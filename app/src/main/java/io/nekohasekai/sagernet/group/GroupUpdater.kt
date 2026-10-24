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

package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    data class UpdateResult(
        val success: Boolean,
        val duplicateCount: Int = 0,
        val skipped: Boolean = false,
        val timedOut: Boolean = false
    )

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())
        val lastResults = Collections.synchronizedMap<Long, UpdateResult>(mutableMapOf())
        private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdateWithTimeout(
                    proxyGroup,
                    byUser,
                    DataStore.subscriptionUpdateTimeoutSeconds.coerceAtLeast(10) * 1000L
                )
            }
        }

        suspend fun executeUpdateWithTimeout(
            proxyGroup: ProxyGroup,
            byUser: Boolean,
            timeoutMillis: Long,
            forceUpdateWhenDisconnected: Boolean = false
        ): Boolean {
            val update = timeoutScope.async {
                try {
                    executeUpdate(proxyGroup, byUser, forceUpdateWhenDisconnected)
                } catch (e: CancellationException) {
                    false
                }
            }
            return try {
                select {
                    update.onAwait { it }
                    onTimeout(timeoutMillis) {
                        Logs.w("Subscription update timed out: ${proxyGroup.displayName()}")
                        update.cancel()
                        timeoutUpdate(proxyGroup)
                        false
                    }
                }
            } catch (e: CancellationException) {
                update.cancel()
                finishUpdate(proxyGroup)
                throw e
            }
        }

        suspend fun executeUpdate(
            proxyGroup: ProxyGroup,
            byUser: Boolean,
            forceUpdateWhenDisconnected: Boolean = false
        ): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) {
                    lastResults[proxyGroup.id] = UpdateResult(success = false, skipped = true)
                    return@coroutineScope false
                }
                lastResults.remove(proxyGroup.id)
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription!!
                val connected = SagerNet.started && DataStore.startedProfile > 0
                val userInterface = GroupManager.userInterface

                if (subscription.updateWhenConnectedOnly && !connected && !forceUpdateWhenDisconnected) {
                    if (!byUser || userInterface == null) {
                        lastResults[proxyGroup.id] = UpdateResult(success = false, skipped = true)
                        finishUpdate(proxyGroup)
                        cancel()
                    } else {
                        if (!userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            lastResults[proxyGroup.id] = UpdateResult(success = false, skipped = true)
                            finishUpdate(proxyGroup)
                            cancel()
                        }
                    }
                }

                try {
                    when (subscription.type) {
                        SubscriptionType.RAW -> RawUpdater
                        SubscriptionType.SIP008 -> SIP008Updater
                        else -> error("unsupported")
                    }.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: CancellationException) {
                    finishUpdate(proxyGroup)
                    throw e
                } catch (e: Throwable) {
                    Logs.w(e)
                    SubscriptionUpdater.recordFailure(proxyGroup)
                    lastResults[proxyGroup.id] = UpdateResult(success = false)
                    if (byUser && userInterface != null) {
                        userInterface.onUpdateFailure(proxyGroup, e.readableMessage)
                    }
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

        private suspend fun timeoutUpdate(proxyGroup: ProxyGroup) {
            SubscriptionUpdater.recordFailure(proxyGroup)
            lastResults[proxyGroup.id] = UpdateResult(
                success = false,
                skipped = true,
                timedOut = true
            )
            finishUpdate(proxyGroup)
        }

    }

}
