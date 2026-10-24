/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026                                                         *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify        *
 * it under the terms of the GNU General Public License as published by        *
 * the Free Software Foundation, either version 3 of the License, or           *
 * (at your option) any later version.                                         *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DiagnosticLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Owns direct Auto VPN checks while the VPN service is deliberately stopped.
 * VpnService lives in :bg and Android can terminate that process after a stop,
 * therefore this foreground service has a separate process and lifetime.
 */
class AutoVpnDirectSearchService : Service() {

    companion object {
        private const val ACTION_CANDIDATE_SEARCH = "io.nekohasekai.sagernet.AUTO_DIRECT_CANDIDATE_SEARCH"
        private const val ACTION_STOP = "io.nekohasekai.sagernet.AUTO_DIRECT_STOP"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PROFILE_ID = "profileId"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_RESET_RESULTS = "resetResults"
        private const val NOTIFICATION_ID = ServiceNotification.notificationId
        private const val STOP_TIMEOUT_MS = 15_000L
        private const val STOP_POLL_DELAY_MS = 100L
        private const val SETTLE_DELAY_MS = 2_000L

        fun startCandidateSearch(context: Context, mode: Int, profileId: Long, reason: String): Boolean {
            return start(
                context,
                Intent(context, AutoVpnDirectSearchService::class.java)
                    .setAction(ACTION_CANDIDATE_SEARCH)
                    .putExtra(EXTRA_MODE, mode)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_RESET_RESULTS, false),
            )
        }

        fun startInitialSearch(context: Context, mode: Int): Boolean {
            return start(
                context,
                Intent(context, AutoVpnDirectSearchService::class.java)
                    .setAction(ACTION_CANDIDATE_SEARCH)
                    .putExtra(EXTRA_MODE, mode)
                    .putExtra(EXTRA_PROFILE_ID, 0L)
                    .putExtra(EXTRA_REASON, "initial-scan")
                    .putExtra(EXTRA_RESET_RESULTS, true),
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, AutoVpnDirectSearchService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                DiagnosticLog.error("auto-vpn", "direct-search-service-stop-failed", e)
            }
        }

        private fun start(context: Context, intent: Intent): Boolean {
            try {
                ContextCompat.startForegroundService(context, intent)
                return true
            } catch (e: Exception) {
                DiagnosticLog.error("auto-vpn", "direct-search-service-start-failed", e)
                return false
            }
        }
    }

    private var searchJob: Job? = null
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val autoStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Action.AUTO_STATUS_CHANGED && foregroundStarted) {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = SagerNet.power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "exclaved:auto-direct-search",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        val filter = IntentFilter(Action.AUTO_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autoStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(autoStatusReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == ACTION_STOP) {
            DiagnosticLog.critical("auto-vpn", "direct-search-service-stop-requested")
            AutoVpnManager.stopDetachedDirectSearch()
            searchJob?.cancel()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (searchJob?.isActive == true) {
            DiagnosticLog.warn("auto-vpn", "direct-search-service-request-ignored action=$action")
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
        val mode = intent.getIntExtra(EXTRA_MODE, AutoVpnManager.MODE_ANY)
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val resetResults = intent.getBooleanExtra(EXTRA_RESET_RESULTS, false)
        searchJob = runOnDefaultDispatcher {
            try {
                DiagnosticLog.critical(
                    "auto-vpn",
                    "direct-search-service-start action=$action mode=$mode profile=$profileId reason=$reason"
                )
                if (!waitForVpnToStop()) {
                    AutoVpnManager.reportDetachedSearchStopTimeout(mode, reason)
                    return@runOnDefaultDispatcher
                }
                delay(SETTLE_DELAY_MS)
                when (action) {
                    ACTION_CANDIDATE_SEARCH -> AutoVpnManager.runDetachedDirectCandidateSearch(
                        mode = mode,
                        excludedProfileId = profileId,
                        reason = reason,
                        resetResults = resetResults,
                    )
                }
            } finally {
                DiagnosticLog.critical("auto-vpn", "direct-search-service-finish action=$action")
                foregroundStarted = false
                if (DataStore.startedProfile > 0L || DataStore.autoVpnServiceStartRequested) {
                    // The VPN service takes over the same notification ID during this handoff.
                    ServiceCompat.stopForeground(this@AutoVpnDirectSearchService, ServiceCompat.STOP_FOREGROUND_DETACH)
                } else {
                    ServiceCompat.stopForeground(this@AutoVpnDirectSearchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        searchJob?.cancel()
        searchJob = null
        foregroundStarted = false
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        unregisterReceiver(autoStatusReceiver)
        super.onDestroy()
    }

    private suspend fun waitForVpnToStop(): Boolean {
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (DataStore.startedProfile > 0L && System.currentTimeMillis() < deadline) {
            delay(STOP_POLL_DELAY_MS)
        }
        return DataStore.startedProfile <= 0L
    }

    private fun buildNotification(): android.app.Notification {
        val text = AutoVpnManager.notificationText(getString(R.string.auto_vpn_notification_searching))
        return NotificationCompat.Builder(this, "service-profile-test")
        .setSmallIcon(R.drawable.ic_service_active)
        .setContentTitle(AutoVpnManager.notificationTitle(""))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    }
}
