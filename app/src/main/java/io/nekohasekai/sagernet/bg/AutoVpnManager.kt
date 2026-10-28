/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026                                                         *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify        *
 * it under the terms of the GNU General Public License as published by        *
 * the Free Software Foundation, either version 3 of the License, or           *
 *  (at your option) any later version.                                        *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.content.Intent
import android.database.sqlite.SQLiteDatabaseLockedException
import android.net.NetworkCapabilities
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyListItem
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onIoDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.IDN
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object AutoVpnManager {

    const val AUTO_PROFILE_ID = -1L
    const val AUTO_NON_RU_PROFILE_ID = -2L
    const val AUTO_GEMINI_PROFILE_ID = -3L

    const val MODE_ANY = 0
    const val MODE_NON_RU = 1
    const val MODE_GEMINI = 2

    private const val CURRENT_VERIFY_ATTEMPTS = 3
    private const val CURRENT_VERIFY_RETRY_DELAY_MS = 2000L
    private const val RECENT_WORKING_TTL_MS = 30L * 60L * 1000L
    private const val AUTO_SCAN_PAGE_SIZE = 200
    private const val AUTO_SCAN_NEXT_CYCLE_DELAY_MS = 1000L
    private const val SCANNED_IDS_PERSIST_MIN_INTERVAL_MS = 5000L
    private const val ACTIVE_MONITOR_STALE_GRACE_MS = 60_000L
    private const val DIRECT_SEARCH_STALE_MS = 45_000L
    private const val DIRECT_TEST_VPN_SETTLE_DELAY_MS = 2000L
    private const val DIRECT_TEST_VPN_STOP_TIMEOUT_MS = 15_000L
    private const val DIRECT_TEST_VPN_STOP_POLL_DELAY_MS = 100L
    private const val AUTO_SEARCH_NOTIFICATION_ID = 4
    private const val MAX_GEMINI_REJECTED_KEYS = 5000
    private const val PROFILE_RESULT_DB_RETRY_COUNT = 4
    private const val PROFILE_RESULT_DB_RETRY_DELAY_MS = 250L
    private const val CANDIDATE_TEST_HARD_TIMEOUT_GRACE_MS = 5_000L
    private const val TRAFFIC_SAMPLE_INTERVAL_MS = 10_000L
    private const val TRAFFIC_SUSPICIOUS_INTERVALS = 2
    private const val TRAFFIC_OUTGOING_MIN_BYTES = 1024L
    private const val TRAFFIC_INCOMING_MAX_BYTES = 256L
    private const val TRAFFIC_CONFIRM_DELAY_MS = 5_000L
    private const val TRAFFIC_DIAGNOSTIC_COOLDOWN_MS = 60_000L
    private const val DIRECT_NETWORK_RETRY_DELAY_MS = 45_000L
    private const val DIRECT_NETWORK_CHECK_TIMEOUT_MS = 4_000L
    private const val DIRECT_NETWORK_CONNECT_TIMEOUT_MS = 3_000
    private const val DIRECT_NETWORK_READ_TIMEOUT_MS = 3_000
    private const val DIRECT_NETWORK_CHECK_URL = "https://ya.ru/"
    private const val RUSSIA_FLAG = "\uD83C\uDDF7\uD83C\uDDFA"
    private const val RUSSIA_CYRILLIC_TLD = "\u0440\u0444"
    private val POST_CONNECT_VERIFY_DELAYS_MS = longArrayOf(3000L, 5000L, 5000L)

    private data class CandidateResult(
        val profile: ProxyEntity,
        val ping: Int,
    )

    private data class CandidateOutcome(
        val result: CandidateResult?,
        val elapsedMillis: Long,
        val error: Throwable?,
    )

    private data class CachedWorking(
        val ping: Int,
        val at: Long,
    )

    private data class ActiveCheckResult(
        val bad: Boolean,
        val ping: Int? = null,
        val exitCountryRejected: Boolean = false,
    )

    private data class AutoProgressSnapshot(
        val generation: Long,
        val running: Boolean,
        val total: Int,
        val finished: Int,
        val working: Int,
    ) {
        fun encode(): String = listOf(
            generation,
            if (running) 1 else 0,
            total,
            finished,
            working,
        ).joinToString("|")
    }

    data class ProgressSummary(
        val finished: Int,
        val total: Int,
        val working: Int,
        val reserve: Int,
        val reserveTarget: Int,
    )

    private class AutoTestThrottle(
        private val quickFailureLimit: Int,
        private val quickFailureMillis: Int,
        private val quickFailurePauseMillis: Long,
        private val scheduledBreakAfterCount: Int,
        private val scheduledBreakMillis: Long,
    ) {
        private var quickFailureCounter = 0
        private var quickFailurePauseUntil = 0L
        private var scheduledBreakCounter = 0
        private var scheduledBreakUntil = 0L

        suspend fun waitForPause() {
            while (true) {
                val now = System.currentTimeMillis()
                val wait = maxOf(quickFailurePauseUntil, scheduledBreakUntil) - now
                if (wait <= 0L) return
                delay(wait.coerceAtMost(250L))
            }
        }

        fun record(outcome: CandidateOutcome) {
            val error = outcome.error
            if (outcome.result != null) {
                quickFailureCounter = 0
            } else if (error != null) {
                val quickNetworkFailure =
                    outcome.elapsedMillis <= quickFailureMillis &&
                            ProfileTestRunner.isLikelyTransientNetworkFailure(error)
                if (quickNetworkFailure) {
                    quickFailureCounter += 1
                    if (quickFailureCounter >= quickFailureLimit) {
                        quickFailureCounter = 0
                        quickFailurePauseUntil = maxOf(
                            quickFailurePauseUntil,
                            System.currentTimeMillis() + quickFailurePauseMillis,
                        )
                    }
                } else {
                    quickFailureCounter = 0
                }
            }

            if (scheduledBreakAfterCount > 0 && scheduledBreakMillis > 0L) {
                scheduledBreakCounter += 1
                if (scheduledBreakCounter >= scheduledBreakAfterCount) {
                    scheduledBreakCounter = 0
                    scheduledBreakUntil = maxOf(
                        scheduledBreakUntil,
                        System.currentTimeMillis() + scheduledBreakMillis,
                    )
                }
            }
        }
    }

    private data class Ipv4Cidr(
        val network: Long,
        val mask: Long,
    ) {
        fun contains(ip: Long): Boolean = ip and mask == network
    }

    private val switchMutex = Mutex()
    private val reserveUpdateLock = Any()
    private val candidateTestExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "exclaved-auto-candidate-test").apply { isDaemon = true }
    }
    // Native URL tests may ignore coroutine cancellation. Keep candidate workers outside
    // the selection coroutine so cancelled tail tests cannot delay VPN startup.
    private val candidateTestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val failedUntil = ConcurrentHashMap<Long, Long>()
    private val workingCache = ConcurrentHashMap<Long, CachedWorking>()
    private val workingPools = mutableMapOf<Int, List<Long>>()
    private val hydratedReserveModes = mutableSetOf<Int>()
    private val manualCycleIds = mutableMapOf<Int, LinkedHashSet<Long>>()
    private val scannedIds = mutableMapOf<Int, LinkedHashSet<Long>>()
    private val lastScannedIdsPersistAt = mutableMapOf<Int, Long>()
    @Volatile
    private var cachedGeminiRejectedKeys: Set<String>? = null
    @Volatile
    private var cachedGeminiRejectedNames: Set<String>? = null
    @Volatile
    private var cachedGeminiRejectedRevision = Long.MIN_VALUE
    private val listeners = LinkedHashSet<Listener>()
    private val progressLock = Any()
    private val russianTokenRegex = Regex(
        """(^|[^a-z0-9\u0430-\u044f])(?:ru|rus|russia|russian|\u0440\u043e\u0441\u0441\u0438\u044f|\u0440\u043e\u0441\u0441\u0438\u0439\u0441\u043a\u0438\u0439|\u043c\u043e\u0441\u043a\u0432\u0430|moscow|msk|spb|\u0440\u0444)(?=$|[^a-z0-9\u0430-\u044f])""",
        RegexOption.IGNORE_CASE,
    )
    private val isoCountryCodes by lazy { Locale.getISOCountries().toSet() }
    private val knownCountryNameRegex by lazy {
        val names = (Locale.getISOCountries().flatMap { code ->
            val country = Locale.Builder().setRegion(code).build()
            listOf(
                country.getDisplayCountry(Locale.ENGLISH),
                country.getDisplayCountry(Locale.forLanguageTag("ru")),
                country.getDisplayCountry(Locale.getDefault()),
            )
        } + listOf("USA", "UAE", "США", "ОАЭ", "Великобритания"))
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT).replace('ё', 'е') }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("(?<![\\p{L}\\p{N}])(?:$names)(?![\\p{L}\\p{N}])")
    }
    private val russianIpv4Cidrs by lazy {
        listOf(
            "2.60.0.0/14",
            "5.8.0.0/13",
            "5.16.0.0/12",
            "31.173.0.0/16",
            "37.9.0.0/16",
            "37.140.0.0/16",
            "46.17.40.0/21",
            "46.38.48.0/20",
            "46.61.0.0/16",
            "77.88.0.0/18",
            "79.104.0.0/13",
            "80.68.240.0/20",
            "81.19.64.0/20",
            "83.220.160.0/19",
            "84.201.128.0/18",
            "87.240.128.0/18",
            "89.108.64.0/18",
            "90.156.128.0/17",
            "93.158.128.0/18",
            "95.163.0.0/16",
            "109.95.0.0/16",
            "109.120.128.0/17",
            "178.154.128.0/17",
            "185.32.56.0/22",
            "185.71.76.0/22",
            "213.180.192.0/19",
        ).mapNotNull(::parseCidr)
    }

    private var monitorJob: Job? = null
    private var immediateVerifyJob: Job? = null
    private var autoScanJob: Job? = null
    private var trafficMonitorJob: Job? = null
    private var trafficDiagnosticJob: Job? = null
    private var directNetworkRetryJob: Job? = null
    private var lastTrafficDiagnosticAt = 0L
    @Volatile
    private var lastMonitorTickAt = 0L
    private var failures = 0
    private var lastSwitchAt = 0L
    private var lastAutoProgressNotifyAt = 0L
    private var autoProgressGeneration = 0L
    private var lastCheckedPingMillis: Int? = null
    private var lastCheckedPingFailed = false
    @Volatile
    private var lastDecisionReason: String? = null
    @Volatile
    private var pendingAutoServiceTransition = false
    @Volatile
    private var notificationStatus: String? = null
    @Volatile
    private var notificationDetailStatus: String? = null
    @Volatile
    private var notificationBriefStatus: String? = null
    @Volatile
    private var activeUrlTester: (() -> Int)? = null
    @Volatile
    private var activeTrafficSampler: (suspend () -> TrafficStats?)? = null
    @Volatile
    var autoRunning = false
        private set

    @Volatile
    var autoTotal = 0
        private set

    @Volatile
    var autoFinished = 0
        private set

    @Volatile
    var autoWorking = 0
        private set

    @Volatile
    var testResultsResetGeneration = 0
        private set

    interface Listener {
        fun onAutoVpnStateChanged()
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners += listener
        }
        listener.onAutoVpnStateChanged()
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners -= listener
        }
    }

    fun hasVisibleState(): Boolean {
        syncVisibleStateFromStore()
        return DataStore.autoVpnEnabled && (autoRunning || notificationStatus != null)
    }

    fun isAutoRunning(): Boolean {
        syncVisibleStateFromStore()
        return autoRunning
    }

    fun hasProgressState(): Boolean {
        syncVisibleStateFromStore()
        return DataStore.autoVpnEnabled && (autoRunning || autoTotal > 0)
    }

    fun progressText(): String {
        syncVisibleStateFromStore()
        return app.getString(R.string.connection_test_progress, autoFinished, autoTotal)
    }

    fun progressPercent(): Int {
        syncVisibleStateFromStore()
        val total = autoTotal
        return if (total > 0) {
            ((autoFinished.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    fun progressSummary(mode: Int = DataStore.autoVpnMode): ProgressSummary {
        syncVisibleStateFromStore()
        return ProgressSummary(
            finished = autoFinished,
            total = autoTotal,
            working = autoWorking,
            reserve = storedReserveCount(mode),
            reserveTarget = reserveTarget(),
        )
    }

    fun reserveStatus(mode: Int = DataStore.autoVpnMode): String {
        syncVisibleStateFromStore()
        val count = storedReserveCount(mode)
        return app.getString(R.string.auto_vpn_candidates_count, count, reserveTarget())
    }

    fun appWorkingText(mode: Int = DataStore.autoVpnMode): String {
        syncVisibleStateFromStore()
        return reserveStatus(mode)
    }

    fun cardStatusText(mode: Int = DataStore.autoVpnMode): String {
        syncVisibleStateFromStore()
        val parts = ArrayList<String>()
        parts += notificationDetailStatus
            ?: notificationStatus
            ?: app.getString(R.string.auto_vpn_notification_running, modeName(mode))
        if (!autoRunning) lastDecisionReason?.let { parts += it }
        lastPingText()?.let { parts += it }
        return parts.joinToString(" | ")
    }

    fun diagnosticsText(mode: Int = DataStore.autoVpnMode): String {
        syncVisibleStateFromStore()
        val safeMode = sanitizeMode(mode)
        val currentId = DataStore.startedProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        val current = SagerDatabase.proxyDao.getById(currentId)
        val quarantine = quarantineCount(safeMode)
        return buildString {
            appendLine(app.getString(R.string.auto_vpn_diag_mode, modeName(safeMode)))
            appendLine(app.getString(R.string.auto_vpn_diag_current, current?.displayName().orEmpty().ifBlank { "-" }))
            appendLine(app.getString(R.string.auto_vpn_diag_reason, lastDecisionReason ?: current?.let {
                selectionReason(it)
            } ?: "-"))
            appendLine(app.getString(R.string.auto_vpn_diag_reserve, storedReserveCount(safeMode), reserveTarget()))
            appendLine(app.getString(R.string.auto_vpn_diag_progress, autoFinished, autoTotal))
            appendLine(app.getString(R.string.auto_vpn_diag_failures, failures, failuresBeforeSwitch()))
            append(app.getString(R.string.auto_vpn_diag_quarantine, quarantine))
        }
    }

    fun lastPingStatusText(): String? {
        return lastPingText()
    }

    fun hasLastPingError(): Boolean {
        return lastCheckedPingFailed
    }

    fun isGeminiModeActive(): Boolean {
        return DataStore.autoVpnEnabled && sanitizeMode(DataStore.autoVpnMode) == MODE_GEMINI
    }

    fun isAutoProfileId(profileId: Long): Boolean {
        return profileId == AUTO_PROFILE_ID ||
                profileId == AUTO_NON_RU_PROFILE_ID ||
                profileId == AUTO_GEMINI_PROFILE_ID
    }

    fun modeForProfileId(profileId: Long): Int {
        return when (profileId) {
            AUTO_NON_RU_PROFILE_ID -> MODE_NON_RU
            AUTO_GEMINI_PROFILE_ID -> MODE_GEMINI
            else -> MODE_ANY
        }
    }

    fun autoListItems(): List<ProxyListItem> {
        return listOf(
            autoListItem(MODE_ANY),
            autoListItem(MODE_NON_RU),
            autoListItem(MODE_GEMINI),
        )
    }

    fun autoListItem(mode: Int): ProxyListItem {
        syncVisibleStateFromStore()
        val safeMode = sanitizeMode(mode)
        val enabled = DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode
        val currentId = DataStore.startedProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        val current = SagerDatabase.proxyDao.getById(currentId)
        val currentName = current?.displayName().orEmpty()
        val status = when {
            enabled && lastCheckedPingMillis != null -> 1
            enabled && lastCheckedPingFailed -> 3
            enabled && current?.status == 1 -> 1
            enabled -> 0
            else -> 0
        }
        val ping = if (enabled) {
            lastCheckedPingMillis ?: current?.ping ?: 0
        } else {
            0
        }
        val address = when {
            enabled && (SagerNet.started || DataStore.startedProfile > 0L) && currentName.isNotBlank() -> {
                app.getString(R.string.auto_vpn_ready_address, currentName)
            }
            enabled && hasDetailedAppStatus() -> cardStatusText(safeMode)
            enabled -> app.getString(R.string.auto_vpn_enabled_short)
            else -> app.getString(R.string.auto_vpn_tap_to_start)
        }

        return ProxyListItem(
            id = profileIdForMode(safeMode),
            groupId = 0L,
            type = -1,
            userOrder = Long.MIN_VALUE + safeMode,
            tx = current?.tx ?: 0L,
            rx = current?.rx ?: 0L,
            status = status,
            ping = ping,
            error = null,
            profileNameCache = when (safeMode) {
                MODE_NON_RU -> "Auto no RU"
                MODE_GEMINI -> "Auto Gemini"
                else -> "Auto"
            },
            displayNameCache = app.getString(
                when (safeMode) {
                    MODE_NON_RU -> R.string.auto_vpn_no_ru_name
                    MODE_GEMINI -> R.string.auto_vpn_gemini_name
                    else -> R.string.auto_vpn_any_name
                }
            ),
            displayTypeCache = app.getString(R.string.auto_vpn_type),
            displayAddressCache = address,
        )
    }

    fun modeName(mode: Int = DataStore.autoVpnMode): String {
        return app.getString(
            when (sanitizeMode(mode)) {
                MODE_NON_RU -> R.string.auto_vpn_no_ru_name
                MODE_GEMINI -> R.string.auto_vpn_gemini_name
                else -> R.string.auto_vpn_any_name
            }
        )
    }

    fun notificationTitle(profileName: String): String {
        if (!DataStore.autoVpnEnabled) return profileName
        return profileName.ifBlank { modeName() }
    }

    fun notificationText(defaultText: CharSequence): CharSequence {
        if (!DataStore.autoVpnEnabled) return defaultText
        syncVisibleStateFromStore()
        notificationDetailStatus?.let { return it }

        val briefStatus = notificationBriefStatus
        if (briefStatus == app.getString(R.string.auto_vpn_notification_checking) ||
            briefStatus == app.getString(R.string.auto_vpn_notification_switching)
        ) {
            return notificationStatus ?: briefStatus
        }
        if (autoRunning) return autoProgressNotificationText()
        if (briefStatus == app.getString(R.string.auto_vpn_notification_searching)) {
            return if (autoTotal > 0) autoProgressNotificationText() else briefStatus
        }
        return defaultText
    }

    fun selectMode(mode: Int = MODE_ANY) {
        val safeMode = sanitizeMode(mode)
        val modeChanged = !DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode
        if (modeChanged) {
            resetAutoSearchState(cancelScan = true)
        }
        DataStore.autoVpnMode = safeMode
        DataStore.autoVpnEnabled = true
        setAutoStatus(safeMode, R.string.auto_vpn_status_selected)
    }

    fun enableAndStart(mode: Int = MODE_ANY) {
        selectMode(mode)
        startSelectedModeFromVpnButton()
    }

    fun startSelectedModeFromVpnButton(onFinishedWithoutServiceStart: (() -> Unit)? = null): Boolean {
        val safeMode = sanitizeMode(DataStore.autoVpnMode)
        DataStore.autoVpnMode = safeMode
        DataStore.autoVpnEnabled = true
        DiagnosticLog.critical(
            "auto-vpn",
            "start-from-vpn-button mode=$safeMode activeScan=${autoScanJob?.isActive == true} " +
                    "${DiagnosticLog.stateSummary()}"
        )
        setAutoStatus(safeMode, R.string.auto_vpn_status_initial_search)
        if (!isVpnRunningForAuto()) {
            if (isDirectSearchActive()) {
                DiagnosticLog.critical(
                    "auto-vpn",
                    "start-from-vpn-button-reused-direct-search mode=$safeMode " +
                            "token=${DataStore.autoVpnDirectSearchToken}"
                )
                return true
            }
            autoScanJob?.cancel()
            autoScanJob = null
            DataStore.autoVpnServiceStartRequested = false
            if (!AutoVpnDirectSearchService.startInitialSearch(app, safeMode)) {
                setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                onFinishedWithoutServiceStart?.invoke()
            }
            return true
        }
        if (autoScanJob?.isActive == true) {
            onFinishedWithoutServiceStart?.let { callback ->
                val job = autoScanJob
                runOnDefaultDispatcher {
                    job?.join()
                    if (!pendingAutoServiceTransition && !SagerNet.started) {
                        callback()
                    }
                }
            }
            return true
        }
        autoScanJob?.cancel()
        autoScanJob = null

        autoScanJob = runOnDefaultDispatcher {
            try {
                if (!pauseVpnForDirectTests("initial-scan")) {
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    return@runOnDefaultDispatcher
                }
                resetAutoSearchState(cancelScan = false)
                DiagnosticLog.critical("auto-vpn", "initial-scan-reset mode=$safeMode ${DiagnosticLog.stateSummary()}")
                ProfileTestRunner.syncFromStore()
                if (!ProfileTestRunner.isRunning()) {
                    ProfileTestRunner.clearState()
                }

                val groupIds = enabledGroupIds()
                if (groupIds.isEmpty()) {
                    clearAutoProgress()
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                    return@runOnDefaultDispatcher
                }

                clearAutoQuarantineState()
                resetAutoCandidateResults(groupIds)
                resetAutoProgress(SagerDatabase.proxyDao.countByGroups(groupIds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                setAutoStatus(safeMode, R.string.auto_vpn_status_initial_search)
                DiagnosticLog.critical(
                    "auto-vpn",
                    "initial-scan-start mode=$safeMode groups=${groupIds.size} total=$autoTotal target=${reserveTarget()}"
                )

                val working = scanInitialWorkingProfiles(
                    groupIds = groupIds,
                    excludedIds = automaticExcludedIds(safeMode, emptyList()),
                    mode = safeMode,
                    target = reserveTarget(),
                    countProgress = true,
                )

                if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode && working.isEmpty()) {
                    DiagnosticLog.critical("auto-vpn", "initial-scan-no-candidate mode=$safeMode checked=$autoFinished total=$autoTotal")
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                } else if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
                    val best = chooseBestCandidate(working) ?: return@runOnDefaultDispatcher
                    DiagnosticLog.critical(
                        "auto-vpn",
                        "initial-scan-selected mode=$safeMode working=${working.size} " +
                                ProfileTestRunner.profileDiagnosticSummary(best.profile)
                    )
                    switchMutex.withLock {
                        if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
                            if (switchToVerified(
                                    initial = best,
                                    mode = safeMode,
                                    excludedIds = automaticExcludedIds(safeMode, emptyList()),
                                    forceTest = true,
                                    startIfStopped = true,
                                    feedback = true,
                                    recordManualCycle = false,
                                )
                            ) {
                                setAutoReadyStatus(safeMode)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.error("auto-vpn", "initial-scan-error mode=$safeMode", e)
                Logs.w(e)
                if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                }
            } finally {
                DiagnosticLog.critical(
                    "auto-vpn",
                    "initial-scan-finish mode=$safeMode pendingTransition=$pendingAutoServiceTransition " +
                            "started=${SagerNet.started} progress=$autoFinished/$autoTotal working=$autoWorking"
                )
                finishAutoProgress()
                if (!pendingAutoServiceTransition && !SagerNet.started) {
                    onFinishedWithoutServiceStart?.invoke()
                }
            }
        }
        return true
    }

    fun resetGeminiRejectedProfiles(): Int {
        val count = maxOf(geminiRejectedKeys().size, geminiRejectedNames().size)
        DataStore.autoVpnGeminiRejectedKeys = emptySet()
        DataStore.autoVpnGeminiRejectedNames = ""
        updateGeminiRejectedCache(emptySet(), emptySet(), bumpGeminiRejectedRevision())
        if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == MODE_GEMINI) {
            synchronized(workingPools) {
                workingPools.remove(MODE_GEMINI)
                hydratedReserveModes.remove(MODE_GEMINI)
            }
            DataStore.autoVpnReserveIdsGemini = ""
            DataStore.autoVpnReserveCountGemini = 0
            workingCache.clear()
            clearGeminiTransientExclusions()
            setAutoReadyStatus(MODE_GEMINI)
        }
        return count
    }

    /**
     * Runs in the dedicated direct-test foreground process. That process survives
     * stopping the VPN service, so candidate results are never measured through an
     * active tunnel and a handoff cannot leave Auto in a stale searching state.
     */
    internal suspend fun runDetachedDirectCandidateSearch(
        mode: Int,
        excludedProfileId: Long,
        reason: String,
        resetResults: Boolean,
    ): Boolean {
        val safeMode = sanitizeMode(mode)
        if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode) {
            DiagnosticLog.warn(
                "auto-vpn",
                "detached-search-skipped reason=$reason mode=$safeMode enabled=${DataStore.autoVpnEnabled} " +
                        "storedMode=${DataStore.autoVpnMode}"
            )
            return false
        }

        DiagnosticLog.critical(
            "auto-vpn",
            "detached-search-start reason=$reason mode=$safeMode reset=$resetResults excluded=$excludedProfileId"
        )
        try {
            syncVisibleStateFromStore()
            ProfileTestRunner.syncFromStore()
            if (!ProfileTestRunner.isRunning()) {
                ProfileTestRunner.clearState()
            }

            val groupIds = enabledGroupIds()
            if (groupIds.isEmpty()) {
                clearAutoProgress()
                setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                abandonPendingAutoTransitionIfStopped()
                if (resetResults) onMainDispatcher { SagerNet.stopService() }
                return false
            }

            if (resetResults) {
                resetAutoSearchState(cancelScan = false)
                clearAutoQuarantineState()
                resetAutoCandidateResults(groupIds)
            }

            val excludedIds = automaticExcludedIds(
                safeMode,
                listOf(excludedProfileId).filter { it > 0L },
            )

            if (!resetResults) {
                val reserveCandidate = reserveProfileIds(safeMode)
                    .asSequence()
                    .filter { it !in excludedIds }
                    .mapNotNull { SagerDatabase.proxyDao.getById(it) }
                    .filter { profileAllowed(it, safeMode) && !isMarkedFailed(it.id) }
                    .minWithOrNull(candidateComparator())
                if (reserveCandidate != null) {
                    DiagnosticLog.critical(
                        "auto-vpn",
                        "detached-search-precheck-reserve reason=$reason " +
                                ProfileTestRunner.profileDiagnosticSummary(reserveCandidate)
                    )
                    if (switchToVerified(
                            initial = CandidateResult(reserveCandidate, reserveCandidate.ping),
                            mode = safeMode,
                            excludedIds = excludedIds,
                            forceTest = true,
                            startIfStopped = true,
                            feedback = true,
                            recordManualCycle = false,
                        )
                    ) {
                        setAutoReadyStatus(safeMode)
                        return true
                    }
                }
            }

            val total = SagerDatabase.proxyDao.countByGroups(groupIds)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (resetResults) {
                resetAutoProgress(total)
            } else {
                beginOnDemandSearchProgress(safeMode, total)
            }
            setAutoStatus(safeMode, R.string.auto_vpn_status_initial_search)
            val working = if (resetResults) {
                scanInitialWorkingProfiles(
                    groupIds = groupIds,
                    excludedIds = excludedIds,
                    mode = safeMode,
                    target = reserveTarget(),
                    countProgress = true,
                )
            } else {
                scanWorkingProfiles(
                    groupIds = groupIds,
                    excludedIds = excludedIds,
                    mode = safeMode,
                    target = reserveTarget(),
                    countProgress = true,
                    connectFirst = false,
                    startIfStopped = true,
                    feedback = true,
                )
            }
            val best = chooseBestCandidate(working)
            if (best == null) {
                setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                abandonPendingAutoTransitionIfStopped()
                if (resetResults) onMainDispatcher { SagerNet.stopService() }
                return false
            }

            return switchMutex.withLock {
                switchToVerified(
                    initial = best,
                    mode = safeMode,
                    excludedIds = excludedIds,
                    forceTest = true,
                    startIfStopped = true,
                    feedback = true,
                    recordManualCycle = false,
                ).also { switched ->
                    if (switched) setAutoReadyStatus(safeMode)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.error("auto-vpn", "detached-search-error reason=$reason mode=$safeMode", e)
            setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
            abandonPendingAutoTransitionIfStopped()
            if (resetResults) onMainDispatcher { SagerNet.stopService() }
            return false
        } finally {
            finishAutoProgress()
            DiagnosticLog.critical(
                "auto-vpn",
                "detached-search-finish reason=$reason mode=$safeMode progress=$autoFinished/$autoTotal " +
                        "working=$autoWorking started=${SagerNet.started}"
            )
        }
    }

    internal fun reportDetachedSearchStopTimeout(mode: Int, reason: String) {
        val safeMode = sanitizeMode(mode)
        DiagnosticLog.error(
            "auto-vpn",
            "detached-search-stop-timeout reason=$reason mode=$safeMode ${DiagnosticLog.stateSummary()}"
        )
        if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
            setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
            finishAutoProgress()
        }
        pendingAutoServiceTransition = false
        DataStore.autoVpnServiceStartRequested = false
        finishDirectSearchSession()
    }

    internal fun stopDetachedDirectSearch() {
        pendingAutoServiceTransition = false
        DataStore.autoVpnServiceStartRequested = false
        finishDirectSearchSession()
        finishAutoProgress()
        setAutoStatus(DataStore.autoVpnMode, R.string.auto_vpn_status_scan_stopped)
    }

    /*
     * Kept for older callers; the public flow above now starts from the VPN button.
     */
    private fun startLegacyAutoSelection() {
        runOnDefaultDispatcher {
            switchMutex.withLock {
                val safeMode = sanitizeMode(DataStore.autoVpnMode)
                DataStore.autoVpnMode = safeMode
                failedUntil.clear()
                manualCycleIds.getOrPut(safeMode) { LinkedHashSet() }.clear()
                val working = prepareWorkingPool(safeMode)
                if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode) return@withLock
                val best = chooseBestCandidate(working)
                if (best == null) {
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    return@withLock
                }
                switchToVerified(
                    initial = best,
                    mode = safeMode,
                    excludedIds = emptySet(),
                    forceTest = true,
                    startIfStopped = true,
                    feedback = true,
                    recordManualCycle = false,
                )
            }
        }
    }

    fun disable() {
        DataStore.autoVpnEnabled = false
        DataStore.autoVpnServiceStartRequested = false
        finishDirectSearchSession()
        failures = 0
        monitorJob?.cancel()
        monitorJob = null
        immediateVerifyJob?.cancel()
        immediateVerifyJob = null
        autoScanJob?.cancel()
        autoScanJob = null
        stopTrafficMonitoring()
        activeTrafficSampler = null
        FloatingSwitchButton.hide()
        clearAutoProgress()
        setAutoStatus(null)
    }

    fun onManualProfileSelected() {
        if (DataStore.autoVpnEnabled) {
            disable()
        }
    }

    fun shouldStartAutoSelectionInsteadOfService(): Boolean {
        return DataStore.autoVpnEnabled &&
                !pendingAutoServiceTransition &&
                !DataStore.autoVpnServiceStartRequested &&
                !isDirectSearchActive()
    }

    /**
     * The direct-search service runs in another process while VPN is deliberately
     * stopped. Keep its state in the shared database so a duplicate service command
     * cannot replace a scan that is already testing candidates.
     */
    fun beginDirectSearchSession(): Long {
        val token = maxOf(System.currentTimeMillis(), DataStore.autoVpnDirectSearchToken + 1L)
        DataStore.autoVpnDirectSearchToken = token
        DataStore.autoVpnDirectSearchHeartbeat = System.currentTimeMillis()
        return token
    }

    fun touchDirectSearchSession(token: Long) {
        if (token > 0L && DataStore.autoVpnDirectSearchToken == token) {
            DataStore.autoVpnDirectSearchHeartbeat = System.currentTimeMillis()
        }
    }

    fun finishDirectSearchSession(token: Long = 0L) {
        if (token > 0L && DataStore.autoVpnDirectSearchToken != token) return
        DataStore.autoVpnDirectSearchToken = 0L
        DataStore.autoVpnDirectSearchHeartbeat = 0L
    }

    fun isDirectSearchActive(): Boolean {
        val token = DataStore.autoVpnDirectSearchToken
        if (token <= 0L) return false
        val heartbeat = DataStore.autoVpnDirectSearchHeartbeat
        if (heartbeat > 0L && System.currentTimeMillis() - heartbeat <= DIRECT_SEARCH_STALE_MS) {
            return true
        }
        DiagnosticLog.warn(
            "auto-vpn",
            "direct-search-session-stale token=$token heartbeatAge=${System.currentTimeMillis() - heartbeat}"
        )
        finishDirectSearchSession(token)
        return false
    }

    fun isServiceTransitionPending(): Boolean {
        return pendingAutoServiceTransition
    }

    fun onVpnStarted(
        urlTester: (() -> Int)? = null,
        trafficSampler: (suspend () -> TrafficStats?)? = null,
    ) {
        if (!DataStore.autoVpnEnabled) {
            FloatingSwitchButton.hide()
            return
        }
        activeUrlTester = urlTester
        activeTrafficSampler = trafficSampler
        DataStore.autoVpnServiceStartRequested = false
        FloatingSwitchButton.sync()
        startMonitor()
        startTrafficMonitor()
        if (pendingAutoServiceTransition) {
            pendingAutoServiceTransition = false
        }
        verifyCurrentSoon(urlTester)
    }

    fun onVpnStopped() {
        val autoRestarting = pendingAutoServiceTransition || DataStore.autoVpnServiceStartRequested
        failures = 0
        monitorJob?.cancel()
        monitorJob = null
        immediateVerifyJob?.cancel()
        immediateVerifyJob = null
        stopTrafficMonitoring()
        activeUrlTester = null
        activeTrafficSampler = null
        FloatingSwitchButton.hide()
        if (!autoRestarting) {
            resetAutoSearchState(cancelScan = true)
            setAutoStatus(null)
        }
    }

    fun onServiceFailed(message: String?) {
        if (!DataStore.autoVpnEnabled || message == null) return
        val failedProfiles = currentProfileIds()
        for (failedProfile in failedProfiles) {
            markFailed(failedProfile)
        }
        val safeMode = sanitizeMode(DataStore.autoVpnMode)
        if (!isVpnRunningForAuto() && AutoVpnDirectSearchService.startCandidateSearch(
                context = app,
                mode = safeMode,
                profileId = failedProfiles.firstOrNull() ?: 0L,
                reason = "service-failed",
            )
        ) {
            setAutoStatus(safeMode, R.string.auto_vpn_status_selecting)
            return
        }
        switchToBestProfile(
            mode = safeMode,
            excludedIds = failedProfiles.toSet(),
            forceTest = true,
            startIfStopped = true,
            ignoreSwitchCooldown = true,
            feedback = true,
        )
    }

    fun switchNowFromNotification() {
        setAutoStatus(DataStore.autoVpnMode, R.string.auto_vpn_status_selecting)
        switchToNextProfile(feedback = true)
    }

    fun switchNowFromApp() {
        if (SagerNet.started || DataStore.startedProfile > 0L) {
            app.sendBroadcast(Intent(Action.AUTO_SWITCH).setPackage(app.packageName))
            return
        }
        setAutoStatus(DataStore.autoVpnMode, R.string.auto_vpn_status_selecting)
        switchToNextProfile(feedback = true)
    }

    fun switchGeminiSoftFromNotification() {
        setAutoStatus(MODE_GEMINI, R.string.auto_vpn_status_selecting)
        switchGeminiSoft(feedback = true)
    }

    fun switchFromFloatingButton() {
        if (isGeminiModeActive()) {
            switchGeminiSoftFromNotification()
        } else {
            switchNowFromNotification()
        }
    }

    fun switchGeminiSoftFromApp() {
        if (SagerNet.started || DataStore.startedProfile > 0L) {
            app.sendBroadcast(Intent(Action.AUTO_SWITCH_GEMINI_SOFT).setPackage(app.packageName))
            return
        }
        setAutoStatus(MODE_GEMINI, R.string.auto_vpn_status_selecting)
        switchGeminiSoft(feedback = true)
    }

    fun switchGeminiAndRememberFromApp() {
        // Save before the VPN-process command: a switch stops that process and the
        // next direct scan deliberately starts in a separate process.
        rememberGeminiRejectedProfiles(currentProfileIds())
        switchNowFromApp()
    }

    fun stopScanFromApp() {
        AutoVpnDirectSearchService.stop(app)
        if (SagerNet.started || DataStore.startedProfile > 0L || DataStore.autoVpnProgressRunning) {
            app.sendBroadcast(Intent(Action.AUTO_SCAN_STOP).setPackage(app.packageName))
            return
        }
        stopScan(feedback = true)
    }

    fun stopScanFromService() {
        stopScan(feedback = true)
    }

    fun resumeScanFromApp() {
        if (SagerNet.started || DataStore.startedProfile > 0L) {
            app.sendBroadcast(Intent(Action.AUTO_SCAN_RESUME).setPackage(app.packageName))
            return
        }
        startSelectedModeFromVpnButton()
    }

    fun resumeScanFromService() {
        if (!DataStore.autoVpnEnabled) return
        if (!SagerNet.started && DataStore.startedProfile <= 0L) {
            startSelectedModeFromVpnButton()
        } else {
            setAutoReadyStatus()
        }
    }

    fun onSubscriptionsUpdated() {
        if (!DataStore.autoVpnEnabled || !SagerNet.started) return
        verifyCurrentAndSwitchIfBad(countFailure = true, ignoreSwitchCooldown = false, feedback = false)
    }

    private fun switchToNextProfile(feedback: Boolean) {
        DataStore.autoVpnEnabled = true
        DataStore.autoVpnMode = sanitizeMode(DataStore.autoVpnMode)
        runOnDefaultDispatcher {
            switchMutex.withLock {
                if (!DataStore.autoVpnEnabled) return@withLock
                val mode = sanitizeMode(DataStore.autoVpnMode)
                val currentIds = currentProfileIds()
                val visited = manualCycleIds.getOrPut(mode) { LinkedHashSet() }
                visited += currentIds
                currentIds.forEach { markManualSkipped(it, mode) }
                if (mode == MODE_GEMINI) {
                    rememberGeminiRejectedProfiles(currentIds)
                }

                val excludedIds = automaticExcludedIds(mode, currentIds + visited)
                val best = findBestCandidate(excludedIds, forceTest = true, mode = mode)
                if (best == null) {
                    setAutoStatus(mode, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                    return@withLock
                }

                switchToVerified(
                        initial = best,
                        mode = mode,
                        excludedIds = excludedIds,
                        forceTest = true,
                        startIfStopped = true,
                        feedback = feedback,
                        recordManualCycle = true,
                    )
            }
        }
    }

    private fun switchGeminiSoft(feedback: Boolean) {
        DataStore.autoVpnEnabled = true
        DataStore.autoVpnMode = MODE_GEMINI
        runOnDefaultDispatcher {
            switchMutex.withLock {
                if (!DataStore.autoVpnEnabled) return@withLock
                DataStore.autoVpnMode = MODE_GEMINI
                val currentIds = currentProfileIds()
                val best = findBestCandidate(
                    excludedIds = automaticExcludedIds(MODE_GEMINI, currentIds),
                    forceTest = true,
                    mode = MODE_GEMINI,
                )
                if (best == null) {
                    setAutoStatus(MODE_GEMINI, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                    return@withLock
                }
                if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != MODE_GEMINI) return@withLock

                switchToVerified(
                        initial = best,
                        mode = MODE_GEMINI,
                        excludedIds = automaticExcludedIds(MODE_GEMINI, currentIds),
                        forceTest = true,
                        startIfStopped = true,
                        feedback = feedback,
                        recordManualCycle = false,
                    )
            }
        }
    }

    private fun switchToBestProfile(
        mode: Int,
        excludedIds: Set<Long>,
        forceTest: Boolean,
        startIfStopped: Boolean,
        ignoreSwitchCooldown: Boolean,
        feedback: Boolean,
    ) {
        runOnDefaultDispatcher {
            switchMutex.withLock {
                if (!DataStore.autoVpnEnabled) return@withLock
                val safeMode = sanitizeMode(mode)
                DataStore.autoVpnMode = safeMode
                val now = System.currentTimeMillis()
                if (!ignoreSwitchCooldown && now - lastSwitchAt < switchCooldownMillis()) {
                    return@withLock
                }

                val effectiveExcluded = automaticExcludedIds(safeMode, excludedIds)
                val best = findBestCandidate(effectiveExcluded, forceTest, safeMode)
                if (best == null) {
                    setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                    abandonPendingAutoTransitionIfStopped()
                    return@withLock
                }
                if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode) return@withLock

                switchToVerified(
                        initial = best,
                        mode = safeMode,
                        excludedIds = effectiveExcluded,
                        forceTest = true,
                        startIfStopped = startIfStopped,
                        feedback = feedback,
                        recordManualCycle = false,
                    )
            }
        }
    }

    private suspend fun prepareWorkingPool(mode: Int): List<CandidateResult> {
        val groupIds = enabledGroupIds()
        if (groupIds.isEmpty()) return emptyList()

        resetAutoCandidateResults(groupIds)
        resetAutoProgress(SagerDatabase.proxyDao.countByGroups(groupIds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val working = scanWorkingProfiles(
            groupIds = groupIds,
            excludedIds = emptySet(),
            mode = mode,
            target = reserveTarget(),
            countProgress = true,
            connectFirst = false,
            startIfStopped = false,
            feedback = false,
            scanAllAvailable = true,
        )
        finishAutoProgress()
        return working
    }

    private suspend fun scanInitialWorkingProfiles(
        groupIds: List<Long>,
        excludedIds: Set<Long>,
        mode: Int,
        target: Int,
        countProgress: Boolean,
    ): List<CandidateResult> {
        val safeMode = sanitizeMode(mode)
        val parallel = DataStore.connectionTestParallel.coerceAtLeast(1)
        val throttle = autoTestThrottle()
        val working = ArrayList<CandidateResult>()
        var windowStartedAt = System.currentTimeMillis()
        setAutoStatus(safeMode, R.string.auto_vpn_status_initial_search)

        while (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode && working.size < target) {
            if (System.currentTimeMillis() - windowStartedAt >= initialSelectionWindowMillis()) {
                if (working.isNotEmpty()) {
                    break
                }
                windowStartedAt = System.currentTimeMillis()
            }

            // Keep one stable ordering for the whole round. The delay sort changes as tests
            // complete, so OFFSET paging could otherwise make a round appear to restart early.
            val snapshot = autoScanProfiles(groupIds)
            if (snapshot.isEmpty()) break
            var testedAnyInCycle = false
            var keepScanning = true

            pageLoop@ for (page in snapshot.chunked(AUTO_SCAN_PAGE_SIZE)) {
                if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode || working.size >= target) {
                    keepScanning = false
                    break@pageLoop
                }
                if (System.currentTimeMillis() - windowStartedAt >= initialSelectionWindowMillis()) {
                    if (working.isNotEmpty()) {
                        keepScanning = false
                        break@pageLoop
                    }
                    windowStartedAt = System.currentTimeMillis()
                }

                val dynamicExcluded = excludedIds + currentProfileIds() + working.map { it.profile.id }
                val candidates = page.asSequence()
                    .filter { it.id !in dynamicExcluded }
                    .filter { profileAllowed(it, safeMode) }
                    .filterNot { isMarkedFailed(it.id) }
                    .toList()
                if (countProgress) recordAutoSkippedResults(page.size - candidates.size)

                for (chunk in candidates.chunked(parallel)) {
                    if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode || working.size >= target) {
                        keepScanning = false
                        break@pageLoop
                    }
                    val testChunk = chunk.filter { markScanned(safeMode, it.id) }
                    if (countProgress) recordAutoSkippedResults(chunk.size - testChunk.size)
                    if (testChunk.isEmpty()) continue
                    testedAnyInCycle = true
                    val tested = sortCandidatesByPing(
                        testCandidates(
                            testChunk,
                            safeMode,
                            countProgress,
                            throttle,
                            onWorkingResult = { rememberWorkingResults(safeMode, listOf(it)) },
                            stopAfterWorking = (target - working.size).coerceAtLeast(1),
                        )
                    )
                    if (tested.isEmpty()) continue

                    rememberWorkingResults(safeMode, tested)
                    working += tested
                    setAutoStatus(safeMode, R.string.auto_vpn_status_initial_found, working.size, target)
                }
            }

            if (!keepScanning || working.size >= target) break
            if (!restartAutoScanCycle(safeMode, snapshot.size, testedAnyInCycle)) break
            if (working.isEmpty()) windowStartedAt = System.currentTimeMillis()
        }

        persistScannedIdsIfNeeded(safeMode, force = true)
        return sortCandidatesByPing(working)
    }

    private suspend fun scanWorkingProfiles(
        groupIds: List<Long>,
        excludedIds: Set<Long>,
        mode: Int,
        target: Int,
        countProgress: Boolean,
        connectFirst: Boolean,
        startIfStopped: Boolean,
        feedback: Boolean,
        scanAllAvailable: Boolean = false,
    ): List<CandidateResult> {
        val safeMode = sanitizeMode(mode)
        val parallel = DataStore.connectionTestParallel.coerceAtLeast(1)
        val throttle = autoTestThrottle()
        val working = ArrayList<CandidateResult>()
        var connected = false
        setAutoStatus(safeMode, R.string.auto_vpn_status_testing_more)

        while (DataStore.autoVpnEnabled &&
            DataStore.autoVpnMode == safeMode &&
            (scanAllAvailable || reserveCount(safeMode) < target)
        ) {
            val snapshot = autoScanProfiles(groupIds)
            if (snapshot.isEmpty()) break
            var testedAnyInCycle = false
            var keepScanning = true

            pageLoop@ for (page in snapshot.chunked(AUTO_SCAN_PAGE_SIZE)) {
                if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode ||
                    (!scanAllAvailable && reserveCount(safeMode) >= target)
                ) {
                    keepScanning = false
                    break@pageLoop
                }

                val dynamicExcluded = excludedIds + currentProfileIds() + reserveProfileIds(safeMode)
                val candidates = page.asSequence()
                    .filter { it.id !in dynamicExcluded }
                    .filter { profileAllowed(it, safeMode) }
                    .filterNot { isMarkedFailed(it.id) }
                    .toList()
                if (countProgress) recordAutoSkippedResults(page.size - candidates.size)

                for (chunk in candidates.chunked(parallel)) {
                    if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode ||
                        (!scanAllAvailable && reserveCount(safeMode) >= target)
                    ) {
                        keepScanning = false
                        break@pageLoop
                    }
                    val testChunk = chunk.filter { markScanned(safeMode, it.id) }
                    if (countProgress) recordAutoSkippedResults(chunk.size - testChunk.size)
                    if (testChunk.isEmpty()) continue
                    testedAnyInCycle = true
                    val tested = sortCandidatesByPing(
                        testCandidates(
                            testChunk,
                            safeMode,
                            countProgress,
                            throttle,
                            onWorkingResult = { rememberWorkingResults(safeMode, listOf(it)) },
                        )
                    )
                    if (tested.isEmpty()) continue

                    rememberWorkingResults(safeMode, tested)
                    working += tested

                    if (connectFirst && !connected) {
                        val best = chooseBestCandidate(working) ?: tested.first()
                        switchMutex.withLock {
                            if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
                                connected = switchToVerified(
                                    initial = best,
                                    mode = safeMode,
                                    excludedIds = dynamicExcluded,
                                    forceTest = true,
                                    startIfStopped = startIfStopped,
                                    feedback = feedback,
                                    recordManualCycle = false,
                                )
                            }
                        }
                        setAutoStatus(safeMode, R.string.auto_vpn_status_testing_more)
                    }
                }
            }

            if (!keepScanning || (!scanAllAvailable && reserveCount(safeMode) >= target)) break
            if (!restartAutoScanCycle(safeMode, snapshot.size, testedAnyInCycle)) break
        }

        persistScannedIdsIfNeeded(safeMode, force = true)
        return sortCandidatesByPing(working)
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        lastMonitorTickAt = System.currentTimeMillis()
        DiagnosticLog.event(
            "auto-vpn",
            "active-monitor-start interval=${checkIntervalMillis()} profile=${DataStore.startedProfile}"
        )
        monitorJob = runOnDefaultDispatcher {
            try {
                while (isActive && DataStore.autoVpnEnabled) {
                    delay(checkIntervalMillis())
                    lastMonitorTickAt = System.currentTimeMillis()
                    if (!DataStore.autoVpnEnabled) break
                    if (!SagerNet.started || DataStore.startedProfile <= 0L) continue
                    try {
                        checkActiveProfileOnSchedule()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticLog.error("auto-vpn", "active-monitor-tick-failed", e)
                        Logs.w(e)
                    }
                }
            } finally {
                DiagnosticLog.event(
                    "auto-vpn",
                    "active-monitor-stop active=$isActive enabled=${DataStore.autoVpnEnabled} " +
                            "started=${SagerNet.started} profile=${DataStore.startedProfile}"
                )
            }
        }
    }

    /**
     * Watches only for actual outbound VPN use without a matching response. No
     * traffic is never an error: the detector merely accelerates diagnosis while
     * an application is actively trying to use the tunnel.
     */
    private fun startTrafficMonitor() {
        if (trafficMonitorJob?.isActive == true) return
        trafficMonitorJob = runOnDefaultDispatcher {
            var previous: TrafficStats? = null
            var previousProfileId = 0L
            var suspiciousIntervals = 0

            while (isActive && DataStore.autoVpnEnabled) {
                delay(TRAFFIC_SAMPLE_INTERVAL_MS)
                if (!DataStore.autoVpnEnabled || !SagerNet.started || DataStore.startedProfile <= 0L) {
                    previous = null
                    previousProfileId = 0L
                    suspiciousIntervals = 0
                    continue
                }

                val profileId = DataStore.startedProfile
                val sample = runCatching { activeTrafficSampler?.invoke() }.getOrNull()
                if (sample == null) {
                    previous = null
                    previousProfileId = 0L
                    suspiciousIntervals = 0
                    continue
                }

                val old = previous
                if (old == null || previousProfileId != profileId) {
                    previous = sample
                    previousProfileId = profileId
                    suspiciousIntervals = 0
                    continue
                }

                val outgoing = (sample.txTotal - old.txTotal).coerceAtLeast(0L)
                val incoming = (sample.rxTotal - old.rxTotal).coerceAtLeast(0L)
                previous = sample

                if (outgoing >= TRAFFIC_OUTGOING_MIN_BYTES && incoming <= TRAFFIC_INCOMING_MAX_BYTES) {
                    suspiciousIntervals += 1
                } else {
                    suspiciousIntervals = 0
                }

                if (suspiciousIntervals >= TRAFFIC_SUSPICIOUS_INTERVALS) {
                    suspiciousIntervals = 0
                    startTrafficDiagnostic(profileId, sanitizeMode(DataStore.autoVpnMode))
                }
            }
        }
    }

    private fun stopTrafficMonitoring() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        trafficDiagnosticJob?.cancel()
        trafficDiagnosticJob = null
        directNetworkRetryJob?.cancel()
        directNetworkRetryJob = null
        lastTrafficDiagnosticAt = 0L
    }

    private fun startTrafficDiagnostic(profileId: Long, mode: Int) {
        if (trafficDiagnosticJob?.isActive == true || directNetworkRetryJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastTrafficDiagnosticAt < TRAFFIC_DIAGNOSTIC_COOLDOWN_MS) return
        lastTrafficDiagnosticAt = now
        trafficDiagnosticJob = runOnDefaultDispatcher {
            try {
                diagnoseTrafficSignal(profileId, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.error("auto-vpn", "traffic-diagnostic-failed profile=$profileId mode=$mode", e)
            }
        }
    }

    private suspend fun diagnoseTrafficSignal(profileId: Long, mode: Int) {
        val safeMode = sanitizeMode(mode)
        if (!isSameStartedAutoProfile(profileId, safeMode)) return

        DiagnosticLog.event("auto-vpn", "traffic-signal profile=$profileId mode=$safeMode")
        val first = checkActiveProfile()
        if (first.exitCountryRejected && isSameStartedAutoProfile(profileId, safeMode)) {
            confirmActiveProfileFailure(profileId, safeMode, "exit-country-rejected")
            return
        }
        if (!first.bad || !isSameStartedAutoProfile(profileId, safeMode)) {
            failures = 0
            return
        }

        if (!hasDirectInternet()) {
            deferForMissingDirectNetwork(profileId, safeMode, trafficTriggered = true)
            return
        }

        setActiveFailureStatus(safeMode, first.ping, 1, 2)
        delay(TRAFFIC_CONFIRM_DELAY_MS)
        if (!isSameStartedAutoProfile(profileId, safeMode)) return

        val second = checkActiveProfile()
        if (second.exitCountryRejected && isSameStartedAutoProfile(profileId, safeMode)) {
            confirmActiveProfileFailure(profileId, safeMode, "exit-country-rejected")
            return
        }
        if (!second.bad || !isSameStartedAutoProfile(profileId, safeMode)) {
            failures = 0
            return
        }

        setActiveFailureStatus(safeMode, second.ping, 2, 2)
        confirmActiveProfileFailure(profileId, safeMode, "traffic-confirmed")
    }

    private suspend fun checkActiveProfileOnSchedule() {
        val profileId = DataStore.startedProfile
        val mode = sanitizeMode(DataStore.autoVpnMode)
        if (!isSameStartedAutoProfile(profileId, mode)) return

        val activeCheck = checkActiveProfile()
        if (activeCheck.exitCountryRejected && isSameStartedAutoProfile(profileId, mode)) {
            confirmActiveProfileFailure(profileId, mode, "exit-country-rejected")
            return
        }
        if (!activeCheck.bad || !isSameStartedAutoProfile(profileId, mode)) {
            failures = 0
            return
        }

        if (!hasDirectInternet()) {
            deferForMissingDirectNetwork(profileId, mode, trafficTriggered = false)
            return
        }

        failures = (failures + 1).coerceAtMost(failuresBeforeSwitch())
        setActiveFailureStatus(mode, activeCheck.ping, failures, failuresBeforeSwitch())
        if (failures >= failuresBeforeSwitch()) {
            failures = 0
            confirmActiveProfileFailure(profileId, mode, "scheduled-confirmed")
        }
    }

    private fun deferForMissingDirectNetwork(profileId: Long, mode: Int, trafficTriggered: Boolean) {
        if (!isSameStartedAutoProfile(profileId, mode)) return
        failures = 0
        setAutoStatus(
            mode,
            R.string.auto_vpn_status_no_direct_network_retry,
            (DIRECT_NETWORK_RETRY_DELAY_MS / 1000L).toInt(),
        )
        DiagnosticLog.event(
            "auto-vpn",
            "direct-network-unavailable profile=$profileId mode=$mode trafficTriggered=$trafficTriggered",
        )
        if (directNetworkRetryJob?.isActive == true) return
        directNetworkRetryJob = runOnDefaultDispatcher {
            while (isActive && isSameStartedAutoProfile(profileId, mode)) {
                delay(DIRECT_NETWORK_RETRY_DELAY_MS)
                if (!isSameStartedAutoProfile(profileId, mode)) break
                if (!hasDirectInternet()) {
                    setAutoStatus(
                        mode,
                        R.string.auto_vpn_status_no_direct_network_retry,
                        (DIRECT_NETWORK_RETRY_DELAY_MS / 1000L).toInt(),
                    )
                    continue
                }
                if (trafficTriggered) {
                    diagnoseTrafficSignal(profileId, mode)
                } else {
                    checkActiveProfileOnSchedule()
                }
                break
            }
        }
    }

    private suspend fun confirmActiveProfileFailure(profileId: Long, mode: Int, reason: String) {
        if (!isSameStartedAutoProfile(profileId, mode)) return
        SagerDatabase.proxyDao.getById(profileId)?.let { profile ->
            recordProfileFailure(profile, quarantine = true)
        }
        failures = 0
        DiagnosticLog.critical("auto-vpn", "active-failure-confirmed reason=$reason profile=$profileId mode=$mode")
        switchToBestProfile(
            mode = mode,
            excludedIds = setOf(profileId),
            forceTest = false,
            startIfStopped = false,
            ignoreSwitchCooldown = true,
            feedback = false,
        )
    }

    /**
     * Uses the non-VPN default network selected by DefaultNetworkListener. Binding
     * the HttpURLConnection to that Network keeps this probe outside the tunnel.
     */
    private suspend fun hasDirectInternet(): Boolean {
        return withTimeoutOrNull(DIRECT_NETWORK_CHECK_TIMEOUT_MS) {
            val network = DefaultNetworkListener.get()
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
                ?: return@withTimeoutOrNull false
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@withTimeoutOrNull false
            }

            onIoDispatcher {
                val connection = network.openConnection(URL(DIRECT_NETWORK_CHECK_URL)) as? HttpURLConnection
                    ?: return@onIoDispatcher false
                try {
                    connection.instanceFollowRedirects = true
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = DIRECT_NETWORK_CONNECT_TIMEOUT_MS
                    connection.readTimeout = DIRECT_NETWORK_READ_TIMEOUT_MS
                    connection.responseCode in 200..399
                } finally {
                    connection.disconnect()
                }
            }
        } == true
    }

    private fun verifyCurrentSoon(urlTester: (() -> Int)?) {
        immediateVerifyJob?.cancel()
        val profileId = DataStore.startedProfile
        val mode = sanitizeMode(DataStore.autoVpnMode)
        immediateVerifyJob = runOnDefaultDispatcher {
            if (profileId <= 0L || urlTester == null) return@runOnDefaultDispatcher
            var connectFailures = 0
            for ((index, delayMs) in POST_CONNECT_VERIFY_DELAYS_MS.withIndex()) {
                delay(delayMs)
                if (!isSameStartedAutoProfile(profileId, mode)) return@runOnDefaultDispatcher
                setAutoNotificationStatus(mode, R.string.auto_vpn_status_verifying_connected, index + 1, POST_CONNECT_VERIFY_DELAYS_MS.size)
                val ping = testStartedConnection(profileId, mode, urlTester)
                if (ping != null && ping <= badPingMillis()) {
                    failures = 0
                    setAutoReadyStatus(mode)
                    return@runOnDefaultDispatcher
                }
                if (ping != null) {
                    setActiveFailureStatus(mode, ping, index + 1, POST_CONNECT_VERIFY_DELAYS_MS.size)
                } else {
                    setActiveFailureStatus(mode, null, index + 1, POST_CONNECT_VERIFY_DELAYS_MS.size)
                }
                connectFailures += 1
            }

            if (connectFailures >= POST_CONNECT_VERIFY_DELAYS_MS.size) {
                switchAfterPostConnectFailures(profileId, mode)
            }
        }
    }

    private fun isSameStartedAutoProfile(profileId: Long, mode: Int): Boolean {
        return DataStore.autoVpnEnabled &&
                DataStore.autoVpnMode == sanitizeMode(mode) &&
                SagerNet.started &&
                DataStore.startedProfile == profileId
    }

    private suspend fun checkActiveProfile(): ActiveCheckResult {
        val profileId = DataStore.startedProfile
        val mode = sanitizeMode(DataStore.autoVpnMode)
        val urlTester = activeUrlTester ?: return ActiveCheckResult(checkCurrentProfileBad())
        if (profileId <= 0L || !isSameStartedAutoProfile(profileId, mode)) return ActiveCheckResult(false)
        if (requiresExitCountryCheck(mode)) {
            val profile = SagerDatabase.proxyDao.getById(profileId) ?: return ActiveCheckResult(true)
            if (hasExcludedGlobeMarker(profile)) return ActiveCheckResult(true, exitCountryRejected = true)
            val countryCode = ExitIpGeoChecker.countryCode()
            DiagnosticLog.event(
                "auto-vpn",
                "active-exit-country mode=$mode profile=$profileId country=${countryCode ?: "unknown"}",
            )
            if (countryCode == null || countryCode == "RU") {
                return ActiveCheckResult(true, exitCountryRejected = true)
            }
        }

        setAutoStatus(mode, R.string.auto_vpn_status_checking_active)
        val ping = testStartedConnection(profileId, mode, urlTester, recordFailure = false)
        if (ping == null) {
            return ActiveCheckResult(true)
        }
        if (ping > badPingMillis()) {
            return ActiveCheckResult(true, ping)
        }
        setAutoReadyStatus(mode)
        return ActiveCheckResult(false)
    }

    private suspend fun testStartedConnection(
        profileId: Long,
        mode: Int,
        urlTester: () -> Int,
        recordFailure: Boolean = true,
    ): Int? {
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return null
        return try {
            val ping = urlTester()
            recordLastPing(ping)
            profile.ping = ping
            profile.status = 1
            profile.error = null
            saveProfileTestResultSafely(profile, "active-success")
            if (ping > badPingMillis()) {
                recordProfileFailure(profile, quarantine = false)
            }
            rememberWorkingResults(mode, listOf(CandidateResult(profile, ping)))
            ping
        } catch (e: Exception) {
            Logs.w(e)
            recordLastPing(null)
            profile.status = 3
            profile.error = e.readableMessage
            saveProfileTestResultSafely(profile, "active-failed")
            if (recordFailure) {
                recordProfileFailure(profile, quarantine = true)
            }
            null
        }
    }

    private suspend fun switchAfterPostConnectFailures(profileId: Long, mode: Int) {
        switchMutex.withLock {
            val safeMode = sanitizeMode(mode)
            if (!isSameStartedAutoProfile(profileId, safeMode)) return@withLock
            markFailed(profileId, countFailure = false)
            failures = 0
            setAutoStatus(safeMode, R.string.auto_vpn_status_selecting)
            val best = findBestCandidate(
                excludedIds = automaticExcludedIds(safeMode, setOf(profileId)),
                forceTest = false,
                mode = safeMode,
            )
            if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode || best == null) {
                setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
                abandonPendingAutoTransitionIfStopped()
                return@withLock
            }
            switchToVerified(
                    initial = best,
                    mode = safeMode,
                    excludedIds = automaticExcludedIds(safeMode, setOf(profileId)),
                    forceTest = true,
                    startIfStopped = false,
                    feedback = false,
                    recordManualCycle = false,
                )
        }
    }

    private fun verifyCurrentAndSwitchIfBad(
        countFailure: Boolean,
        ignoreSwitchCooldown: Boolean,
        feedback: Boolean,
    ) {
        runOnDefaultDispatcher {
            verifyCurrentAndSwitchIfBadLocked(countFailure, ignoreSwitchCooldown, feedback)
        }
    }

    private suspend fun verifyCurrentAndSwitchIfBadLocked(
        countFailure: Boolean,
        ignoreSwitchCooldown: Boolean,
        feedback: Boolean,
    ) {
        switchMutex.withLock {
            if (!DataStore.autoVpnEnabled || !SagerNet.started || DataStore.startedProfile <= 0L) return@withLock
            val badCurrent = checkCurrentProfileBad()
            if (!badCurrent) {
                failures = 0
                return@withLock
            }

            if (countFailure) {
                failures++
                val mode = sanitizeMode(DataStore.autoVpnMode)
                setActiveFailureStatus(mode, null, failures, failuresBeforeSwitch())
                if (failures < failuresBeforeSwitch()) return@withLock
            }
            failures = 0

            val now = System.currentTimeMillis()
            if (!ignoreSwitchCooldown && now - lastSwitchAt < switchCooldownMillis()) {
                return@withLock
            }

            val mode = sanitizeMode(DataStore.autoVpnMode)
            val best = findBestCandidate(automaticExcludedIds(mode, currentProfileIds()), forceTest = true, mode = mode)
            if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != mode || best == null) {
                setAutoStatus(mode, R.string.auto_vpn_status_no_candidate)
                abandonPendingAutoTransitionIfStopped()
                return@withLock
            }
            switchToVerified(
                    initial = best,
                    mode = mode,
                    excludedIds = automaticExcludedIds(mode, currentProfileIds()),
                    forceTest = true,
                    startIfStopped = false,
                    feedback = feedback,
                    recordManualCycle = false,
                )
        }
    }

    private suspend fun checkCurrentProfileBad(): Boolean {
        val current = SagerDatabase.proxyDao.getById(DataStore.startedProfile) ?: return true
        val mode = sanitizeMode(DataStore.autoVpnMode)
        if (!profileAllowed(current, mode)) {
            markFailed(current.id)
            return true
        }

        repeat(CURRENT_VERIFY_ATTEMPTS) { attempt ->
            val result = testProfile(current, mode, markFailure = false)
            if (result != null && result.ping <= badPingMillis()) {
                rememberWorkingResults(mode, listOf(result))
                setAutoReadyStatus(mode)
                return false
            }
            if (attempt < CURRENT_VERIFY_ATTEMPTS - 1) {
                delay(CURRENT_VERIFY_RETRY_DELAY_MS)
            }
        }

        markFailed(current.id, countFailure = false)
        return true
    }

    private suspend fun findBestCandidate(
        excludedIds: Set<Long>,
        forceTest: Boolean,
        mode: Int,
    ): CandidateResult? {
        val safeMode = sanitizeMode(mode)
        if (!pauseVpnForDirectTests("candidate-search")) return null
        val groupIds = enabledGroupIds()
        if (groupIds.isEmpty()) return null

        val poolCandidates = reserveProfileIds(safeMode)
            .mapNotNull { SagerDatabase.proxyDao.getById(it) }
            .filter { it.id !in excludedIds && profileAllowed(it, safeMode) && !isMarkedFailed(it.id) }

        val cachedPool = poolCandidates.mapNotNull(::cachedWorkingResult)
        if (cachedPool.isNotEmpty()) {
            return chooseBestCandidate(cachedPool)
        }

        if (!forceTest && poolCandidates.isNotEmpty()) {
            val first = poolCandidates.minWithOrNull(candidateComparator()) ?: poolCandidates.first()
            return CandidateResult(first, first.ping)
        }
        if (poolCandidates.isNotEmpty()) {
            // The caller will perform the direct pre-switch test. In a detached
            // process the in-memory cache is intentionally empty, so do not skip
            // a valid persisted reserve and rescan every profile unnecessarily.
            val first = poolCandidates.minWithOrNull(candidateComparator()) ?: poolCandidates.first()
            return CandidateResult(first, first.ping)
        }
        return findBestCandidateFromScan(groupIds, excludedIds, safeMode, countProgress = false)
    }

    private suspend fun findBestCandidateFromScan(
        groupIds: List<Long>,
        excludedIds: Set<Long>,
        mode: Int,
        countProgress: Boolean,
    ): CandidateResult? {
        val safeMode = sanitizeMode(mode)
        val snapshot = autoScanProfiles(groupIds)
        if (snapshot.isEmpty()) return null

        // Once the reserve is empty, rebuild it with the same target and timeout used
        // for the initial Auto start. This happens only after the VPN is paused, so no
        // candidate is measured through the active tunnel.
        val ownsProgress = !countProgress && beginOnDemandSearchProgress(safeMode, snapshot.size)
        val trackProgress = countProgress || ownsProgress
        setAutoStatus(safeMode, R.string.auto_vpn_status_testing_more)

        try {
            val working = scanWorkingProfiles(
                groupIds = groupIds,
                excludedIds = excludedIds,
                mode = safeMode,
                target = reserveTarget(),
                countProgress = trackProgress,
                connectFirst = false,
                startIfStopped = false,
                feedback = false,
            )
            return chooseBestCandidate(working)
        } finally {
            if (ownsProgress && autoScanJob?.isActive != true) {
                finishAutoProgress()
            }
        }
    }

    private fun chooseBestCandidate(results: List<CandidateResult>): CandidateResult? {
        if (results.isEmpty()) return null
        return results.minWithOrNull(candidateResultComparator())
    }

    private fun sortCandidatesByPing(results: List<CandidateResult>): List<CandidateResult> {
        if (results.isEmpty()) return emptyList()
        return results.sortedWith(candidateResultComparator())
    }

    private fun candidateResultComparator(): Comparator<CandidateResult> =
        compareBy<CandidateResult> { if (it.ping > 0) it.ping else Int.MAX_VALUE }
            .thenBy { it.profile.userOrder }
            .thenBy { it.profile.id }

    private fun candidateComparator(): Comparator<ProxyEntity> =
        compareBy<ProxyEntity> { if (it.ping > 0) it.ping else Int.MAX_VALUE }
            .thenBy { it.userOrder }
            .thenBy { it.id }

    private fun sortProfileIdsByPing(profileIds: Collection<Long>): List<Long> {
        if (profileIds.isEmpty()) return emptyList()
        return profileIds
            .mapNotNull { profileId ->
                val profile = SagerDatabase.proxyDao.getById(profileId) ?: return@mapNotNull null
                val ping = workingCache[profileId]?.ping ?: profile.ping
                Triple(profileId, ping, profile.userOrder)
            }
            .sortedWith(
                compareBy<Triple<Long, Int, Long>> { if (it.second > 0) it.second else Int.MAX_VALUE }
                    .thenBy { it.third }
                    .thenBy { it.first }
            )
            .map { it.first }
    }

    private fun selectionReason(profile: ProxyEntity): String {
        val ping = workingCache[profile.id]?.ping ?: profile.ping
        return app.getString(R.string.auto_vpn_selection_reason_ping, ping.coerceAtLeast(0))
    }

    private fun recordProfileFailure(profile: ProxyEntity, quarantine: Boolean) {
        if (quarantine) {
            failedUntil[profile.id] = System.currentTimeMillis() + quarantineMillis()
        }
    }

    private fun markManualSkipped(profileId: Long, mode: Int) {
        if (profileId <= 0L) return
        failedUntil[profileId] = System.currentTimeMillis() + quarantineMillis()
        if (sanitizeMode(mode) == MODE_GEMINI) {
            rememberGeminiRejectedProfiles(listOf(profileId))
        }
    }

    private fun rememberWorkingResults(mode: Int, results: List<CandidateResult>) {
        if (results.isEmpty()) return
        synchronized(reserveUpdateLock) {
            val safeMode = sanitizeMode(mode)
            val now = System.currentTimeMillis()
            results.forEach {
                workingCache[it.profile.id] = CachedWorking(it.ping, now)
            }
            val activeIds = currentProfileIds().toSet()
            val manualIds = manualCycleIds[safeMode].orEmpty()
            val existingIds = LinkedHashSet(reserveProfileIds(safeMode))
            sortCandidatesByPing(results).forEach {
                val id = it.profile.id
                if (id > 0L &&
                    id !in activeIds &&
                    id !in manualIds &&
                    !isMarkedFailed(id) &&
                    profileAllowed(it.profile, safeMode)
                ) {
                    existingIds += id
                }
            }
            synchronized(workingPools) {
                workingPools[safeMode] = sortProfileIdsByPing(existingIds)
            }
            updateAutoReserve(safeMode)
        }
    }

    private fun cachedWorkingResult(profile: ProxyEntity): CandidateResult? {
        val cached = workingCache[profile.id] ?: return null
        if (System.currentTimeMillis() - cached.at > RECENT_WORKING_TTL_MS) {
            workingCache.remove(profile.id)
            return null
        }
        return CandidateResult(profile, cached.ping)
    }

    private fun hasFreshWorkingCache(profileId: Long): Boolean {
        if (profileId <= 0L) return false
        val cached = workingCache[profileId] ?: return false
        if (System.currentTimeMillis() - cached.at <= RECENT_WORKING_TTL_MS) return true
        workingCache.remove(profileId)
        return false
    }

    private fun enabledGroupIds(): List<Long> {
        return SagerDatabase.groupDao.allGroups().filter {
            it.id > 0L &&
                    DataStore.isGroupEnabled(it.id) &&
                    (!it.ungrouped || SagerDatabase.proxyDao.countByGroup(it.id) > 0L)
        }.map { it.id }
    }

    private fun autoScanProfiles(groupIds: List<Long>): List<ProxyEntity> {
        return when (DataStore.allGroupOrder) {
            GroupOrder.BY_NAME -> SagerDatabase.proxyDao.getAutoScanProfilesByName(groupIds)
            GroupOrder.BY_DELAY -> SagerDatabase.proxyDao.getAutoScanProfilesByDelay(groupIds)
            else -> SagerDatabase.proxyDao.getAutoScanProfilesByOrigin(groupIds)
        }
    }

    private fun autoTestThrottle(): AutoTestThrottle {
        return AutoTestThrottle(
            quickFailureLimit = DataStore.connectionTestQuickFailureCount.coerceAtLeast(1),
            quickFailureMillis = DataStore.connectionTestQuickFailureMillis.coerceAtLeast(1),
            quickFailurePauseMillis = DataStore.connectionTestQuickFailurePauseSeconds.coerceAtLeast(1) * 1000L,
            scheduledBreakAfterCount = DataStore.connectionTestBreakAfterCount.coerceAtLeast(0),
            scheduledBreakMillis = DataStore.connectionTestBreakSeconds.coerceAtLeast(0) * 1000L,
        )
    }

    private suspend fun testCandidates(
        candidates: List<ProxyEntity>,
        mode: Int,
        countProgress: Boolean,
        throttle: AutoTestThrottle? = null,
        onWorkingResult: ((CandidateResult) -> Unit)? = null,
        stopAfterWorking: Int? = null,
    ): List<CandidateResult> {
        val parallel = DataStore.connectionTestParallel.coerceAtLeast(1)
        val semaphore = Semaphore(parallel)
        val throttleLock = Any()
        val outcomes = ConcurrentLinkedQueue<CandidateOutcome>()
        val workingLimit = stopAfterWorking?.coerceAtLeast(1)
        var workingCount = 0
        val workingLock = Any()
        val jobs = candidates.map { profile ->
            candidateTestScope.launch {
                semaphore.withPermit {
                    throttle?.waitForPause()
                    val outcome = testProfileOutcome(profile, mode)
                    // A native test can finish after its batch has already selected a
                    // candidate. Do not let that stale tail mutate live scan progress.
                    if (!isActive) return@withPermit
                    outcome.result?.let { result ->
                        synchronized(workingLock) {
                            workingCount += 1
                        }
                        onWorkingResult?.invoke(result)
                    }
                    if (throttle != null) {
                        synchronized(throttleLock) {
                            throttle.record(outcome)
                        }
                    }
                    if (countProgress) {
                        recordAutoTestResult(outcome.result != null)
                    }
                    outcomes += outcome
                }
            }
        }

        try {
            while (jobs.any { it.isActive }) {
                if (workingLimit != null && synchronized(workingLock) { workingCount >= workingLimit }) {
                    DiagnosticLog.critical(
                        "auto-vpn",
                        "candidate-batch-target-reached mode=$mode working=$workingCount target=$workingLimit " +
                                "batch=${candidates.size}"
                    )
                    val pending = jobs.count { it.isActive }
                    jobs.filter { it.isActive }.forEach { it.cancel() }
                    DiagnosticLog.critical(
                        "auto-vpn",
                        "candidate-batch-return-early mode=$mode completed=${outcomes.size} " +
                                "pendingCancelled=$pending batch=${candidates.size}"
                    )
                    break
                }
                delay(20L)
            }
        } catch (e: CancellationException) {
            val pending = jobs.count { it.isActive }
            jobs.filter { it.isActive }.forEach { it.cancel() }
            DiagnosticLog.critical(
                "auto-vpn",
                "candidate-batch-cancelled mode=$mode completed=${outcomes.size} " +
                        "pendingCancelled=$pending batch=${candidates.size}"
            )
            throw e
        }

        return outcomes.mapNotNull { it.result }
    }

    private suspend fun testProfile(
        profile: ProxyEntity,
        mode: Int,
        markFailure: Boolean = true,
        verifyExitCountry: Boolean = false,
    ): CandidateResult? = testProfileOutcome(profile, mode, markFailure, verifyExitCountry).result

    private suspend fun testProfileOutcome(
        profile: ProxyEntity,
        mode: Int,
        markFailure: Boolean = true,
        verifyExitCountry: Boolean = false,
    ): CandidateOutcome {
        val timeout = DataStore.connectionTestTimeoutSeconds.coerceAtLeast(1) * 1000
        val link = testUrlForMode(mode)
        val startedAt = System.currentTimeMillis()
        profile.status = 0
        profile.error = null
        DiagnosticLog.event(
            "auto-vpn",
            "candidate-test-start mode=$mode markFailure=$markFailure " +
                    ProfileTestRunner.profileDiagnosticSummary(profile)
        )

        return try {
            if (ProfileTestRunner.useBrowserForwarder(profile)) {
                DiagnosticLog.event(
                    "auto-vpn",
                    "candidate-test-skip-browser-forwarder mode=$mode " +
                            ProfileTestRunner.profileDiagnosticSummary(profile)
                )
                CandidateOutcome(null, System.currentTimeMillis() - startedAt, null)
            } else {
                val instance = V2RayTestInstance(
                    profile,
                    link,
                    timeout,
                    protectPath = protectPathForTests(),
                    enableHttpProxy = verifyExitCountry,
                )
                val ping = try {
                    val result = runCandidateUrlTest(instance, timeout)
                    if (verifyExitCountry && requiresExitCountryCheck(mode)) {
                        val countryCode = instance.exitIpCountryCode()
                        DiagnosticLog.event(
                            "auto-vpn",
                            "candidate-exit-country mode=$mode profile=${profile.id} " +
                                    "country=${countryCode ?: "unknown"}",
                        )
                        if (countryCode == null || countryCode == "RU") {
                            throw IOException(
                                if (countryCode == "RU") "Exit IP is in Russia" else "Exit IP country is unknown"
                            )
                        }
                    }
                    result
                } finally {
                    instance.close()
                }
                recordLastPing(ping)
                profile.ping = ping
                profile.status = 1
                saveProfileTestResultSafely(profile, "candidate-success")
                DiagnosticLog.event(
                    "auto-vpn",
                    "candidate-test-success mode=$mode elapsedMs=${System.currentTimeMillis() - startedAt} " +
                            ProfileTestRunner.profileDiagnosticSummary(profile)
                )
                CandidateOutcome(CandidateResult(profile, ping), System.currentTimeMillis() - startedAt, null)
            }
        } catch (e: CancellationException) {
            DiagnosticLog.event(
                "auto-vpn",
                "candidate-test-cancelled mode=$mode elapsedMs=${System.currentTimeMillis() - startedAt} " +
                        ProfileTestRunner.profileDiagnosticSummary(profile)
            )
            throw e
        } catch (e: Exception) {
            recordLastPing(null)
            profile.status = 3
            profile.error = e.readableMessage
            saveProfileTestResultSafely(profile, "candidate-failed")
            recordProfileFailure(profile, quarantine = markFailure)
            Logs.d("Auto VPN candidate failed: ${profile.displayName()} - ${e.readableMessage}")
            DiagnosticLog.event(
                "auto-vpn",
                "candidate-test-failed mode=$mode elapsedMs=${System.currentTimeMillis() - startedAt} " +
                        ProfileTestRunner.profileDiagnosticSummary(profile)
            )
            CandidateOutcome(null, System.currentTimeMillis() - startedAt, e)
        }
    }

    /**
     * Native URL tests occasionally ignore their own timeout. Run them outside the
     * scan coroutine so one frozen native call cannot hold the whole Gemini batch
     * (and therefore the candidate reserve) forever.
     */
    private suspend fun runCandidateUrlTest(instance: V2RayTestInstance, timeoutMillis: Int): Int {
        return onIoDispatcher {
            val task = candidateTestExecutor.submit<Int> {
                runBlocking { instance.doTest() }
            }
            try {
                task.get(
                    timeoutMillis.toLong().coerceAtLeast(1L) + CANDIDATE_TEST_HARD_TIMEOUT_GRACE_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (e: TimeoutException) {
                task.cancel(true)
                throw SocketTimeoutException(
                    "Candidate URL test exceeded ${timeoutMillis + CANDIDATE_TEST_HARD_TIMEOUT_GRACE_MS}ms"
                )
            } catch (e: InterruptedException) {
                task.cancel(true)
                throw CancellationException("Candidate URL test interrupted").also { it.initCause(e) }
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is CancellationException) throw cause
                if (cause is Exception) throw cause
                throw e
            }
        }
    }

    private suspend fun saveProfileTestResultSafely(profile: ProxyEntity, reason: String) {
        repeat(PROFILE_RESULT_DB_RETRY_COUNT) { attempt ->
            try {
                ProfileManager.updateProfileTestResult(profile)
                if (attempt > 0) {
                    DiagnosticLog.event(
                        "auto-vpn",
                        "profile-result-save-recovered reason=$reason attempt=${attempt + 1} " +
                                ProfileTestRunner.profileDiagnosticSummary(profile)
                    )
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLiteDatabaseLockedException) {
                DiagnosticLog.warn(
                    "auto-vpn",
                    "profile-result-save-locked reason=$reason attempt=${attempt + 1}/$PROFILE_RESULT_DB_RETRY_COUNT " +
                            ProfileTestRunner.profileDiagnosticSummary(profile),
                    e,
                )
                delay(PROFILE_RESULT_DB_RETRY_DELAY_MS * (attempt + 1))
            } catch (e: Exception) {
                Logs.w(e)
                DiagnosticLog.warn(
                    "auto-vpn",
                    "profile-result-save-failed reason=$reason " +
                            ProfileTestRunner.profileDiagnosticSummary(profile),
                    e,
                )
                return
            }
        }
        DiagnosticLog.warn(
            "auto-vpn",
            "profile-result-save-skipped-db-busy reason=$reason " +
                    ProfileTestRunner.profileDiagnosticSummary(profile)
        )
    }

    private fun testUrlForMode(mode: Int): String {
        return if (mode == MODE_GEMINI) {
            DataStore.autoVpnGeminiTestUrl.trim().ifBlank { "https://gemini.google.com/" }
        } else {
            DataStore.connectionTestURL
        }
    }

    private fun profileAllowed(profile: ProxyEntity, mode: Int): Boolean {
        if (mode != MODE_ANY && looksRussian(profile)) return false
        if (requiresExitCountryCheck(mode) && hasExcludedGlobeMarker(profile)) return false
        return mode != MODE_GEMINI || !isGeminiRejected(profile)
    }

    private fun hasExcludedGlobeMarker(profile: ProxyEntity): Boolean =
        "🌐" in profile.displayName() || "🌐" in profile.profileNameCache

    private fun requiresExitCountryCheck(mode: Int): Boolean =
        mode == MODE_NON_RU || mode == MODE_GEMINI

    private fun rememberGeminiRejectedProfiles(profileIds: Collection<Long>) {
        if (profileIds.isEmpty()) return
        val keys = LinkedHashSet(geminiRejectedKeys())
        val names = LinkedHashSet(geminiRejectedNames())
        val profiles = profileIds.mapNotNull { SagerDatabase.proxyDao.getById(it) }
        profiles.flatMap(::profileRejectKeys).forEach(keys::add)
        profiles.mapNotNull(::profileRejectName).forEach(names::add)

        while (keys.size > MAX_GEMINI_REJECTED_KEYS) {
            val iterator = keys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        while (names.size > MAX_GEMINI_REJECTED_KEYS) {
            val iterator = names.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        DataStore.autoVpnGeminiRejectedKeys = keys
        DataStore.autoVpnGeminiRejectedNames = encodeGeminiRejectedNames(names)
        updateGeminiRejectedCache(keys, names, bumpGeminiRejectedRevision())
        DiagnosticLog.critical(
            "auto-vpn",
            "gemini-rejected-persisted profiles=${profiles.map { it.id }} keys=${keys.size} names=${names.size}",
        )
    }

    private fun isGeminiRejected(profile: ProxyEntity): Boolean {
        val rejectedKeys = geminiRejectedKeys()
        if (profileRejectKeys(profile).any(rejectedKeys::contains)) return true
        val name = profileRejectName(profile) ?: return false
        return name in geminiRejectedNames()
    }

    private fun geminiRejectedKeys(): Set<String> {
        refreshGeminiRejectedCacheIfNeeded()
        return cachedGeminiRejectedKeys.orEmpty()
    }

    private fun profileRejectKeys(profile: ProxyEntity): List<String> {
        val keys = LinkedHashSet<String>()
        profileRejectKey(profile)?.let(keys::add)
        profileRejectName(profile)?.let { name ->
            // A subscription can refresh a link while preserving its exact profile name.
            keys += "name:$name"
        }
        return keys.toList()
    }

    private fun profileRejectName(profile: ProxyEntity): String? =
        profile.displayName().trim().takeIf { it.isNotEmpty() }

    private fun geminiRejectedNames(): Set<String> {
        refreshGeminiRejectedCacheIfNeeded()
        return cachedGeminiRejectedNames.orEmpty()
    }

    private fun decodeGeminiRejectedNames(encoded: String): Set<String> {
        if (encoded.isBlank()) return emptySet()
        return encoded.split(',')
            .asSequence()
            .mapNotNull { value ->
                runCatching {
                    String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
                        .takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
            .toCollection(LinkedHashSet())
    }

    private fun refreshGeminiRejectedCacheIfNeeded() {
        val revision = geminiRejectedRevision()
        if (cachedGeminiRejectedRevision == revision &&
            cachedGeminiRejectedKeys != null &&
            cachedGeminiRejectedNames != null
        ) return
        synchronized(this) {
            val currentRevision = geminiRejectedRevision()
            if (cachedGeminiRejectedRevision == currentRevision &&
                cachedGeminiRejectedKeys != null &&
                cachedGeminiRejectedNames != null
            ) return
            val keys = runCatching { DataStore.autoVpnGeminiRejectedKeys }
                .getOrElse {
                    Logs.w(it)
                    emptySet()
                }
            val names = runCatching { decodeGeminiRejectedNames(DataStore.autoVpnGeminiRejectedNames) }
                .getOrElse {
                    Logs.w(it)
                    emptySet()
                }
            updateGeminiRejectedCache(keys, names, currentRevision)
            if (keys.isEmpty() && names.isEmpty()) {
                clearGeminiTransientExclusions()
            }
        }
    }

    private fun updateGeminiRejectedCache(keys: Set<String>, names: Set<String>, revision: Long) {
        cachedGeminiRejectedKeys = keys
        cachedGeminiRejectedNames = names
        cachedGeminiRejectedRevision = revision
    }

    private fun geminiRejectedRevision(): Long =
        runCatching { DataStore.autoVpnGeminiRejectedRevision }.getOrDefault(0L)

    private fun bumpGeminiRejectedRevision(): Long {
        val next = maxOf(System.currentTimeMillis(), geminiRejectedRevision() + 1L)
        DataStore.autoVpnGeminiRejectedRevision = next
        return next
    }

    private fun clearGeminiTransientExclusions() {
        failedUntil.clear()
        manualCycleIds.remove(MODE_GEMINI)
        synchronized(scannedIds) {
            scannedIds.remove(MODE_GEMINI)
        }
        clearStoredScannedIds(MODE_GEMINI)
    }

    private fun encodeGeminiRejectedNames(names: Collection<String>): String =
        names.asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",") { name ->
                Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }

    private fun profileRejectKey(profile: ProxyEntity): String? {
        profile.importKeyCache.trim().takeIf { it.isNotEmpty() }?.let {
            return "link:$it"
        }
        return runCatching {
            val bean = profile.requireBean().applyDefaultValues()
            "bean:${profile.type}:${bean.serverAddress}:${bean.serverPort}:${bean.profileId}"
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "id:${profile.id}".takeIf { profile.id > 0L }
    }

    fun isKnownNonRussianProfile(profile: ProxyEntity, groupName: String = ""): Boolean {
        if (looksRussian(profile, groupName)) return false
        val labels = listOf(profile.displayName(), profile.profileNameCache, groupName)
        return hasKnownCountryMarker(labels)
    }

    private fun looksRussian(profile: ProxyEntity, groupNameOverride: String? = null): Boolean {
        val groupName = groupNameOverride ?: runCatching {
            SagerDatabase.groupDao.getById(profile.groupId)?.displayName().orEmpty()
        }.getOrDefault("")
        val label = listOf(
            profile.displayName(),
            profile.profileNameCache,
            groupName,
        ).joinToString(" ").lowercase(Locale.ROOT)

        if (RUSSIA_FLAG in label) return true
        if (russianTokenRegex.containsMatchIn(label)) return true

        val host = serverHost(profile) ?: return false
        return isRussianHost(host)
    }

    private fun hasKnownCountryMarker(labels: List<String>): Boolean {
        val text = labels.joinToString(" ")
        if (text.isBlank()) return false
        val normalizedText = text.lowercase(Locale.ROOT).replace('ё', 'е')
        if (knownCountryNameRegex.containsMatchIn(normalizedText)) {
            return true
        }

        val uppercaseCodes = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")
        if (uppercaseCodes.findAll(text).any {
                it.groupValues[1] in isoCountryCodes || it.groupValues[1] == "UK"
            }
        ) return true

        var index = 0
        while (index < text.length) {
            val first = Character.codePointAt(text, index)
            val nextIndex = index + Character.charCount(first)
            if (nextIndex >= text.length) break
            val second = Character.codePointAt(text, nextIndex)
            if (first !in 0x1F1E6..0x1F1FF || second !in 0x1F1E6..0x1F1FF) {
                index = nextIndex
                continue
            }
            val countryCode = buildString {
                append(('A'.code + first - 0x1F1E6).toChar())
                append(('A'.code + second - 0x1F1E6).toChar())
            }
            if (countryCode in isoCountryCodes && countryCode != "RU") return true
            index = nextIndex + Character.charCount(second)
        }
        return false
    }

    private fun serverHost(profile: ProxyEntity): String? {
        return runCatching {
            profile.requireBean().applyDefaultValues().serverAddress
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: profile.displayAddress()
                .substringBeforeLast(":")
                .removePrefix("[")
                .removeSuffix("]")
                .takeIf { it.isNotBlank() }
    }

    private fun isRussianHost(host: String): Boolean {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (cleanHost.isBlank()) return false
        val lowerHost = cleanHost.lowercase(Locale.ROOT)
        val ascii = runCatching {
            IDN.toASCII(lowerHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        }.getOrDefault(lowerHost)
        val unicode = runCatching {
            IDN.toUnicode(ascii).lowercase(Locale.ROOT)
        }.getOrDefault(lowerHost)

        if (ascii == "ru" || ascii.endsWith(".ru")) return true
        if (ascii == "su" || ascii.endsWith(".su")) return true
        if (ascii == "xn--p1ai" || ascii.endsWith(".xn--p1ai")) return true
        if (unicode == RUSSIA_CYRILLIC_TLD || unicode.endsWith(".$RUSSIA_CYRILLIC_TLD")) return true

        val ip = ipv4ToLong(lowerHost) ?: return false
        return russianIpv4Cidrs.any { it.contains(ip) }
    }

    private fun parseCidr(value: String): Ipv4Cidr? {
        val address = value.substringBefore("/")
        val prefix = value.substringAfter("/", "").toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val ip = ipv4ToLong(address) ?: return null
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return Ipv4Cidr(ip and mask, mask)
    }

    private fun ipv4ToLong(value: String): Long? {
        val parts = value.split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result and 0xffffffffL
    }

    private fun protectPathForTests(): String {
        return if (
            DataStore.tunImplementation == TunImplementation.SYSTEM &&
            DataStore.serviceMode == Key.MODE_VPN &&
            SagerNet.started &&
            DataStore.startedProfile > 0L
        ) {
            SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
        } else {
            ""
        }
    }

    private fun isVpnRunningForAuto(): Boolean {
        return SagerNet.started || DataStore.startedProfile > 0L
    }

    private suspend fun pauseVpnForDirectTests(reason: String): Boolean {
        if (!isVpnRunningForAuto()) return true

        val mode = sanitizeMode(DataStore.autoVpnMode)
        val profileId = DataStore.startedProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        DiagnosticLog.critical(
            "auto-vpn",
            "direct-test-handoff-start reason=$reason mode=$mode profile=$profileId ${DiagnosticLog.stateSummary()}"
        )
        pendingAutoServiceTransition = true
        DataStore.autoVpnServiceStartRequested = false
        if (!AutoVpnDirectSearchService.startCandidateSearch(app, mode, profileId, reason)) {
            pendingAutoServiceTransition = false
            return false
        }
        onMainDispatcher {
            SagerNet.stopService()
        }
        return false
    }

    private fun abandonPendingAutoTransitionIfStopped() {
        if (!isVpnRunningForAuto()) {
            pendingAutoServiceTransition = false
            DataStore.autoVpnServiceStartRequested = false
        }
    }

    private suspend fun switchToVerified(
        initial: CandidateResult,
        mode: Int,
        excludedIds: Set<Long>,
        forceTest: Boolean,
        startIfStopped: Boolean,
        feedback: Boolean,
        recordManualCycle: Boolean,
    ): Boolean {
        val safeMode = sanitizeMode(mode)
        val precheckEnabled = DataStore.autoVpnPrecheckEnabled
        if ((precheckEnabled || requiresExitCountryCheck(safeMode)) &&
            !pauseVpnForDirectTests("candidate-precheck")
        ) return false
        val rejectedIds = LinkedHashSet<Long>()
        var candidate: CandidateResult? = initial

        while (candidate != null &&
            DataStore.autoVpnEnabled &&
            DataStore.autoVpnMode == safeMode
        ) {
            val profile = candidate.profile
            rejectedIds += profile.id

            if (!profileAllowed(profile, safeMode) || isMarkedFailed(profile.id)) {
                consumeReserve(profile.id, safeMode)
                workingCache.remove(profile.id)
                candidate = findBestCandidate(
                    automaticExcludedIds(safeMode, excludedIds + rejectedIds + currentProfileIds()),
                    forceTest = forceTest,
                    mode = safeMode,
                )
                continue
            }

            if (!precheckEnabled && !requiresExitCountryCheck(safeMode)) {
                setAutoStatus(safeMode, R.string.auto_vpn_status_selecting)
                DiagnosticLog.critical(
                    "auto-vpn",
                    "pre-switch-test-skipped mode=$safeMode " +
                            ProfileTestRunner.profileDiagnosticSummary(profile)
                )
                switchTo(
                    profile = profile,
                    startIfStopped = startIfStopped,
                    feedback = feedback,
                    recordManualCycle = recordManualCycle,
                )
                return true
            }

            setAutoStatus(safeMode, R.string.auto_vpn_status_prechecking_candidate, profile.displayName())
            DiagnosticLog.critical(
                "auto-vpn",
                "pre-switch-test-start mode=$safeMode startIfStopped=$startIfStopped " +
                        ProfileTestRunner.profileDiagnosticSummary(profile)
            )

            val verified = testProfile(
                profile,
                safeMode,
                markFailure = true,
                verifyExitCountry = requiresExitCountryCheck(safeMode),
            )
            // A successful direct test proves that the profile can establish a connection.
            // The bad-ping threshold is for monitoring an already connected VPN; rejecting
            // a live profile here can leave initial auto-start without any connection at all.
            if (verified != null && verified.ping > 0) {
                rememberWorkingResults(safeMode, listOf(verified))
                DiagnosticLog.critical(
                    "auto-vpn",
                    "pre-switch-test-success mode=$safeMode ping=${verified.ping} " +
                            ProfileTestRunner.profileDiagnosticSummary(verified.profile)
                )
                switchTo(
                    profile = verified.profile,
                    startIfStopped = startIfStopped,
                    feedback = feedback,
                    recordManualCycle = recordManualCycle,
                )
                return true
            }

            consumeReserve(profile.id, safeMode)
            workingCache.remove(profile.id)
            if (verified != null) {
                recordProfileFailure(profile, quarantine = false)
            }
            DiagnosticLog.warn(
                "auto-vpn",
                "pre-switch-test-rejected mode=$safeMode ping=${verified?.ping ?: -1} " +
                        ProfileTestRunner.profileDiagnosticSummary(profile)
            )

            candidate = findBestCandidate(
                automaticExcludedIds(safeMode, excludedIds + rejectedIds + currentProfileIds()),
                forceTest = true,
                mode = safeMode,
            )
        }

        if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == safeMode) {
            setAutoStatus(safeMode, R.string.auto_vpn_status_no_candidate)
            abandonPendingAutoTransitionIfStopped()
        }
        return false
    }

    private suspend fun switchTo(
        profile: ProxyEntity,
        startIfStopped: Boolean,
        feedback: Boolean,
        recordManualCycle: Boolean,
    ) {
        val previous = DataStore.selectedProxy
        consumeReserve(profile.id, sanitizeMode(DataStore.autoVpnMode))
        DiagnosticLog.critical(
            "auto-vpn",
            "switchTo previous=$previous startIfStopped=$startIfStopped feedback=$feedback " +
                    "manualCycle=$recordManualCycle started=${SagerNet.started} " +
                    ProfileTestRunner.profileDiagnosticSummary(profile)
        )
        if (previous == profile.id && SagerNet.started) {
            setAutoReadyStatus(DataStore.autoVpnMode)
            DiagnosticLog.critical("auto-vpn", "switchTo-same-profile previous=$previous")
            return
        }

        DataStore.selectedProxy = profile.id
        DataStore.selectedGroup = profile.groupId
        ProfileManager.postUpdate(previous)
        ProfileManager.postUpdate(profile)
        lastSwitchAt = System.currentTimeMillis()
        lastDecisionReason = selectionReason(profile)

        if (recordManualCycle) {
            manualCycleIds.getOrPut(sanitizeMode(DataStore.autoVpnMode)) { LinkedHashSet() } += profile.id
        }
        if (DataStore.autoVpnEnabled) {
            setAutoStatus(DataStore.autoVpnMode, R.string.auto_vpn_status_switched_to, profile.displayName())
        }
        if (feedback) {
            notifyUser(R.string.auto_vpn_switched_to, profile.displayName())
        }

        val willStartService = SagerNet.started || startIfStopped || pendingAutoServiceTransition
        if (willStartService) {
            pendingAutoServiceTransition = true
            if (!SagerNet.started) {
                DataStore.autoVpnServiceStartRequested = true
            }
        }
        onMainDispatcher {
            if (SagerNet.started) {
                SagerNet.reloadService()
            } else if (willStartService) {
                SagerNet.startService()
            }
        }
    }

    private fun markFailed(profileId: Long, countFailure: Boolean = true) {
        if (profileId <= 0L) return
        val profile = SagerDatabase.proxyDao.getById(profileId)
        if (profile != null && countFailure) {
            recordProfileFailure(profile, quarantine = true)
        } else {
            failedUntil[profileId] = System.currentTimeMillis() + quarantineMillis()
        }
    }

    private fun isMarkedFailed(profileId: Long): Boolean {
        val now = System.currentTimeMillis()
        val until = failedUntil[profileId]
        if (until != null) {
            if (until > now) return true
            failedUntil.remove(profileId)
        }
        return false
    }

    private fun profileIdForMode(mode: Int): Long {
        return when (mode) {
            MODE_NON_RU -> AUTO_NON_RU_PROFILE_ID
            MODE_GEMINI -> AUTO_GEMINI_PROFILE_ID
            else -> AUTO_PROFILE_ID
        }
    }

    private fun sanitizeMode(mode: Int): Int {
        return when (mode) {
            MODE_NON_RU -> MODE_NON_RU
            MODE_GEMINI -> MODE_GEMINI
            else -> MODE_ANY
        }
    }

    private fun currentProfileIds(): List<Long> {
        return listOf(DataStore.selectedProxy, DataStore.startedProfile)
            .filter { it > 0L }
            .distinct()
    }

    private fun reserveTarget(): Int {
        return DataStore.autoVpnWorkingTarget.coerceAtLeast(1)
    }

    private fun initialSelectionWindowMillis(): Long {
        return DataStore.autoVpnInitialSelectionTimeoutSeconds.coerceAtLeast(1) * 1000L
    }

    private fun quarantineMillis(): Long {
        return DataStore.autoVpnQuarantineMinutes.coerceAtLeast(1) * 60L * 1000L
    }

    private fun storedReserveCount(mode: Int = DataStore.autoVpnMode): Int {
        return when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnReserveCountNonRu.coerceAtLeast(0)
            MODE_GEMINI -> DataStore.autoVpnReserveCountGemini.coerceAtLeast(0)
            else -> DataStore.autoVpnReserveCountAny.coerceAtLeast(0)
        }
    }

    private fun reserveCount(mode: Int = DataStore.autoVpnMode): Int {
        return reserveProfileIds(mode).size
    }

    private fun persistReserveCounts() {
        persistReserveCount(MODE_ANY)
        persistReserveCount(MODE_NON_RU)
        persistReserveCount(MODE_GEMINI)
    }

    private fun persistReserveCount(mode: Int) {
        val count = reserveProfileIds(mode).size
        when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnReserveCountNonRu = count
            MODE_GEMINI -> DataStore.autoVpnReserveCountGemini = count
            else -> DataStore.autoVpnReserveCountAny = count
        }
    }

    private fun clearReserveCounts() {
        DataStore.autoVpnReserveCountAny = 0
        DataStore.autoVpnReserveCountNonRu = 0
        DataStore.autoVpnReserveCountGemini = 0
        DataStore.autoVpnReserveIdsAny = ""
        DataStore.autoVpnReserveIdsNonRu = ""
        DataStore.autoVpnReserveIdsGemini = ""
        synchronized(workingPools) {
            workingPools.clear()
            hydratedReserveModes.clear()
        }
    }

    private fun quarantineCount(mode: Int = DataStore.autoVpnMode): Int {
        val now = System.currentTimeMillis()
        var count = 0
        failedUntil.keys.forEach { profileId ->
            val until = failedUntil[profileId]
            if (until != null && until <= now) {
                failedUntil.remove(profileId)
            } else if (until != null) {
                count += 1
            }
        }
        return count
    }

    private fun reserveProfileIds(mode: Int = DataStore.autoVpnMode): List<Long> {
        val safeMode = sanitizeMode(mode)
        ensureReservePoolLoaded(safeMode)
        val activeIds = currentProfileIds().toSet()
        val manualIds = manualCycleIds[safeMode].orEmpty()
        val retained = LinkedHashSet<Long>()
        val ids = synchronized(workingPools) {
            workingPools[safeMode].orEmpty().toList()
        }

        for (id in ids) {
            if (id <= 0L || id in activeIds || id in manualIds || isMarkedFailed(id)) {
                workingCache.remove(id)
                continue
            }
            val profile = SagerDatabase.proxyDao.getById(id)
            if (profile == null || !profileAllowed(profile, safeMode)) {
                workingCache.remove(id)
                continue
            }
            val cached = workingCache[id]
            if (cached != null && System.currentTimeMillis() - cached.at > RECENT_WORKING_TTL_MS) {
                workingCache.remove(id)
                continue
            }
            retained += id
        }

        synchronized(workingPools) {
            workingPools[safeMode] = retained.toList()
        }
        persistReserveIds(safeMode, retained)
        return retained.toList()
    }

    private fun ensureReservePoolLoaded(mode: Int) {
        val safeMode = sanitizeMode(mode)
        synchronized(workingPools) {
            if (!hydratedReserveModes.add(safeMode)) return
            workingPools[safeMode] = storedReserveIds(safeMode)
        }
    }

    private fun storedReserveIds(mode: Int): List<Long> {
        val encoded = when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnReserveIdsNonRu
            MODE_GEMINI -> DataStore.autoVpnReserveIdsGemini
            else -> DataStore.autoVpnReserveIdsAny
        }
        return encoded
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .distinct()
    }

    private fun persistReserveIds(mode: Int, profileIds: Collection<Long>) {
        val encoded = profileIds
            .asSequence()
            .filter { it > 0L }
            .distinct()
            .joinToString(",")
        when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnReserveIdsNonRu = encoded
            MODE_GEMINI -> DataStore.autoVpnReserveIdsGemini = encoded
            else -> DataStore.autoVpnReserveIdsAny = encoded
        }
    }

    private fun storedScannedIds(mode: Int): LinkedHashSet<Long> {
        val encoded = when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnScannedIdsNonRu
            MODE_GEMINI -> DataStore.autoVpnScannedIdsGemini
            else -> DataStore.autoVpnScannedIdsAny
        }
        return encoded
            .split(',')
            .asSequence()
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .toCollection(LinkedHashSet())
    }

    private fun persistScannedIds(mode: Int, profileIds: Collection<Long>) {
        val encoded = profileIds
            .asSequence()
            .filter { it > 0L }
            .distinct()
            .joinToString(",")
        when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnScannedIdsNonRu = encoded
            MODE_GEMINI -> DataStore.autoVpnScannedIdsGemini = encoded
            else -> DataStore.autoVpnScannedIdsAny = encoded
        }
    }

    private fun persistScannedIdsIfNeeded(mode: Int, force: Boolean = false) {
        val safeMode = sanitizeMode(mode)
        val now = System.currentTimeMillis()
        val lastPersist = synchronized(lastScannedIdsPersistAt) {
            lastScannedIdsPersistAt[safeMode] ?: 0L
        }
        val ids = synchronized(scannedIds) {
            scannedIds.getOrPut(safeMode) { storedScannedIds(safeMode) }.toList()
        }
        if (!force &&
            ids.size % 50 != 0 &&
            now - lastPersist < SCANNED_IDS_PERSIST_MIN_INTERVAL_MS
        ) {
            return
        }
        persistScannedIds(safeMode, ids)
        synchronized(lastScannedIdsPersistAt) {
            lastScannedIdsPersistAt[safeMode] = now
        }
    }

    private fun scannedCount(mode: Int): Int {
        val safeMode = sanitizeMode(mode)
        return synchronized(scannedIds) {
            scannedIds.getOrPut(safeMode) { storedScannedIds(safeMode) }.size
        }
    }

    private fun clearStoredScannedIds(mode: Int? = null) {
        if (mode == null) {
            DataStore.autoVpnScannedIdsAny = ""
            DataStore.autoVpnScannedIdsNonRu = ""
            DataStore.autoVpnScannedIdsGemini = ""
            synchronized(lastScannedIdsPersistAt) {
                lastScannedIdsPersistAt.clear()
            }
            return
        }
        when (sanitizeMode(mode)) {
            MODE_NON_RU -> DataStore.autoVpnScannedIdsNonRu = ""
            MODE_GEMINI -> DataStore.autoVpnScannedIdsGemini = ""
            else -> DataStore.autoVpnScannedIdsAny = ""
        }
        synchronized(lastScannedIdsPersistAt) {
            lastScannedIdsPersistAt.remove(sanitizeMode(mode))
        }
    }

    fun syncVisibleStateFromStore() {
        clearStaleStoredProgressIfNeeded()
        notificationStatus = DataStore.autoVpnStatusText.takeIf { it.isNotBlank() }
        notificationDetailStatus = DataStore.autoVpnDetailStatusText.takeIf { it.isNotBlank() }
        notificationBriefStatus = DataStore.autoVpnNotificationText.takeIf { it.isNotBlank() }
        val snapshot = storedAutoProgressSnapshot()
        synchronized(progressLock) {
            if (snapshot != null && snapshot.generation >= autoProgressGeneration) {
                val sameProgress = snapshot.generation == autoProgressGeneration &&
                        snapshot.total == autoTotal
                autoProgressGeneration = snapshot.generation
                autoRunning = snapshot.running
                autoTotal = snapshot.total
                autoFinished = if (sameProgress) {
                    maxOf(autoFinished, snapshot.finished)
                } else {
                    snapshot.finished
                }
                autoWorking = if (sameProgress) {
                    maxOf(autoWorking, snapshot.working)
                } else {
                    snapshot.working
                }
            } else if (snapshot == null) {
                autoProgressGeneration = DataStore.autoVpnProgressGeneration
                autoRunning = DataStore.autoVpnProgressRunning
                autoTotal = DataStore.autoVpnProgressTotal
                autoFinished = DataStore.autoVpnProgressFinished
                autoWorking = DataStore.autoVpnProgressWorking
            }
        }
        lastCheckedPingMillis = DataStore.autoVpnLastPingMillis.takeIf { it > 0 }
        lastCheckedPingFailed = DataStore.autoVpnLastPingFailed
        testResultsResetGeneration = DataStore.autoVpnResultsResetGeneration
        ensureAutoRuntimeJobs()
    }

    private fun ensureAutoRuntimeJobs() {
        if (!DataStore.autoVpnEnabled || !SagerNet.started || DataStore.startedProfile <= 0L) return
        activeUrlTester ?: return
        val now = System.currentTimeMillis()
        val staleAfter = checkIntervalMillis() + ACTIVE_MONITOR_STALE_GRACE_MS
        val monitor = monitorJob
        if (monitor?.isActive == true && now - lastMonitorTickAt <= staleAfter) {
            if (activeTrafficSampler != null && trafficMonitorJob?.isActive != true) {
                startTrafficMonitor()
            }
            return
        }
        if (monitor?.isActive == true) {
            DiagnosticLog.warn(
                "auto-vpn",
                "active-monitor-watchdog-restart lastTickAge=${now - lastMonitorTickAt} " +
                        "staleAfter=$staleAfter profile=${DataStore.startedProfile}"
            )
            monitor.cancel()
        } else {
            DiagnosticLog.event("auto-vpn", "active-monitor-watchdog-start profile=${DataStore.startedProfile}")
        }
        monitorJob = null
        startMonitor()
        if (activeTrafficSampler != null && trafficMonitorJob?.isActive != true) {
            startTrafficMonitor()
        }
    }

    private fun clearStaleStoredProgressIfNeeded() {
        if (!(storedAutoProgressSnapshot()?.running ?: DataStore.autoVpnProgressRunning)) return
        if (SagerNet.started || DataStore.startedProfile > 0L) return
        val updatedAt = DataStore.autoVpnStatusUpdatedAt
        if (updatedAt <= 0L) return
        val staleAfter = maxOf(120_000L, DataStore.connectionTestProgressIntervalSeconds.coerceAtLeast(1) * 4_000L)
        if (System.currentTimeMillis() - updatedAt < staleAfter) return

        clearStoredProgressState()
    }

    private fun clearStoredProgressState() {
        synchronized(progressLock) {
            beginAutoProgressGenerationLocked()
            autoRunning = false
            autoTotal = 0
            autoFinished = 0
            autoWorking = 0
            lastAutoProgressNotifyAt = 0L
        }
        persistAutoProgress()
        DataStore.autoVpnNotificationText = ""
        cancelAutoSearchNotification()
    }

    private fun touchAutoState() {
        DataStore.autoVpnStatusUpdatedAt = System.currentTimeMillis()
    }

    private fun persistAutoStatus() {
        DataStore.autoVpnStatusText = notificationStatus.orEmpty()
        DataStore.autoVpnDetailStatusText = notificationDetailStatus.orEmpty()
        DataStore.autoVpnNotificationText = notificationBriefStatus.orEmpty()
        touchAutoState()
    }

    private fun persistAutoProgress() {
        var snapshot = synchronized(progressLock) {
            AutoProgressSnapshot(
                generation = autoProgressGeneration,
                running = autoRunning,
                total = autoTotal,
                finished = autoFinished,
                working = autoWorking,
            )
        }
        if (snapshot.generation < DataStore.autoVpnProgressGeneration) {
            DiagnosticLog.warn(
                "auto-vpn",
                "progress-write-skipped-stale local=${snapshot.generation} " +
                        "stored=${DataStore.autoVpnProgressGeneration}"
            )
            return
        }
        storedAutoProgressSnapshot()?.let { stored ->
            if (stored.generation == snapshot.generation && stored.total == snapshot.total) {
                snapshot = snapshot.copy(
                    finished = maxOf(snapshot.finished, stored.finished),
                    working = maxOf(snapshot.working, stored.working),
                )
                synchronized(progressLock) {
                    autoFinished = snapshot.finished
                    autoWorking = snapshot.working
                }
            }
        }
        DataStore.autoVpnProgressGeneration = snapshot.generation
        DataStore.autoVpnProgressSnapshot = snapshot.encode()
        DataStore.autoVpnProgressRunning = snapshot.running
        DataStore.autoVpnProgressTotal = snapshot.total
        DataStore.autoVpnProgressFinished = snapshot.finished
        DataStore.autoVpnProgressWorking = snapshot.working
        touchAutoState()
        updateAutoSearchNotification()
    }

    private fun storedAutoProgressSnapshot(): AutoProgressSnapshot? {
        val fields = DataStore.autoVpnProgressSnapshot.split('|')
        if (fields.size != 5) return null
        val generation = fields[0].toLongOrNull() ?: return null
        val running = when (fields[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val total = fields[2].toIntOrNull() ?: return null
        val finished = fields[3].toIntOrNull() ?: return null
        val working = fields[4].toIntOrNull() ?: return null
        if (generation < 0L || total < 0 || finished < 0 || working < 0) return null
        return AutoProgressSnapshot(generation, running, total, finished, working)
    }

    private fun persistAutoPing() {
        DataStore.autoVpnLastPingMillis = lastCheckedPingMillis ?: 0
        DataStore.autoVpnLastPingFailed = lastCheckedPingFailed
        touchAutoState()
    }

    private fun updateAutoReserve(mode: Int = DataStore.autoVpnMode) {
        persistReserveCount(mode)
        persistAutoProgress()
        notifyAutoStateChanged()
    }

    private fun consumeReserve(profileId: Long, mode: Int = DataStore.autoVpnMode) {
        if (profileId <= 0L) return
        val safeMode = sanitizeMode(mode)
        ensureReservePoolLoaded(safeMode)
        synchronized(workingPools) {
            workingPools[safeMode] = workingPools[safeMode].orEmpty().filterNot { it == profileId }
        }
        updateAutoReserve(safeMode)
    }

    private fun markScanned(mode: Int, profileId: Long): Boolean {
        if (profileId <= 0L) return false
        val safeMode = sanitizeMode(mode)
        val added = synchronized(scannedIds) {
            scannedIds.getOrPut(safeMode) { storedScannedIds(safeMode) }.add(profileId)
        }
        if (added) persistScannedIdsIfNeeded(safeMode)
        return added
    }

    private suspend fun restartAutoScanCycle(mode: Int, scannedTotal: Int, testedAnyInCycle: Boolean): Boolean {
        val safeMode = sanitizeMode(mode)
        val completedStoredCycle = scannedCount(safeMode) >= scannedTotal
        if (scannedTotal <= 0 || (!testedAnyInCycle && !completedStoredCycle)) return false
        DiagnosticLog.critical(
            "auto-vpn",
            "restart-scan-cycle mode=$safeMode scanned=$scannedTotal testedAny=$testedAnyInCycle " +
                    "storedScanned=${scannedCount(safeMode)} progress=$autoFinished/$autoTotal working=$autoWorking"
        )
        failedUntil.clear()
        synchronized(scannedIds) {
            scannedIds.remove(safeMode)
        }
        clearStoredScannedIds(safeMode)
        persistReserveCount(safeMode)
        resetAutoProgressForNextCycle(safeMode, scannedTotal)
        setAutoStatus(safeMode, R.string.auto_vpn_status_restart_scan)
        delay(AUTO_SCAN_NEXT_CYCLE_DELAY_MS)
        return true
    }

    private fun stopScan(feedback: Boolean) {
        DiagnosticLog.critical(
            "auto-vpn",
            "stop-scan feedback=$feedback activeScan=${autoScanJob?.isActive == true} " +
                    "progress=$autoFinished/$autoTotal working=$autoWorking"
        )
        autoScanJob?.cancel()
        autoScanJob = null
        finishAutoProgress()
        setAutoStatus(DataStore.autoVpnMode, R.string.auto_vpn_status_scan_stopped)
        if (feedback) {
            notifyUser(R.string.auto_vpn_scan_stopped)
        }
    }

    private fun resetAutoSearchState(cancelScan: Boolean) {
        if (cancelScan) {
            autoScanJob?.cancel()
            autoScanJob = null
        }
        // Score is no longer part of the Auto selection algorithm.
        if (!DataStore.autoVpnProfileScoreStats.isNullOrBlank()) {
            DataStore.autoVpnProfileScoreStats = ""
        }
        failedUntil.clear()
        workingCache.clear()
        synchronized(workingPools) {
            workingPools.clear()
        }
        manualCycleIds.clear()
        synchronized(scannedIds) {
            scannedIds.clear()
        }
        clearStoredScannedIds()
        lastCheckedPingMillis = null
        lastCheckedPingFailed = false
        persistAutoPing()
        failures = 0
        clearAutoProgress()
    }

    private fun resetAutoCandidateResults(groupIds: List<Long>) {
        ProfileTestRunner.syncFromStore()
        if (ProfileTestRunner.isRunning()) return
        groupIds.chunked(500).forEach {
            SagerDatabase.proxyDao.clearTestResultsByGroups(it)
        }
        markAutoTestResultsReset()
    }

    private fun clearAutoQuarantineState() {
        failedUntil.clear()
    }

    private fun automaticExcludedIds(mode: Int, extraIds: Collection<Long>): Set<Long> {
        return (manualCycleIds[mode].orEmpty() + extraIds)
            .filter { it > 0L }
            .toSet()
    }

    private fun resetAutoProgress(total: Int, resetFinished: Boolean = true) {
        synchronized(progressLock) {
            beginAutoProgressGenerationLocked()
            autoRunning = true
            autoTotal = total.coerceAtLeast(0)
            autoFinished = if (resetFinished) {
                0
            } else if (autoTotal > 0) {
                autoFinished.coerceIn(0, autoTotal)
            } else {
                autoFinished.coerceAtLeast(0)
            }
            autoWorking = if (resetFinished) 0 else autoWorking
            lastAutoProgressNotifyAt = 0L
        }
        DiagnosticLog.event(
            "auto-vpn",
            "progress-reset total=$autoTotal resetFinished=$resetFinished finished=$autoFinished working=$autoWorking"
        )
        persistAutoProgress()
        notifyAutoStateChanged()
    }

    private fun beginOnDemandSearchProgress(mode: Int, total: Int): Boolean {
        // A direct search may continue a scan started in another process. Read the
        // persisted snapshot before claiming the progress panel, otherwise a switch
        // briefly replaces an already-running counter with 0/total.
        syncVisibleStateFromStore()
        if (autoScanJob?.isActive == true) return false
        val safeMode = sanitizeMode(mode)
        val safeTotal = total.coerceAtLeast(0)
        val retainedWorking = reserveCount(safeMode)
        val scannedFinished = scannedCount(safeMode).coerceIn(0, safeTotal)
        val stored = storedAutoProgressSnapshot()
        if (stored != null &&
            stored.total == safeTotal &&
            (stored.running || stored.finished > 0 || scannedFinished > 0)
        ) {
            synchronized(progressLock) {
                autoProgressGeneration = maxOf(autoProgressGeneration, stored.generation)
                autoRunning = true
                autoTotal = safeTotal
                autoFinished = maxOf(stored.finished, scannedFinished).coerceIn(0, safeTotal)
                autoWorking = maxOf(stored.working, retainedWorking)
                lastAutoProgressNotifyAt = 0L
            }
            DiagnosticLog.event(
                "auto-vpn",
                "progress-on-demand-resume mode=$safeMode total=$autoTotal " +
                        "finished=$autoFinished working=$autoWorking"
            )
            persistAutoProgress()
            notifyAutoStateChanged()
            return true
        }
        synchronized(progressLock) {
            beginAutoProgressGenerationLocked()
            autoRunning = true
            autoTotal = safeTotal
            autoFinished = 0
            autoWorking = retainedWorking
            lastAutoProgressNotifyAt = 0L
        }

        DiagnosticLog.event(
            "auto-vpn",
            "progress-on-demand mode=$safeMode total=$autoTotal retainedWorking=$retainedWorking"
        )
        persistAutoProgress()
        notifyAutoStateChanged()
        return true
    }

    private fun resetAutoProgressForNextCycle(mode: Int, total: Int) {
        val safeMode = sanitizeMode(mode)
        val retainedWorking = maxOf(autoWorking, reserveCount(safeMode))
        synchronized(progressLock) {
            beginAutoProgressGenerationLocked()
            autoRunning = true
            autoTotal = total.coerceAtLeast(0)
            autoFinished = 0
            autoWorking = retainedWorking.coerceAtLeast(0)
            lastAutoProgressNotifyAt = 0L
        }
        DiagnosticLog.event(
            "auto-vpn",
            "progress-next-cycle mode=$safeMode total=$autoTotal retainedWorking=$retainedWorking"
        )
        persistAutoProgress()
        notifyAutoStateChanged()
    }

    private fun clearAutoProgress() {
        synchronized(progressLock) {
            beginAutoProgressGenerationLocked()
            autoRunning = false
            autoTotal = 0
            autoFinished = 0
            autoWorking = 0
            lastAutoProgressNotifyAt = 0L
        }
        DiagnosticLog.event("auto-vpn", "progress-clear")
        clearReserveCounts()
        persistAutoProgress()
        notifyAutoStateChanged()
    }

    private fun beginAutoProgressGenerationLocked() {
        autoProgressGeneration = maxOf(
            System.currentTimeMillis(),
            autoProgressGeneration + 1L,
            DataStore.autoVpnProgressGeneration + 1L,
        )
    }

    private fun markAutoTestResultsReset() {
        testResultsResetGeneration += 1
        DataStore.autoVpnResultsResetGeneration = testResultsResetGeneration
        notifyAutoStateChanged()
    }

    private fun recordLastPing(ping: Int?) {
        lastCheckedPingMillis = ping
        lastCheckedPingFailed = ping == null
        persistAutoPing()
    }

    private fun lastPingText(): String? {
        lastCheckedPingMillis?.let {
            return app.getString(R.string.auto_vpn_last_ping, it)
        }
        return if (lastCheckedPingFailed) {
            app.getString(R.string.auto_vpn_last_ping_error)
        } else {
            null
        }
    }

    private fun hasDetailedAppStatus(): Boolean {
        return notificationDetailStatus != null ||
                notificationStatus != null ||
                autoRunning ||
                autoTotal > 0 ||
                lastCheckedPingMillis != null ||
                lastCheckedPingFailed
    }

    private fun appStatusText(mode: Int = DataStore.autoVpnMode): String {
        val parts = ArrayList<String>()
        parts += notificationDetailStatus
            ?: notificationStatus
            ?: app.getString(R.string.auto_vpn_notification_running, modeName(mode))
        lastDecisionReason?.let { parts += it }
        if (autoTotal > 0) {
            parts += app.getString(R.string.auto_vpn_checked_count, autoFinished, autoTotal)
            parts += app.getString(R.string.auto_vpn_working_count_target, autoWorking, reserveTarget())
        }
        lastPingText()?.let { parts += it }
        return parts.joinToString(" | ")
    }

    private fun finishAutoProgress() {
        synchronized(progressLock) {
            autoRunning = false
            lastAutoProgressNotifyAt = 0L
        }
        DiagnosticLog.event("auto-vpn", "progress-finish finished=$autoFinished total=$autoTotal working=$autoWorking")
        persistAutoProgress()
        notifyAutoStateChanged()
    }

    private fun recordAutoTestResult(working: Boolean) {
        val shouldNotify: Boolean
        synchronized(progressLock) {
            autoFinished = if (autoTotal > 0) {
                (autoFinished + 1).coerceAtMost(autoTotal)
            } else {
                autoFinished + 1
            }
            if (working) {
                autoWorking += 1
            }
            val now = System.currentTimeMillis()
            shouldNotify = now - lastAutoProgressNotifyAt >= progressUpdateIntervalMillis() ||
                    (autoTotal > 0 && autoFinished >= autoTotal)
            if (shouldNotify) {
                lastAutoProgressNotifyAt = now
            }
        }
        if (shouldNotify) {
            DiagnosticLog.event(
                "auto-vpn",
                "progress-count finished=$autoFinished total=$autoTotal working=$autoWorking workingResult=$working"
            )
            persistAutoProgress()
            notifyAutoStateChanged()
        }
    }

    private fun recordAutoSkippedResults(count: Int) {
        if (count <= 0) return
        val shouldNotify: Boolean
        synchronized(progressLock) {
            autoFinished = if (autoTotal > 0) {
                (autoFinished + count).coerceAtMost(autoTotal)
            } else {
                autoFinished + count
            }
            val now = System.currentTimeMillis()
            shouldNotify = now - lastAutoProgressNotifyAt >= progressUpdateIntervalMillis() ||
                    (autoTotal > 0 && autoFinished >= autoTotal)
            if (shouldNotify) {
                lastAutoProgressNotifyAt = now
            }
        }
        if (shouldNotify) {
            DiagnosticLog.event(
                "auto-vpn",
                "progress-skip count=$count finished=$autoFinished total=$autoTotal working=$autoWorking"
            )
            persistAutoProgress()
            notifyAutoStateChanged()
        }
    }

    /**
     * Older builds used a second notification for scan progress. Direct checks now
     * use the VPN notification ID, so make sure a stale legacy notification is gone.
     */
    private fun updateAutoSearchNotification() {
        cancelAutoSearchNotification()
    }

    private fun cancelAutoSearchNotification() {
        runCatching {
            NotificationManagerCompat.from(app).cancel(AUTO_SEARCH_NOTIFICATION_ID)
        }.onFailure {
            Logs.w(it)
        }
    }

    private fun notifyAutoStateChanged() {
        app.sendBroadcast(Intent(Action.AUTO_STATUS_CHANGED).setPackage(app.packageName))
        val snapshot = synchronized(listeners) {
            listeners.toList()
        }
        if (snapshot.isEmpty()) return
        runOnMainDispatcher {
            snapshot.forEach { it.onAutoVpnStateChanged() }
        }
    }

    private fun setAutoStatus(mode: Int, resId: Int, vararg args: Any) {
        val messageArgs = arrayOf<Any>(modeName(mode), *args)
        setAutoStatus(
            app.getString(resId, *messageArgs),
            briefStatus = notificationBriefForStatus(resId),
        )
    }

    private fun setAutoAppStatus(mode: Int, resId: Int, vararg args: Any) {
        val messageArgs = arrayOf<Any>(modeName(mode), *args)
        setAutoStatus(
            app.getString(resId, *messageArgs),
            clearNotificationStatus = false,
            briefStatus = notificationBriefForStatus(resId),
        )
    }

    private fun setAutoNotificationStatus(mode: Int, resId: Int, vararg args: Any) {
        val messageArgs = arrayOf<Any>(modeName(mode), *args)
        val message = app.getString(resId, *messageArgs)
        setAutoNotificationStatus(message, notificationBriefForStatus(resId))
    }

    private fun setAutoNotificationStatus(message: String, briefStatus: String?) {
        notificationDetailStatus = message
        notificationStatus = message
        notificationBriefStatus = briefStatus
        DiagnosticLog.event("auto-vpn", "notification-status=$message brief=${briefStatus.orEmpty()}")
        persistAutoStatus()
        notifyAutoStateChanged()
    }

    private fun setActiveFailureStatus(mode: Int, ping: Int?, attempt: Int, maxAttempts: Int) {
        if (ping != null) {
            val message = app.getString(
                R.string.auto_vpn_status_active_bad_ping_count,
                modeName(mode),
                ping,
                badPingMillis(),
                attempt,
                maxAttempts,
            )
            val brief = app.getString(
                R.string.auto_vpn_notification_bad_ping_attempt,
                attempt,
                maxAttempts,
            )
            setAutoNotificationStatus(message, brief)
        } else {
            val message = app.getString(
                R.string.auto_vpn_status_active_issue,
                modeName(mode),
                attempt,
                maxAttempts,
            )
            val brief = app.getString(
                R.string.auto_vpn_notification_failed_attempt,
                attempt,
                maxAttempts,
            )
            setAutoNotificationStatus(message, brief)
        }
    }

    private fun setAutoReadyStatus(mode: Int = DataStore.autoVpnMode) {
        val safeMode = sanitizeMode(mode)
        if (!DataStore.autoVpnEnabled || DataStore.autoVpnMode != safeMode) return
        val current = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        if ((SagerNet.started || DataStore.startedProfile > 0L || DataStore.autoVpnServiceStartRequested) && current != null) {
            setAutoStatus(safeMode, R.string.auto_vpn_status_ready, current.displayName())
        } else {
            setAutoStatus(null)
        }
    }

    private fun setAutoStatus(
        status: String?,
        clearNotificationStatus: Boolean = true,
        briefStatus: String? = null,
    ) {
        notificationStatus = status
        if (clearNotificationStatus) {
            notificationDetailStatus = null
        }
        notificationBriefStatus = briefStatus
        DiagnosticLog.event(
            "auto-vpn",
            "status=${status.orEmpty()} detail=${notificationDetailStatus.orEmpty()} brief=${briefStatus.orEmpty()} " +
                    "clearDetail=$clearNotificationStatus"
        )
        persistAutoStatus()
        notifyAutoStateChanged()
    }

    private fun notificationBriefForStatus(resId: Int): String? {
        return when (resId) {
            R.string.auto_vpn_status_initial_search,
            R.string.auto_vpn_status_initial_found,
            R.string.auto_vpn_status_testing_more,
            R.string.auto_vpn_status_restart_scan,
            R.string.auto_vpn_status_rechecking_reserve -> app.getString(R.string.auto_vpn_notification_searching)
            R.string.auto_vpn_status_selecting,
            R.string.auto_vpn_status_switched_to -> app.getString(R.string.auto_vpn_notification_switching)
            R.string.auto_vpn_status_prechecking_candidate,
            R.string.auto_vpn_status_verifying_connected,
            R.string.auto_vpn_status_checking_active,
            R.string.auto_vpn_status_no_direct_network_retry -> app.getString(R.string.auto_vpn_notification_checking)
            R.string.auto_vpn_status_no_candidate -> null
            R.string.auto_vpn_status_scan_stopped -> app.getString(R.string.auto_vpn_notification_scan_stopped)
            else -> null
        }
    }

    private fun notifyUser(resId: Int, vararg args: Any) {
        runOnDefaultDispatcher {
            onMainDispatcher {
                Toast.makeText(app, app.getString(resId, *args), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkIntervalMillis(): Long {
        return DataStore.autoVpnCheckIntervalSeconds.coerceAtLeast(10) * 1000L
    }

    private fun autoProgressNotificationText(): String {
        val total = autoTotal.coerceAtLeast(0)
        val finished = if (total > 0) {
            autoFinished.coerceIn(0, total)
        } else {
            autoFinished.coerceAtLeast(0)
        }
        return listOf(
            app.getString(R.string.connection_test_progress, finished, total),
            app.getString(R.string.auto_vpn_working_count_target, autoWorking, reserveTarget()),
        ).joinToString(" | ")
    }

    private fun progressUpdateIntervalMillis(): Long {
        return DataStore.connectionTestProgressIntervalSeconds.coerceAtLeast(1) * 1000L
    }

    private fun badPingMillis(): Int {
        return DataStore.autoVpnBadPingMillis.coerceAtLeast(1)
    }

    private fun failuresBeforeSwitch(): Int {
        return DataStore.autoVpnFailuresBeforeSwitch.coerceAtLeast(1)
    }

    private fun switchCooldownMillis(): Long {
        return DataStore.autoVpnSwitchCooldownSeconds.coerceAtLeast(0) * 1000L
    }
}
