/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.AppStatsList
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.Alerts
import io.nekohasekai.sagernet.fmt.TAG_SOCKS
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libexclavecore.AppStats
import libexclavecore.Libexclavecore
import libexclavecore.TrafficListener
import java.net.UnknownHostException
import com.github.shadowsocks.plugin.PluginManager as ShadowsocksPluginPluginManager
import io.nekohasekai.sagernet.aidl.AppStats as AidlAppStats

class BaseService {

    enum class State(val canStop: Boolean = false) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle,
        Connecting(true),
        Connected(true),
        Stopping,
        Stopped,
    }

    interface ExpectedException
    class ExpectedExceptionWrapper(e: Exception) : Exception(e.localizedMessage, e),
        ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { _, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.forceLoad()
                else -> service.stopRunner(keepState = false)
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            binder.stateChanged(s, msg)
            state = s
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(),
        CoroutineScope,
        AutoCloseable,
        TrafficListener {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                stopListeningForBandwidth(callback ?: return)
                stopListeningForStats(callback)
            }
        }
        private val bandwidthListeners = mutableMapOf<IBinder, Long>()  // the binder is the real identifier
        private val statsListeners = mutableMapOf<IBinder, Long>()  // the binder is the real identifier
        override val coroutineContext = Dispatchers.Main.immediate + Job()
        private var looper: Job? = null
        private var statsLooper: Job? = null

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.profile?.displayName() ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback) {
            callbacks.register(cb)
        }

        private val broadcastLock = Mutex()
        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastLock.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (e: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        private suspend fun loop() {
            var lastQueryTime = 0L
            val showDirectSpeed = DataStore.showDirectSpeed
            while (true) {
                val delayMs = bandwidthListeners.values.minOrNull()
                delay(delayMs ?: return)
                if (delayMs == 0L) return
                val queryTime = System.currentTimeMillis()
                val sinceLastQueryInSeconds = (queryTime - lastQueryTime).toDouble() / 1000L
                val proxy = data?.proxy ?: return
                lastQueryTime = queryTime
                val (statsOut, outs) = proxy.outboundStats()
                val stats = TrafficStats(
                    (proxy.uplinkProxy / sinceLastQueryInSeconds).toLong(),
                    (proxy.downlinkProxy / sinceLastQueryInSeconds).toLong(),
                    if (showDirectSpeed) (proxy.uplinkDirect() / sinceLastQueryInSeconds).toLong() else 0L,
                    if (showDirectSpeed) (proxy.downlinkDirect() / sinceLastQueryInSeconds).toLong() else 0L,
                    statsOut.uplinkTotal,
                    statsOut.downlinkTotal
                )
                if (data?.state == State.Connected && bandwidthListeners.isNotEmpty()) {
                    broadcast { item ->
                        if (bandwidthListeners.contains(item.asBinder())) {
                            item.trafficUpdated(proxy.profile.id, stats, true)
                            outs.forEach { (profileId, stats) ->
                                item.trafficUpdated(
                                    profileId, TrafficStats(
                                        txRateDirect = stats.uplinkTotal,
                                        rxTotal = stats.downlinkTotal
                                    ), false
                                )
                            }
                        }
                    }
                }

            }

        }

        val appStats = ArrayList<AppStats>()
        override fun updateStats(t: AppStats) {
            appStats.add(t)
        }

        private suspend fun loopStats() {
            var lastQueryTime = 0L
            var tun = (data?.proxy?.service as? VpnService)?.tun ?: return
            if (!tun.trafficStatsEnabled) return

            PackageCache.awaitLoadSync()
            while (true) {
                val delayMs = statsListeners.values.minOrNull()
                if (delayMs == 0L) return
                val queryTime = System.currentTimeMillis()
                val sinceLastQueryInSeconds = ((queryTime - lastQueryTime).toDouble() / 1000).toLong()
                lastQueryTime = queryTime

                appStats.clear()
                tun = (data?.proxy?.service as? VpnService)?.tun ?: return
                tun.readAppTraffics(this)

                val statsList = AppStatsList(appStats.map {
                    val uid = it.uid
                    AidlAppStats(
                        uid,
                        it.tcpConn,
                        it.udpConn,
                        it.tcpConnTotal,
                        it.udpConnTotal,
                        it.uplink / sinceLastQueryInSeconds,
                        it.downlink / sinceLastQueryInSeconds,
                        it.uplinkTotal,
                        it.downlinkTotal,
                        it.deactivateAt
                    )
                })
                if (data?.state == State.Connected && statsListeners.isNotEmpty()) {
                    broadcast { item ->
                        if (statsListeners.contains(item.asBinder())) {
                            item.statsUpdated(statsList)
                        }
                    }
                }
                delay(delayMs ?: return)
            }

        }

        override fun startListeningForBandwidth(
            cb: ISagerNetServiceCallback,
            timeout: Long,
        ) {
            launch {
                if (bandwidthListeners.isEmpty() and (bandwidthListeners.put(
                        cb.asBinder(), timeout
                    ) == null)
                ) {
                    check(looper == null)
                    looper = launch {
                        loop()
                        looper = null
                    }
                }
                if (data?.state != State.Connected) return@launch
                val data = data
                data?.proxy ?: return@launch
                val sum = TrafficStats()
                cb.trafficUpdated(0, sum, true)
            }
        }

        override fun stopListeningForBandwidth(cb: ISagerNetServiceCallback) {
            launch {
                if (bandwidthListeners.remove(cb.asBinder()) != null && bandwidthListeners.isEmpty() && looper != null) {
                    looper!!.cancel()
                    looper = null
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            stopListeningForBandwidth(cb)   // saves an RPC, and safer
            stopListeningForStats(cb)
            callbacks.unregister(cb)
        }

        override fun protect(fd: Int) {
            (data?.proxy?.service as VpnService?)?.protect(fd)
        }

        override fun urlTest(): Int {
            val v2rayPoint = data?.proxy?.v2rayPoint ?: error("core not started")
            try {
                return Libexclavecore.urlTest(
                    v2rayPoint, TAG_SOCKS, DataStore.connectionTestURL, 5000
                )
            } catch (e: Exception) {
                Logs.w(e)
                error(e)
            }
        }

        override fun startListeningForStats(cb: ISagerNetServiceCallback, timeout: Long) {
            launch {
                if (statsListeners.isEmpty() and (statsListeners.put(
                        cb.asBinder(), timeout
                    ) == null)
                ) {
                    check(statsLooper == null)
                    statsLooper = launch {
                        loopStats()
                        statsLooper = null
                    }
                }
            }
        }

        fun checkLoop() {
            if (bandwidthListeners.isNotEmpty() && looper == null) {
                looper = launch {
                    loop()
                    looper = null
                }
            }
            if (statsListeners.isNotEmpty() && statsLooper == null) {
                statsLooper = launch {
                    loopStats()
                    statsListeners.clear()
                    statsLooper = null
                }
            }
        }

        override fun stopListeningForStats(cb: ISagerNetServiceCallback) {
            launch {
                if (statsListeners.remove(cb.asBinder()) != null && statsListeners.isEmpty() && statsLooper != null) {
                    statsLooper!!.cancel()
                    statsLooper = null
                }
            }
        }

        override fun resetTrafficStats() {
            runOnDefaultDispatcher {
                SagerDatabase.statsDao.deleteAll()
                (data?.proxy?.service as? VpnService)?.tun?.resetAppTraffics()
                val empty = AppStatsList(emptyList())
                broadcast { item ->
                    if (statsListeners.contains(item.asBinder())) {
                        item.statsUpdated(empty)
                    }
                }
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun profilePersisted(ids: List<Long>) = launch {
            if (bandwidthListeners.isNotEmpty() && ids.isNotEmpty()) broadcast { item ->
                if (bandwidthListeners.contains(item.asBinder())) ids.forEach(item::profilePersisted)
            }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun getTrafficStatsEnabled(): Boolean {
            return (data?.proxy?.service as? VpnService)?.tun?.trafficStatsEnabled ?: false
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun forceLoad() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            startService(Intent(this, javaClass))
        }

        fun killProcesses() {
            data.proxy?.close()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null, keepState: Boolean = true) {
            data.notification?.destroy()
            data.notification = null
            if (data.state == State.Stopping) return
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                killProcesses()
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.binder.profilePersisted(listOfNotNull(data.proxy).map { it.profile.id })
                    data.proxy = null
                }

                // change the state
                data.changeState(State.Stopped, msg)
                DataStore.startedProfile = 0L
                if (!keepState) DataStore.currentProfile = 0L
                // stop the service if nothing has bound to it
                if (restart) startRunner() else { //   BootReceiver.enabled = false
                    stopSelf()
                }
            }
        }

        fun persistStats() {
            data.proxy?.persistStats()
            (this as? VpnService)?.persistAppStats()
        }

        suspend fun preInit() {}

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }
            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(data.receiver, IntentFilter().apply {
                        addAction(Action.RELOAD)
                        addAction(Intent.ACTION_SHUTDOWN)
                        addAction(Action.CLOSE)
                    }, "$packageName.SERVICE", null, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(data.receiver, IntentFilter().apply {
                        addAction(Action.RELOAD)
                        addAction(Intent.ACTION_SHUTDOWN)
                        addAction(Action.CLOSE)
                    }, "$packageName.SERVICE", null)
                }
                data.closeReceiverRegistered = true
            }

            val group = SagerDatabase.groupDao.getById(profile.groupId)
            data.notification = if (DataStore.showGroupName && group != null){
                createNotification("[" + group.displayName() + "] " + profile.displayName())
            } else {
                createNotification(profile.displayName())
            }

            data.changeState(State.Connecting)
            runOnMainDispatcher {
                try {
                    preInit()
                    proxy.init()
                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }
                    DataStore.currentProfile = profile.id
                    DataStore.startedProfile = profile.id
                    startProcesses()
                    data.changeState(State.Connected)
                    data.binder.checkLoop()

                    for ((type, routeName) in proxy.config.alerts) {
                        data.binder.broadcast {
                            it.routeAlert(type, routeName)
                        }
                    }
                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Logs.d(e.readableMessage)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (e: ShadowsocksPluginPluginManager.PluginNotFoundException) {
                    Logs.d(e.readableMessage)
                    data.binder.missingPlugin("shadowsocks-" + e.plugin)
                    stopRunner(false, null)
                } catch (e: Alerts.RouteAlertException) {
                    data.binder.broadcast {
                        it.routeAlert(e.alert, e.routeName)
                    }
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc is ExpectedException) Logs.d(exc.readableMessage) else Logs.w(exc)
                    stopRunner(
                        false, exc.readableMessage
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}