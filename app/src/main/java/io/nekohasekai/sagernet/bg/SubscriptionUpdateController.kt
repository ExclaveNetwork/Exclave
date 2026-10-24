/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026                                                       *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SubscriptionUpdateController {

    enum class Event {
        PERIODIC,
    }

    private const val UPDATE_NOTIFICATION_ID = 2

    private val eventMutex = Mutex()

    suspend fun handleEvent(event: Event): SubscriptionUpdater.UpdateSummary? {
        return eventMutex.withLock {
            if (!DataStore.subscriptionGlobalAutoUpdate) return@withLock null
            if (SubscriptionUpdater.isUpdating()) return@withLock null

            when (event) {
                Event.PERIODIC -> handleBackgroundUpdateAll()
            }
        }
    }

    private suspend fun handleBackgroundUpdateAll(): SubscriptionUpdater.UpdateSummary? {
        val groups = SagerDatabase.groupDao.subscriptions()
        if (groups.isEmpty()) return null

        val summary = updateGroupsWithNotification(groups)
        if (DataStore.subscriptionAutoSortGroups) {
            GroupManager.sortGroupsByTotalProfiles()
        }
        return summary
    }

    suspend fun updateGroupsWithNotification(groups: List<ProxyGroup>): SubscriptionUpdater.UpdateSummary {
        val nm = NotificationManagerCompat.from(app)
        val notification = NotificationCompat.Builder(app, "service-subscription-silent")
            .setWhen(0)
            .setTicker(app.getString(R.string.forward_success))
            .setContentTitle(app.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(SagerNet.configureIntent(app))

        return try {
            notification.setContentText(app.getString(R.string.subscription_update_progress, 0, groups.size))
            nm.notify(UPDATE_NOTIFICATION_ID, notification.build())
            SubscriptionUpdater.updateGroups(
                groups,
                byUser = false,
                forceUpdateWhenDisconnected = true,
                onGroupStart = { profile ->
                    notification.setContentText(
                        app.getString(R.string.subscription_update_message, profile.displayName())
                    )
                    nm.notify(UPDATE_NOTIFICATION_ID, notification.build())
                },
                onProgress = { summary ->
                    notification.setContentText(
                        app.getString(
                            R.string.subscription_update_progress,
                            summary.done,
                            summary.total
                        )
                    )
                    nm.notify(UPDATE_NOTIFICATION_ID, notification.build())
                }
            ).also {
                AutoVpnManager.onSubscriptionsUpdated()
            }
        } finally {
            nm.cancel(UPDATE_NOTIFICATION_ID)
        }
    }

}
