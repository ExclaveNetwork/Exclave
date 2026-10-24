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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object VpnSubscriptionUpdateScheduler {

    private const val WORK_NAME = "VpnSubscriptionUpdateScheduler"
    private const val KEY_RETRY_ATTEMPT = "retryAttempt"

    fun isVpnRunning(): Boolean {
        return DataStore.serviceMode == Key.MODE_VPN && DataStore.startedProfile > 0L
    }

    fun shouldOwnBackgroundUpdates(): Boolean {
        return DataStore.subscriptionGlobalAutoUpdate &&
                DataStore.subscriptionVpnBackgroundUpdate &&
                isVpnRunning()
    }

    fun onVpnStarted() {
        if (!shouldOwnBackgroundUpdates()) {
            cancel()
            return
        }
        schedule(initialDelayMinutes(), retryAttempt = 0)
    }

    fun onVpnStopped() {
        cancel()
    }

    fun reconfigureForState() {
        if (shouldOwnBackgroundUpdates()) {
            schedule(initialDelayMinutes(), retryAttempt = 0)
        } else {
            cancel()
        }
    }

    private fun schedule(delayMinutes: Long, retryAttempt: Int) {
        val request = OneTimeWorkRequestBuilder<UpdateTask>()
            .setInitialDelay(delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_RETRY_ATTEMPT to retryAttempt.coerceAtLeast(0)))
            .build()

        RemoteWorkManager.getInstance(app).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Logs.d("VpnSubscriptionUpdateScheduler scheduled: $delayMinutes min, retry=$retryAttempt")
    }

    private fun cancel() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    private fun autoUpdateIntervalMinutes(): Long {
        return DataStore.subscriptionVpnIntervalMinutes.coerceAtLeast(1).toLong()
    }

    private fun initialDelayMinutes(): Long {
        return DataStore.subscriptionVpnInitialDelayMinutes.coerceAtLeast(1).toLong()
    }

    private fun retryDelayMinutes(): Long {
        return DataStore.subscriptionVpnRetryIntervalMinutes.coerceAtLeast(1).toLong()
    }

    private fun retryCount(): Int {
        return DataStore.subscriptionVpnRetryCount.coerceAtLeast(0)
    }

    private fun staleSubscriptionGroups(groups: List<ProxyGroup>): List<ProxyGroup> {
        val now = System.currentTimeMillis() / 1000L
        val interval = autoUpdateIntervalMinutes() * 60L
        return groups.filter { group ->
            val lastUpdated = group.subscription?.lastUpdated ?: 0L
            lastUpdated <= 0L || now - lastUpdated >= interval
        }
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            if (!shouldOwnBackgroundUpdates()) return Result.success()

            val retryAttempt = inputData.getInt(KEY_RETRY_ATTEMPT, 0).coerceAtLeast(0)
            runCatching {
                val beforeUpdate = staleSubscriptionGroups(SagerDatabase.groupDao.subscriptions())
                if (beforeUpdate.isNotEmpty()) {
                    SubscriptionUpdateController.updateGroupsWithNotification(beforeUpdate)
                    if (DataStore.subscriptionAutoSortGroups) {
                        GroupManager.sortGroupsByTotalProfiles()
                    }
                }

                if (!shouldOwnBackgroundUpdates()) return@runCatching

                val remaining = staleSubscriptionGroups(SagerDatabase.groupDao.subscriptions())
                when {
                    remaining.isEmpty() -> schedule(autoUpdateIntervalMinutes(), retryAttempt = 0)
                    retryAttempt < retryCount() -> {
                        schedule(retryDelayMinutes(), retryAttempt = retryAttempt + 1)
                    }
                    else -> schedule(autoUpdateIntervalMinutes(), retryAttempt = 0)
                }
            }.onFailure {
                Logs.w(it)
                if (shouldOwnBackgroundUpdates()) {
                    if (retryAttempt < retryCount()) {
                        schedule(retryDelayMinutes(), retryAttempt = retryAttempt + 1)
                    } else {
                        schedule(autoUpdateIntervalMinutes(), retryAttempt = 0)
                    }
                }
            }

            return Result.success()
        }
    }
}
