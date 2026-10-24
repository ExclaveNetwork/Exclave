/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.content.Context
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private val updateMutex = Mutex()

    data class UpdateSummary(
        val total: Int,
        var done: Int = 0,
        var success: Int = 0,
        var errors: Int = 0,
        var skipped: Int = 0,
        var duplicates: Int = 0,
        var failedOrSkippedIds: Set<Long> = emptySet()
    )

    fun hasWorkingInternet(): Boolean {
        val network = SagerNet.connectivity.activeNetwork ?: return false
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isUpdating(): Boolean {
        return updateMutex.isLocked
    }

    suspend fun reconfigureUpdater() {
        if (!DataStore.subscriptionGlobalAutoUpdate) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        val subscriptions = SagerDatabase.groupDao.subscriptions()
        if (subscriptions.isEmpty()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay = DataStore.subscriptionGlobalAutoUpdateDelay.toLong()
        if (minDelay < 15) minDelay = 15

        val request = PeriodicWorkRequest.Builder(
            UpdateTask::class.java,
            minDelay,
            TimeUnit.MINUTES
        ).setInitialDelay(minDelay, TimeUnit.MINUTES).build()

        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Logs.d("reconfigureUpdater, interval: $minDelay min")
    }

    private fun cooldownSeconds(): Long {
        return DataStore.subscriptionGlobalAutoUpdateDelay.coerceAtLeast(1) * 60L
    }

    suspend fun recordAttempt(group: ProxyGroup) {
        group.subscription?.let { subscription ->
            subscription.lastAttempt = System.currentTimeMillis() / 1000L
            SagerDatabase.groupDao.updateGroup(group)
        }
    }

    fun markSuccess(group: ProxyGroup, now: Long = System.currentTimeMillis() / 1000L) {
        group.subscription?.let { subscription ->
            subscription.lastUpdated = now
            subscription.lastSuccess = now
            subscription.lastFailure = 0L
            subscription.cooldownUntil = 0L
            subscription.failureCount = 0
            subscription.lastAttempt = now
        }
    }

    suspend fun recordFailure(group: ProxyGroup) {
        group.subscription?.let { subscription ->
            val now = System.currentTimeMillis() / 1000L
            subscription.lastFailure = now
            subscription.lastAttempt = now
            subscription.failureCount = (subscription.failureCount ?: 0) + 1
            subscription.cooldownUntil = now + cooldownSeconds()
            SagerDatabase.groupDao.updateGroup(group)
        }
    }

    suspend fun ensureCooldown(group: ProxyGroup) {
        group.subscription?.let { subscription ->
            val now = System.currentTimeMillis() / 1000L
            if ((subscription.lastFailure ?: 0L) < (subscription.lastAttempt ?: 0L)) {
                subscription.lastFailure = now
                subscription.failureCount = (subscription.failureCount ?: 0) + 1
            }
            subscription.cooldownUntil = now + cooldownSeconds()
            SagerDatabase.groupDao.updateGroup(group)
        }
    }

    suspend fun updateGroups(
        groups: List<ProxyGroup>,
        byUser: Boolean = false,
        forceUpdateWhenDisconnected: Boolean = false,
        onGroupStart: suspend (ProxyGroup) -> Unit = {},
        onProgress: suspend (UpdateSummary) -> Unit = {}
    ): UpdateSummary = coroutineScope {
        if (!updateMutex.tryLock()) {
            return@coroutineScope UpdateSummary(
                total = groups.size,
                done = groups.size,
                errors = groups.size,
                failedOrSkippedIds = groups.map { it.id }.toSet()
            )
        }

        val parallel = DataStore.subscriptionUpdateParallel.coerceAtLeast(1)
        val timeoutMillis = DataStore.subscriptionUpdateTimeoutSeconds.coerceAtLeast(10) * 1000L
        val semaphore = Semaphore(parallel)
        val lock = Mutex()
        val summary = UpdateSummary(total = groups.size)

        try {
            groups.map { group ->
                async {
                    semaphore.withPermit {
                        recordAttempt(group)
                        onGroupStart(group)
                        val ok = GroupUpdater.executeUpdateWithTimeout(
                            group,
                            byUser,
                            timeoutMillis,
                            forceUpdateWhenDisconnected
                        )
                        val result = GroupUpdater.lastResults[group.id]
                        val snapshot = lock.withLock {
                            when {
                                result?.timedOut == true -> {
                                    summary.skipped++
                                    summary.failedOrSkippedIds = summary.failedOrSkippedIds + group.id
                                }
                                ok && result?.success != false -> summary.success++
                                else -> {
                                    summary.errors++
                                    summary.failedOrSkippedIds = summary.failedOrSkippedIds + group.id
                                }
                            }
                            summary.duplicates += result?.duplicateCount ?: 0
                            summary.done++
                            summary.copy()
                        }
                        onProgress(snapshot)
                    }
                }
            }.awaitAll()

            summary
        } finally {
            updateMutex.unlock()
        }
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            if (VpnSubscriptionUpdateScheduler.shouldOwnBackgroundUpdates()) {
                Logs.d("SubscriptionUpdater skipped periodic update: VPN background loop is active")
                return Result.success()
            }
            SubscriptionUpdateController.handleEvent(SubscriptionUpdateController.Event.PERIODIC)
            return Result.success()
        }
    }

}
