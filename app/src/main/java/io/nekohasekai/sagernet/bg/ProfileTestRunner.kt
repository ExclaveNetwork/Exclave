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

import android.app.Notification
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteListenableDelegatingWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import androidx.work.workDataOf
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

private const val PROFILE_TEST_RUN_ID_INPUT = "profile_test_run_id"
private const val PROFILE_TEST_TOTAL_INPUT = "profile_test_total"
private const val PROFILE_TEST_INITIAL_FINISHED_INPUT = "profile_test_initial_finished"
private const val PROFILE_TEST_INITIAL_WORKING_INPUT = "profile_test_initial_working"
private const val PROFILE_TEST_DB_CHUNK_SIZE = 500
private const val PROFILE_TEST_MAX_WORKER_RUN_MS = 2L * 60L * 1000L
private const val PROFILE_TEST_MAX_PROFILES_PER_WORKER = 1_000
private const val PROFILE_TEST_CONTINUATION_DELAY_MS = 1_000L
private const val PROFILE_TEST_WORKER_FINISH_JOIN_TIMEOUT_MS = 5_000L
private const val PROFILE_TEST_NATIVE_ABORT_GUARD_STATUS = 3

object ProfileTestRunner {

    private const val WORK_NAME = "ProfileTestRunner"
    private const val STOP_SIGNAL_FILE = "profile-test-stop.signal"
    private const val NOTIFICATION_ID = 3
    private const val NOTIFICATION_CHANNEL = "service-profile-test"
    private const val NOTIFICATION_MIN_INTERVAL = 1000L
    private const val MIN_STALE_RUNNING_MS = 60_000L
    private const val WORK_STATE_QUERY_TIMEOUT_SECONDS = 2L

    private val listeners = Collections.synchronizedSet(mutableSetOf<Listener>())
    private var lastNotificationAt = 0L
    private var lastWorkStateReconcileAt = 0L
    private var pendingIdsRaw = ""

    @Volatile
    var running = false
        private set

    @Volatile
    var stopping = false
        private set

    @Volatile
    var refreshDeferred = false
        private set

    @Volatile
    var resumeAvailable = false
        private set

    @Volatile
    var total = 0
        private set

    @Volatile
    var finished = 0
        private set

    @Volatile
    var working = 0
        private set

    @Volatile
    private var runId = 0L

    private var pendingIds = emptyList<Long>()

    data class Snapshot(
        val total: Int,
        val finished: Int,
        val working: Int,
        val hasPending: Boolean,
        val hasResult: Boolean
    )

    private data class WorkerState(
        val runId: Long,
        val total: Int,
        val finished: Int,
        val working: Int,
    )

    private fun stateSummaryLocked(): String {
        return "runId=$runId running=$running stopping=$stopping refreshDeferred=$refreshDeferred " +
                "resume=$resumeAvailable total=$total finished=$finished working=$working " +
                "pending=${pendingIds.size}"
    }

    internal fun diagnosticStateSummary(): String = synchronized(this) {
        stateSummaryLocked()
    }

    private fun logState(event: String) {
        DiagnosticLog.event("profile-test", "$event ${diagnosticStateSummary()}")
    }

    private fun logStateCritical(event: String) {
        DiagnosticLog.critical("profile-test", "$event ${diagnosticStateSummary()}")
    }

    internal fun profileDiagnosticSummary(profile: ProxyEntity): String {
        return "id=${profile.id} group=${profile.groupId} type=${profile.type} " +
                "name=${oneLine(profile.displayName())} kind=${oneLine(profile.displayType())} " +
                "address=${oneLine(profile.displayAddress())} status=${profile.status} " +
                "ping=${profile.ping} error=${oneLine(profile.error)}"
    }

    private fun oneLine(value: String?): String {
        return value.orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()
            .take(160)
    }

    interface Listener {
        fun onProfileTestStateChanged(reloadProfiles: Boolean)
    }

    private fun stopSignalFile(): File {
        return File(SagerNet.deviceStorage.noBackupFilesDir, STOP_SIGNAL_FILE)
    }

    private fun writeStopSignal(workerRunIdToStop: Long) {
        if (workerRunIdToStop <= 0L) return
        runCatching {
            SagerNet.deviceStorage.noBackupFilesDir.mkdirs()
            stopSignalFile().writeText(
                "$workerRunIdToStop\n${System.currentTimeMillis()}"
            )
        }.onFailure {
            DiagnosticLog.warn(
                "profile-test",
                "stop-signal-write-failed runId=$workerRunIdToStop",
                it
            )
            Logs.w(it)
        }
    }

    private fun readStopSignalRunId(): Long {
        return runCatching {
            val file = stopSignalFile()
            if (!file.isFile) {
                0L
            } else {
                file.bufferedReader().use { it.readLine() }.orEmpty().toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun isStopRequestedFor(workerRunId: Long): Boolean {
        if (workerRunId <= 0L) return false
        return readStopSignalRunId() >= workerRunId
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        syncFromStore()
        listener.onProfileTestStateChanged(false)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean = running && !stopping

    fun pendingIdSet(): Set<Long> = synchronized(this) {
        pendingIds.toSet()
    }

    fun hasPendingResume(): Boolean {
        syncFromStore()
        return synchronized(this) {
            pendingIds.isNotEmpty() || resumeAvailable
        }
    }

    internal fun pendingIdList(): List<Long> = synchronized(this) {
        pendingIds.toList()
    }

    internal fun pendingIdList(workerRunId: Long): List<Long> = synchronized(this) {
        if (!isStopRequestedFor(workerRunId) && workerRunId == runId) pendingIds.toList() else emptyList()
    }

    internal fun isActiveRun(workerRunId: Long): Boolean {
        if (isStopRequestedFor(workerRunId)) return false
        return synchronized(this) {
            workerRunId == runId && running && !stopping
        }
    }

    fun currentSnapshot(): Snapshot {
        syncFromStore()
        return synchronized(this) {
            val boundedFinished = normalizedFinished(finished)
            Snapshot(
                total = total,
                finished = boundedFinished,
                working = working.coerceAtLeast(0).coerceAtMost(total.takeIf { it > 0 } ?: Int.MAX_VALUE),
                hasPending = pendingIds.isNotEmpty(),
                hasResult = total > 0 || boundedFinished > 0 || working > 0
            )
        }
    }

    fun hasVisibleState(): Boolean {
        syncFromStore()
        return running || total > 0 || pendingIds.isNotEmpty()
    }

    fun syncFromStore() = synchronized(this) {
        val storedPendingIds = DataStore.profileTestPendingIds
        if (storedPendingIds != pendingIdsRaw) {
            pendingIdsRaw = storedPendingIds
            pendingIds = decodeIds(storedPendingIds)
        }
        total = DataStore.profileTestTotal
        finished = DataStore.profileTestFinished
        working = DataStore.profileTestWorking
        running = DataStore.profileTestRunning
        refreshDeferred = running || DataStore.profileTestRefreshDeferred
        resumeAvailable = DataStore.profileTestResumeAvailable || pendingIds.isNotEmpty()
        runId = DataStore.profileTestRunId
        clearStaleRunningStateIfNeeded()
        if (running && pendingIds.isEmpty()) {
            running = false
            refreshDeferred = false
            DataStore.profileTestRunning = false
            DataStore.profileTestRefreshDeferred = false
        }
    }

    private fun clearStaleRunningStateIfNeeded() {
        if (!running) return
        val updatedAt = DataStore.profileTestUpdatedAt
        val staleAfter = maxOf(
            MIN_STALE_RUNNING_MS,
            DataStore.connectionTestProgressIntervalSeconds.coerceAtLeast(1) * 4_000L,
        )
        val isLegacyRunningState = updatedAt <= 0L
        val isStaleRunningState = updatedAt > 0L && System.currentTimeMillis() - updatedAt > staleAfter
        if (!isLegacyRunningState && !isStaleRunningState) return
        if (pendingIds.isNotEmpty()) {
            DiagnosticLog.critical(
                "profile-test",
                "stale-running-with-pending legacy=$isLegacyRunningState updatedAgoMs=${System.currentTimeMillis() - updatedAt} " +
                        stateSummaryLocked()
            )
            running = false
            stopping = false
            refreshDeferred = false
            resumeAvailable = true
            DataStore.profileTestRunning = false
            DataStore.profileTestRefreshDeferred = false
            DataStore.profileTestResumeAvailable = true
            return
        }

        DiagnosticLog.critical(
            "profile-test",
            "stale-running-cleared legacy=$isLegacyRunningState updatedAgoMs=${System.currentTimeMillis() - updatedAt} " +
                    stateSummaryLocked()
        )
        running = false
        stopping = false
        refreshDeferred = false
        resumeAvailable = pendingIds.isNotEmpty()
        DataStore.profileTestRunning = false
        DataStore.profileTestRefreshDeferred = false
        DataStore.profileTestResumeAvailable = resumeAvailable
    }

    private fun nextRunIdLocked(): Long {
        return maxOf(System.currentTimeMillis(), runId + 1, DataStore.profileTestRunId + 1)
    }

    private fun normalizedFinished(value: Int): Int {
        val safe = value.coerceAtLeast(0)
        return if (total > 0) safe.coerceAtMost(total) else safe
    }

    fun applySnapshot(snapshot: Snapshot) = synchronized(this) {
        if (!running && pendingIds.isEmpty()) {
            if (snapshot.hasResult) {
                total = snapshot.total
                finished = snapshot.finished
                working = snapshot.working
            } else {
                total = 0
                finished = 0
                working = 0
            }
        }
        resumeAvailable = snapshot.hasPending && snapshot.hasResult
        DataStore.profileTestResumeAvailable = resumeAvailable
        notifyListeners(false)
    }

    suspend fun start(
        profileIdsToTest: List<Long>,
        resetGroupIds: List<Long>,
        resume: Boolean,
        resumeSnapshot: Snapshot?,
        testUrl: String = DataStore.connectionTestURL,
        geminiFilter: Boolean = false,
        resetProfileIds: List<Long> = emptyList(),
    ) {
        val ids = profileIdsToTest.distinct()
        DiagnosticLog.critical(
            "profile-test",
            "start-request resume=$resume requestedIds=${profileIdsToTest.size} " +
                    "distinctIds=${ids.size} resetGroups=${resetGroupIds.distinct().size} " +
                    "snapshot=$resumeSnapshot"
        )
        val workerRunId: Long
        val workerTotal: Int
        val workerInitialFinished: Int
        val workerInitialWorking: Int
        synchronized(this) {
            DataStore.profileTestUrl = testUrl
            DataStore.profileTestGeminiFilter = geminiFilter
            stopping = false
            running = true
            refreshDeferred = true
            if (!resume) {
                total = ids.size
                finished = 0
                working = 0
            } else {
                val resumeFinished = (resumeSnapshot?.finished ?: finished).coerceAtLeast(0)
                total = resumeSnapshot?.total?.takeIf { it > 0 }
                    ?.let { maxOf(it, ids.size) }
                    ?: maxOf(resumeFinished + ids.size, ids.size)
                finished = normalizedFinished(resumeFinished)
                working = (resumeSnapshot?.working ?: working).coerceAtLeast(0).coerceAtMost(total)
            }
            pendingIds = ids
            resumeAvailable = ids.isNotEmpty()
            runId = nextRunIdLocked()
            workerRunId = runId
            workerTotal = total
            workerInitialFinished = finished
            workerInitialWorking = working
            persistLocked()
        }
        logStateCritical("start-persisted resume=$resume workerRunId=$workerRunId")

        if (!resume && resetGroupIds.isNotEmpty()) {
            val cleared = resetGroupIds.distinct().chunked(PROFILE_TEST_DB_CHUNK_SIZE).sumOf {
                SagerDatabase.proxyDao.clearTestResultsByGroups(it)
            }
            DiagnosticLog.critical(
                "profile-test",
                "clear-results groups=${resetGroupIds.distinct().size} profiles=$cleared workerRunId=$workerRunId"
            )
        }
        if (!resume && resetProfileIds.isNotEmpty()) {
            val cleared = resetProfileIds.distinct().chunked(PROFILE_TEST_DB_CHUNK_SIZE).sumOf {
                SagerDatabase.proxyDao.clearTestResultsByIds(it)
            }
            DiagnosticLog.critical(
                "profile-test",
                "clear-results-by-id profiles=${resetProfileIds.distinct().size} cleared=$cleared workerRunId=$workerRunId"
            )
        }

        notifyStatus(force = true)
        notifyListeners(reloadProfiles = !resume)

        enqueueWorker(
            WorkerState(
                runId = workerRunId,
                total = workerTotal,
                finished = workerInitialFinished,
                working = workerInitialWorking,
            )
        )
    }

    private fun enqueueWorker(
        state: WorkerState,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        initialDelayMs: Long = 0L,
    ) {
        DiagnosticLog.event(
            "profile-test",
            "enqueue-worker runId=${state.runId} total=${state.total} finished=${state.finished} " +
                    "working=${state.working} policy=$policy delayMs=${initialDelayMs.coerceAtLeast(0L)}"
        )
        val request = OneTimeWorkRequestBuilder<RemoteListenableDelegatingWorker>()
            .setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    RemoteListenableWorker.ARGUMENT_PACKAGE_NAME to app.packageName,
                    RemoteListenableWorker.ARGUMENT_CLASS_NAME to RemoteWorkerService::class.java.name,
                    RemoteListenableDelegatingWorker.ARGUMENT_REMOTE_LISTENABLE_WORKER_NAME to ProfileTestWorker::class.java.name,
                    PROFILE_TEST_RUN_ID_INPUT to state.runId,
                    PROFILE_TEST_TOTAL_INPUT to state.total,
                    PROFILE_TEST_INITIAL_FINISHED_INPUT to state.finished,
                    PROFILE_TEST_INITIAL_WORKING_INPUT to state.working,
                )
            )
            .build()
        RemoteWorkManager.getInstance(app).enqueueUniqueWork(
            WORK_NAME,
            policy,
            request
        )
    }

    fun stop() {
        DiagnosticLog.critical("profile-test", "stop-request ${diagnosticStateSummary()}")
        val idsAtStop: List<Long>
        val stoppedRunId: Long
        val workerRunIdToStop: Long
        synchronized(this) {
            workerRunIdToStop = runId
            writeStopSignal(workerRunIdToStop)
            idsAtStop = pendingIds.toList()
            stopping = true
            running = false
            refreshDeferred = false
            resumeAvailable = pendingIds.isNotEmpty() || resumeAvailable
            runId = nextRunIdLocked()
            stoppedRunId = runId
            persistLocked()
        }
        runCatching {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        }.onFailure {
            DiagnosticLog.warn("profile-test", "stop-local-cancel-failed runId=$workerRunIdToStop", it)
            Logs.w(it)
        }
        runCatching {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        }.onFailure {
            DiagnosticLog.warn("profile-test", "stop-remote-cancel-failed runId=$workerRunIdToStop", it)
            Logs.w(it)
        }
        if (idsAtStop.isNotEmpty()) {
            runOnDefaultDispatcher {
                val untestedIds = idsAtStop.chunked(PROFILE_TEST_DB_CHUNK_SIZE)
                    .flatMap { SagerDatabase.proxyDao.getUntestedIds(it) }
                    .toSet()
                var reconciled = false
                synchronized(this@ProfileTestRunner) {
                    syncFromStore()
                    if (isStopRequestedFor(workerRunIdToStop) && runId <= stoppedRunId) {
                        running = false
                        stopping = true
                        refreshDeferred = false
                        runId = stoppedRunId
                    }
                    if (running || runId != stoppedRunId) {
                        DiagnosticLog.critical(
                            "profile-test",
                            "stop-reconcile-skipped workerRunIdToStop=$workerRunIdToStop stoppedRunId=$stoppedRunId " +
                                    stateSummaryLocked()
                        )
                        return@synchronized
                    }
                    pendingIds = idsAtStop.filter { it in untestedIds }
                    resumeAvailable = pendingIds.isNotEmpty()
                    persistLocked()
                    reconciled = true
                }
                if (reconciled) {
                    logStateCritical(
                        "stop-reconciled idsAtStop=${idsAtStop.size} untested=${untestedIds.size} stoppedRunId=$stoppedRunId"
                    )
                    notifyStatus(force = true)
                    notifyListeners(false)
                }
            }
        }
        notifyStatus(force = true)
        notifyListeners(false)
        logStateCritical("stop-dispatched workerRunIdToStop=$workerRunIdToStop stoppedRunId=$stoppedRunId idsAtStop=${idsAtStop.size}")
    }

    fun reconcileWorkStateAsync() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastWorkStateReconcileAt < 5000L) return
            lastWorkStateReconcileAt = now
        }
        runOnDefaultDispatcher {
            val active = runCatching {
                WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get(WORK_STATE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                                it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.BLOCKED
                    }
            }.onFailure {
                DiagnosticLog.warn("profile-test", "work-state-query-failed", it)
                Logs.w(it)
            }.getOrDefault(true)

            if (!active) {
                DiagnosticLog.critical("profile-test", "work-state-not-active ${diagnosticStateSummary()}")
                restartMissingWorkerOrClearState()
            }
        }
    }

    private fun restartMissingWorkerOrClearState() {
        val state = synchronized(this) {
            syncFromStore()
            if (!running && !refreshDeferred) return
            if (running && !stopping && pendingIds.isNotEmpty()) {
                refreshDeferred = true
                resumeAvailable = true
                persistLocked()
                WorkerState(runId, total, finished, working)
            } else {
                running = false
                stopping = false
                refreshDeferred = false
                resumeAvailable = pendingIds.isNotEmpty() || resumeAvailable
                runId = nextRunIdLocked()
                persistLocked()
                null
            }
        }
        if (state != null) {
            DiagnosticLog.critical("profile-test", "restart-missing-worker ${diagnosticStateSummary()}")
            enqueueWorker(state)
        } else {
            DiagnosticLog.critical("profile-test", "clear-missing-worker ${diagnosticStateSummary()}")
        }
        notifyStatus(force = true)
        notifyListeners(false)
    }

    fun clearState() {
        synchronized(this) {
            if (running) return
            pendingIds = emptyList()
            total = 0
            finished = 0
            working = 0
            stopping = false
            refreshDeferred = false
            resumeAvailable = false
            runId = nextRunIdLocked()
            persistLocked()
            notifyStatus(force = true)
            notifyListeners(false)
        }
        logStateCritical("clear-state")
    }

    internal fun markWorkerStarted(workerRunId: Long): Boolean = synchronized(this) {
        if (isStopRequestedFor(workerRunId)) {
            DiagnosticLog.critical("profile-test", "worker-start-rejected-stop-signal runId=$workerRunId ${stateSummaryLocked()}")
            return@synchronized false
        }
        syncFromStore()
        if (isStopRequestedFor(workerRunId)) {
            DiagnosticLog.critical("profile-test", "worker-start-rejected-stop-signal-after-sync runId=$workerRunId ${stateSummaryLocked()}")
            return@synchronized false
        }
        if (workerRunId <= 0L) {
            DiagnosticLog.critical("profile-test", "worker-start-rejected-invalid runId=$workerRunId ${stateSummaryLocked()}")
            running = false
            stopping = false
            refreshDeferred = false
            resumeAvailable = pendingIds.isNotEmpty() || resumeAvailable
            runId = nextRunIdLocked()
            persistLocked()
            notifyStatus(force = true)
            notifyListeners(false)
            return@synchronized false
        }
        if (workerRunId != runId || !running) {
            DiagnosticLog.critical("profile-test", "worker-start-rejected runId=$workerRunId ${stateSummaryLocked()}")
            return@synchronized false
        }
        running = true
        stopping = false
        refreshDeferred = true
        persistLocked()
        DiagnosticLog.critical("profile-test", "worker-start-accepted runId=$workerRunId ${stateSummaryLocked()}")
        notifyListeners(false)
        true
    }

    internal fun markWorkerFinished(workerRunId: Long, reloadProfiles: Boolean): Boolean = synchronized(this) {
        syncFromStore()
        if (workerRunId != runId) {
            DiagnosticLog.critical("profile-test", "worker-finish-rejected runId=$workerRunId ${stateSummaryLocked()}")
            return@synchronized false
        }
        running = false
        stopping = false
        refreshDeferred = false
        resumeAvailable = pendingIds.isNotEmpty()
        persistLocked()
        DiagnosticLog.critical(
            "profile-test",
            "worker-finished runId=$workerRunId reloadProfiles=$reloadProfiles ${stateSummaryLocked()}"
        )
        notifyStatus(force = true)
        lastNotificationAt = 0L
        notifyListeners(reloadProfiles)
        true
    }

    internal fun markWorkerInterrupted(
        workerRunId: Long,
        enqueueContinuation: Boolean = false,
    ): Boolean {
        if (isStopRequestedFor(workerRunId)) {
            DiagnosticLog.critical(
                "profile-test",
                "worker-interrupted-rejected-stop-signal runId=$workerRunId enqueue=$enqueueContinuation ${diagnosticStateSummary()}"
            )
            return false
        }
        var state: WorkerState? = null
        val accepted = synchronized(this) {
            syncFromStore()
            if (isStopRequestedFor(workerRunId) || workerRunId != runId || stopping || pendingIds.isEmpty()) {
                DiagnosticLog.critical(
                    "profile-test",
                    "worker-interrupted-rejected runId=$workerRunId enqueue=$enqueueContinuation ${stateSummaryLocked()}"
                )
                return@synchronized false
            }
            running = true
            refreshDeferred = true
            resumeAvailable = true
            persistLocked()
            state = WorkerState(runId, total, finished, working)
            DiagnosticLog.critical(
                "profile-test",
                "worker-interrupted runId=$workerRunId enqueue=$enqueueContinuation ${stateSummaryLocked()}"
            )
            true
        }
        if (!accepted) return false
        notifyStatus(force = true)
        notifyListeners(false)
        if (enqueueContinuation) {
            state?.let {
                enqueueWorker(
                    it,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    initialDelayMs = PROFILE_TEST_CONTINUATION_DELAY_MS,
                )
            }
        }
        return true
    }

    internal fun updateProgress(
        workerRunId: Long,
        pending: List<Long>,
        finishedCount: Int,
        workingCount: Int,
    ): Boolean = synchronized(this) {
        if (isStopRequestedFor(workerRunId)) return@synchronized false
        if (workerRunId != runId || !running) return@synchronized false
        pendingIds = pending.distinct()
        finished = normalizedFinished(finishedCount)
        working = workingCount.coerceAtLeast(0).coerceAtMost(total)
        resumeAvailable = pendingIds.isNotEmpty()
        persistLocked()
        DiagnosticLog.event(
            "profile-test",
            "progress-with-pending runId=$workerRunId finished=$finished total=$total " +
                    "working=$working pending=${pendingIds.size}"
        )
        notifyStatus()
        notifyListeners(false)
        true
    }

    internal fun updateProgressCounts(
        workerRunId: Long,
        finishedCount: Int,
        workingCount: Int,
    ): Boolean = synchronized(this) {
        if (isStopRequestedFor(workerRunId)) return@synchronized false
        if (workerRunId != runId || !running) return@synchronized false
        finished = normalizedFinished(finishedCount)
        working = workingCount.coerceAtLeast(0).coerceAtMost(total)
        persistCountsLocked()
        DiagnosticLog.event(
            "profile-test",
            "progress-counts runId=$workerRunId finished=$finished total=$total " +
                    "working=$working pending=${pendingIds.size}"
        )
        notifyStatus()
        notifyListeners(false)
        true
    }

    internal fun beginExternalProgress(totalCount: Int) = synchronized(this) {
        DiagnosticLog.event("profile-test", "external-progress-begin total=$totalCount")
        stopping = false
        running = true
        refreshDeferred = true
        total = totalCount.coerceAtLeast(0)
        finished = 0
        working = 0
        pendingIds = emptyList()
        resumeAvailable = false
        runId = nextRunIdLocked()
        persistLocked()
        notifyStatus(force = true)
        notifyListeners(false)
    }

    internal fun updateExternalProgress(
        finishedCount: Int,
        workingCount: Int,
        force: Boolean = false,
        totalCount: Int? = null,
    ) = synchronized(this) {
        stopping = false
        running = true
        refreshDeferred = true
        if (totalCount != null) {
            total = totalCount.coerceAtLeast(0)
        }
        finished = if (total > 0) finishedCount.coerceIn(0, total) else finishedCount.coerceAtLeast(0)
        working = workingCount.coerceAtLeast(0)
        pendingIds = emptyList()
        resumeAvailable = false
        persistLocked()
        notifyStatus(force = force)
        notifyListeners(false)
    }

    internal fun finishExternalProgress() = synchronized(this) {
        DiagnosticLog.event("profile-test", "external-progress-finish ${stateSummaryLocked()}")
        running = false
        stopping = false
        refreshDeferred = false
        total = 0
        finished = 0
        working = 0
        pendingIds = emptyList()
        resumeAvailable = false
        runId = nextRunIdLocked()
        persistLocked()
        notifyStatus(force = true)
        lastNotificationAt = 0L
        notifyListeners(false)
    }

    internal fun clearExternalProgress() = synchronized(this) {
        DiagnosticLog.event("profile-test", "external-progress-clear ${stateSummaryLocked()}")
        running = false
        stopping = false
        refreshDeferred = false
        total = 0
        finished = 0
        working = 0
        pendingIds = emptyList()
        resumeAvailable = false
        runId = nextRunIdLocked()
        persistLocked()
        notifyStatus(force = true)
        lastNotificationAt = 0L
        notifyListeners(false)
    }

    internal fun notification(): Notification {
        val boundedFinished = if (total > 0) finished.coerceAtMost(total) else 0
        val content = app.getString(R.string.connection_test_progress, boundedFinished, total) +
                " | " + app.getString(R.string.connection_test_working, working)
        return NotificationCompat.Builder(app, NOTIFICATION_CHANNEL)
            .setWhen(0)
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(app.getString(R.string.connection_test))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(isRunning())
            .setAutoCancel(!isRunning())
            .setContentIntent(SagerNet.configureIntent(app))
            .apply {
                if (total > 0) {
                    setProgress(total, boundedFinished, false)
                } else {
                    setProgress(0, 0, isRunning())
                }
            }
            .build()
    }

    internal fun notifyStatus(force: Boolean = false) {
        if (!hasVisibleState()) {
            lastNotificationAt = 0L
            runCatching {
                NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            }.onFailure {
                Logs.w(it)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < NOTIFICATION_MIN_INTERVAL) return
        lastNotificationAt = now
        runCatching {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification())
        }.onFailure {
            Logs.w(it)
        }
    }

    private fun notifyListeners(reloadProfiles: Boolean) {
        runOnMainDispatcher {
            synchronized(listeners) {
                listeners.toList()
            }.forEach {
                it.onProfileTestStateChanged(reloadProfiles)
            }
        }
    }

    private fun persistLocked() {
        pendingIdsRaw = encodeIds(pendingIds)
        DataStore.profileTestPendingIds = pendingIdsRaw
        DataStore.profileTestTotal = total
        DataStore.profileTestFinished = finished
        DataStore.profileTestWorking = working
        DataStore.profileTestRunning = running
        DataStore.profileTestRefreshDeferred = refreshDeferred
        DataStore.profileTestResumeAvailable = resumeAvailable
        DataStore.profileTestUpdatedAt = System.currentTimeMillis()
        DataStore.profileTestRunId = runId
    }

    private fun persistCountsLocked() {
        DataStore.profileTestTotal = total
        DataStore.profileTestFinished = finished
        DataStore.profileTestWorking = working
        DataStore.profileTestRunning = running
        DataStore.profileTestRefreshDeferred = refreshDeferred
        DataStore.profileTestResumeAvailable = resumeAvailable
        DataStore.profileTestUpdatedAt = System.currentTimeMillis()
        DataStore.profileTestRunId = runId
    }

    private fun encodeIds(ids: List<Long>): String = ids.joinToString("\n")

    private fun decodeIds(value: String): List<Long> {
        return value.lineSequence()
            .mapNotNull { it.toLongOrNull() }
            .toList()
    }

    internal fun useBrowserForwarder(profile: ProxyEntity): Boolean {
        return when (val bean = profile.requireBean()) {
            is StandardV2RayBean -> {
                when (bean.type) {
                    "ws" -> bean.wsUseBrowserForwarder
                    "splithttp" -> bean.shUseBrowserForwarder
                    else -> false
                }
            }
            is ChainBean -> SagerDatabase.proxyDao.getEntities(bean.proxies).any(::useBrowserForwarder)
            is BalancerBean -> SagerDatabase.proxyDao.getEntities(bean.proxies).any(::useBrowserForwarder)
            else -> false
        }
    }

    internal fun profileTestSkipReason(profile: ProxyEntity): String? {
        if (useBrowserForwarder(profile)) {
            return app.getString(R.string.connection_test_browser_forwarder_unsupported)
        }

        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            return app.getString(R.string.connection_test_complex_profile_unsupported)
        }

        val config = profile.configBean
        if (profile.type == ProxyEntity.TYPE_CONFIG && config?.type.equals("v2ray", ignoreCase = true)) {
            val content = config?.content.orEmpty().lowercase(Locale.ROOT)
            val hasUnsafeTestFeature = listOf(
                "\"burstobservatory\"",
                "\"multiobservatory\"",
                "\"observatory\"",
                "\"balancers\"",
                "\"balancertag\"",
            ).any { it in content }

            if (hasUnsafeTestFeature) {
                return app.getString(R.string.connection_test_complex_profile_unsupported)
            }
        }

        return null
    }

    internal fun isLikelyTransientNetworkFailure(error: Throwable): Boolean {
        val message = "${error.message.orEmpty()} ${error.readableMessage}".lowercase(Locale.ROOT)
        return listOf(
            "network",
            "unreachable",
            "no route",
            "no such host",
            "temporary failure",
            "dns",
            "lookup",
            "resolve",
            "timeout",
            "deadline",
            "connection refused",
            "connection reset",
            "closed pipe",
        ).any { it in message }
    }

    internal fun updateProfileTestResult(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateTestResult(profile.id, profile.status, profile.ping, profile.error)
    }

    internal fun markProfileTestStarted(profileId: Long) {
        SagerDatabase.proxyDao.updateTestResult(
            profileId,
            PROFILE_TEST_NATIVE_ABORT_GUARD_STATUS,
            0,
            app.getString(R.string.connection_test_interrupted)
        )
    }
}

class ProfileTestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val workerRunId = inputData.getLong(PROFILE_TEST_RUN_ID_INPUT, 0L)
        DiagnosticLog.critical("profile-test-worker", "doWork-start runId=$workerRunId")
        if (!ProfileTestRunner.markWorkerStarted(workerRunId)) {
            DiagnosticLog.critical("profile-test-worker", "doWork-exit-start-rejected runId=$workerRunId")
            return@coroutineScope Result.success()
        }

        ProfileTestRunner.notifyStatus(force = true)
        DiagnosticLog.event(
            "profile-test-worker",
            "notification-refreshed runId=$workerRunId reason=initial"
        )

        DiagnosticLog.critical("profile-test-worker", "load-pending-start runId=$workerRunId")
        val storedPendingIds = ProfileTestRunner.pendingIdList(workerRunId)
        DiagnosticLog.critical(
            "profile-test-worker",
            "load-pending-complete runId=$workerRunId storedPending=${storedPendingIds.size}"
        )
        val runTotal = inputData.getInt(
            PROFILE_TEST_TOTAL_INPUT,
            maxOf(storedPendingIds.size, DataStore.profileTestTotal)
        ).coerceAtLeast(storedPendingIds.size)
        val pendingIds = storedPendingIds
        val pendingQueue = ConcurrentLinkedQueue(pendingIds)
        val storedInitialFinished = maxOf(
            inputData.getInt(PROFILE_TEST_INITIAL_FINISHED_INPUT, 0),
            DataStore.profileTestFinished,
        )
        val initialFinished = storedInitialFinished.coerceIn(0, runTotal)
        val storedInitialWorking = maxOf(
            inputData.getInt(PROFILE_TEST_INITIAL_WORKING_INPUT, 0),
            DataStore.profileTestWorking,
        )
        val initialWorking = storedInitialWorking.coerceIn(0, runTotal)
        DiagnosticLog.critical(
            "profile-test-worker",
            "initialized runId=$workerRunId storedPending=${storedPendingIds.size} " +
                    "pending=${pendingIds.size} runTotal=$runTotal " +
                    "initialFinished=$initialFinished initialWorking=$initialWorking " +
                    "storedFinished=$storedInitialFinished storedWorking=$storedInitialWorking"
        )
        if (pendingQueue.isEmpty()) {
            DiagnosticLog.critical(
                "profile-test-worker",
                "no-pending runId=$workerRunId runTotal=$runTotal initialWorking=$initialWorking"
            )
            ProfileTestRunner.updateProgress(workerRunId, emptyList(), runTotal, initialWorking)
            ProfileTestRunner.markWorkerFinished(workerRunId, reloadProfiles = true)
            return@coroutineScope Result.success()
        }
        ProfileTestRunner.updateProgressCounts(workerRunId, initialFinished, initialWorking)

        val link = DataStore.profileTestUrl.ifBlank { DataStore.connectionTestURL }
        val timeout = DataStore.connectionTestTimeoutSeconds.coerceAtLeast(1) * 1000
        val parallel = DataStore.connectionTestParallel.coerceAtLeast(1)
        val quickFailureLimit = DataStore.connectionTestQuickFailureCount.coerceAtLeast(1)
        val quickFailureMillis = DataStore.connectionTestQuickFailureMillis.coerceAtLeast(1)
        val quickFailurePauseMillis =
            DataStore.connectionTestQuickFailurePauseSeconds.coerceAtLeast(1) * 1000L
        val scheduledBreakAfterCount = DataStore.connectionTestBreakAfterCount.coerceAtLeast(0)
        val scheduledBreakMillis = DataStore.connectionTestBreakSeconds.coerceAtLeast(0) * 1000L
        val scheduledBreakEnabled = scheduledBreakAfterCount > 0 && scheduledBreakMillis > 0L
        val progressUpdateInterval = DataStore.connectionTestProgressIntervalSeconds.coerceAtLeast(1) * 1000L
        DiagnosticLog.critical(
            "profile-test-worker",
            "settings runId=$workerRunId link=${link.take(160)} timeoutMs=$timeout parallel=$parallel " +
                    "quickFailureLimit=$quickFailureLimit quickFailureMillis=$quickFailureMillis " +
                    "quickFailurePauseMs=$quickFailurePauseMillis breakAfter=$scheduledBreakAfterCount " +
                    "breakMs=$scheduledBreakMillis progressIntervalMs=$progressUpdateInterval"
        )
        val finishedCounter = AtomicInteger(initialFinished)
        val workingCounter = AtomicInteger(initialWorking)
        val quickFailureCounter = AtomicInteger(0)
        val quickFailurePaused = AtomicBoolean(false)
        val quickFailurePauseUntil = AtomicLong(0L)
        val scheduledBreakUntil = AtomicLong(0L)
        val remaining = ConcurrentHashMap.newKeySet<Long>().apply {
            addAll(pendingIds)
        }
        val activeInstances = ConcurrentHashMap.newKeySet<V2RayTestInstance>()
        val resultWriteLock = Mutex()
        val lastProgressUpdateAt = AtomicLong(0L)

        fun shouldPublishProgress(force: Boolean): Boolean {
            val now = System.currentTimeMillis()
            if (force) {
                lastProgressUpdateAt.set(now)
                return true
            }
            while (true) {
                val last = lastProgressUpdateAt.get()
                if (now - last < progressUpdateInterval) return false
                if (lastProgressUpdateAt.compareAndSet(last, now)) return true
            }
        }

        fun remainingSnapshot(): List<Long> = pendingIds.filter { it in remaining }

        suspend fun publishProgress(force: Boolean = false, includePending: Boolean = false) {
            if (!shouldPublishProgress(force)) return
            val accepted = if (includePending) {
                ProfileTestRunner.updateProgress(
                    workerRunId,
                    remainingSnapshot(),
                    finishedCounter.get(),
                    workingCounter.get(),
                )
            } else {
                ProfileTestRunner.updateProgressCounts(
                    workerRunId,
                    finishedCounter.get(),
                    workingCounter.get(),
                )
            }
            if (!accepted) return
        }

        suspend fun saveProfileTestResult(profile: ProxyEntity) {
            resultWriteLock.withLock {
                runCatching {
                    ProfileTestRunner.updateProfileTestResult(profile)
                }.onFailure {
                    Logs.w(it)
                }
            }
        }

        suspend fun waitForPause() {
            while (isActive && ProfileTestRunner.isActiveRun(workerRunId)) {
                val now = System.currentTimeMillis()
                if (quickFailurePauseUntil.get() <= now) {
                    quickFailurePaused.set(false)
                }
                val wait = maxOf(quickFailurePauseUntil.get(), scheduledBreakUntil.get()) - now
                if (wait <= 0L) return
                delay(wait.coerceAtMost(250L))
            }
        }

        val wakeLock = SagerNet.power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "exclaved:profile-test"
        )
        val wakeLockTimeout = (
                ((remaining.size / parallel.coerceAtLeast(1)) + 1L) * timeout.toLong() +
                        15L * 60L * 1000L
                ).coerceIn(60L * 60L * 1000L, 6L * 60L * 60L * 1000L)
        wakeLock.acquire(wakeLockTimeout)
        DiagnosticLog.critical(
            "profile-test-worker",
            "wakelock-acquired runId=$workerRunId timeoutMs=$wakeLockTimeout remaining=${remaining.size}"
        )

        val testJobs = mutableListOf<Job>()
        val workerStartedAt = System.currentTimeMillis()
        val processedCounter = AtomicInteger(0)
        var enqueueContinuation = false
        var stopWatcher: Job? = null

        fun workerTimeSliceExpired(): Boolean {
            return System.currentTimeMillis() - workerStartedAt >= PROFILE_TEST_MAX_WORKER_RUN_MS ||
                    processedCounter.get() >= PROFILE_TEST_MAX_PROFILES_PER_WORKER
        }

        try {
            repeat(parallel) { workerIndex ->
                testJobs.add(launch {
                    if (workerIndex > 0) {
                        delay((workerIndex % 50) * 20L)
                    }
                    while (isActive) {
                        if (!ProfileTestRunner.isActiveRun(workerRunId)) break
                        if (workerTimeSliceExpired()) break
                        waitForPause()
                        if (workerTimeSliceExpired()) break
                        val profileId = pendingQueue.poll() ?: break
                        if (!ProfileTestRunner.isActiveRun(workerRunId)) {
                            remaining.add(profileId)
                            break
                        }
                        if (processedCounter.incrementAndGet() > PROFILE_TEST_MAX_PROFILES_PER_WORKER) {
                            remaining.add(profileId)
                            break
                        }
                        val profile = try {
                            SagerDatabase.proxyDao.getById(profileId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.w(e)
                            null
                        }
                        if (profile != null) {
                            try {
                                val skipReason = ProfileTestRunner.profileTestSkipReason(profile)
                                if (skipReason != null) {
                                    profile.status = 3
                                    profile.ping = 0
                                    profile.error = skipReason
                                    DiagnosticLog.event(
                                        "profile-test-worker",
                                        "profile-skipped-unsupported runId=$workerRunId worker=$workerIndex " +
                                                ProfileTestRunner.profileDiagnosticSummary(profile)
                                    )
                                    saveProfileTestResult(profile)
                                } else {
                                    profile.status = 0
                                    profile.error = null

                                    val startedAt = System.currentTimeMillis()
                                    try {
                                        ProfileTestRunner.markProfileTestStarted(profile.id)
                                        DiagnosticLog.critical(
                                            "profile-test-worker",
                                            "profile-test-start runId=$workerRunId worker=$workerIndex " +
                                                    ProfileTestRunner.profileDiagnosticSummary(profile)
                                        )
                                        val instance = if (
                                            DataStore.tunImplementation == TunImplementation.SYSTEM &&
                                            DataStore.serviceMode == Key.MODE_VPN &&
                                            SagerNet.started &&
                                            DataStore.startedProfile > 0
                                        ) {
                                            V2RayTestInstance(
                                                profile,
                                                link,
                                                timeout,
                                                protectPath = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
                                            )
                                        } else {
                                            V2RayTestInstance(profile, link, timeout)
                                        }
                                        activeInstances.add(instance)
                                        try {
                                            val testedPing = instance.doTest()
                                            if (!ProfileTestRunner.isActiveRun(workerRunId)) {
                                                throw CancellationException("Profile test stopped")
                                            }
                                            profile.ping = testedPing
                                            profile.status = 1
                                            quickFailureCounter.set(0)
                                            DiagnosticLog.event(
                                                "profile-test-worker",
                                                "profile-test-success runId=$workerRunId worker=$workerIndex " +
                                                        "elapsedMs=${System.currentTimeMillis() - startedAt} " +
                                                        ProfileTestRunner.profileDiagnosticSummary(profile)
                                            )
                                        } finally {
                                            activeInstances.remove(instance)
                                            runCatching {
                                                instance.close()
                                            }.onFailure {
                                                Logs.w(it)
                                            }
                                        }
                                    } catch (e: PluginManager.PluginNotFoundException) {
                                        profile.status = -1
                                        profile.error = e.readableMessage
                                        quickFailureCounter.set(0)
                                        DiagnosticLog.event(
                                            "profile-test-worker",
                                            "profile-test-plugin-missing runId=$workerRunId worker=$workerIndex " +
                                                    "elapsedMs=${System.currentTimeMillis() - startedAt} " +
                                                    ProfileTestRunner.profileDiagnosticSummary(profile)
                                        )
                                    } catch (e: CancellationException) {
                                        DiagnosticLog.event(
                                            "profile-test-worker",
                                            "profile-test-cancelled runId=$workerRunId worker=$workerIndex " +
                                                    "elapsedMs=${System.currentTimeMillis() - startedAt} " +
                                                    ProfileTestRunner.profileDiagnosticSummary(profile)
                                        )
                                        throw e
                                    } catch (e: Exception) {
                                        val elapsed = System.currentTimeMillis() - startedAt
                                        val quickNetworkFailure =
                                            elapsed <= quickFailureMillis &&
                                                    ProfileTestRunner.isLikelyTransientNetworkFailure(e)
                                        if (quickNetworkFailure && !quickFailurePaused.get()) {
                                            val failures = quickFailureCounter.incrementAndGet()
                                            if (failures >= quickFailureLimit) {
                                                quickFailureCounter.set(0)
                                                val until = System.currentTimeMillis() + quickFailurePauseMillis
                                                quickFailurePaused.set(true)
                                                quickFailurePauseUntil.updateAndGet { current -> maxOf(current, until) }
                                                DiagnosticLog.critical(
                                                    "profile-test-worker",
                                                    "quick-failure-pause runId=$workerRunId failures=$failures " +
                                                            "pauseMs=$quickFailurePauseMillis until=$until"
                                                )
                                            }
                                        } else {
                                            quickFailureCounter.set(0)
                                        }
                                        profile.status = 3
                                        profile.error = e.readableMessage
                                        DiagnosticLog.event(
                                            "profile-test-worker",
                                            "profile-test-failed runId=$workerRunId worker=$workerIndex elapsedMs=$elapsed " +
                                                    "quickNetworkFailure=$quickNetworkFailure " +
                                                    ProfileTestRunner.profileDiagnosticSummary(profile),
                                        )
                                    }

                                    saveProfileTestResult(profile)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logs.w(e)
                                profile.status = 3
                                profile.error = e.readableMessage
                                DiagnosticLog.error(
                                    "profile-test-worker",
                                    "profile-test-save-or-wrapper-failed runId=$workerRunId worker=$workerIndex " +
                                            ProfileTestRunner.profileDiagnosticSummary(profile),
                                    e,
                                )
                                saveProfileTestResult(profile)
                            }
                        } else {
                            DiagnosticLog.event(
                                "profile-test-worker",
                                "profile-missing runId=$workerRunId worker=$workerIndex id=$profileId"
                            )
                        }
                        remaining.remove(profileId)
                        val finished = finishedCounter.incrementAndGet()
                        if (profile?.status == 1) {
                            workingCounter.incrementAndGet()
                        }
                        if (scheduledBreakEnabled &&
                            finished < runTotal &&
                            finished % scheduledBreakAfterCount == 0
                        ) {
                            val until = System.currentTimeMillis() + scheduledBreakMillis
                            scheduledBreakUntil.updateAndGet { current -> maxOf(current, until) }
                            DiagnosticLog.event(
                                "profile-test-worker",
                                "scheduled-break runId=$workerRunId finished=$finished total=$runTotal " +
                                        "breakMs=$scheduledBreakMillis until=$until"
                            )
                        }
                        publishProgress()
                    }
                })
            }
            stopWatcher = launch {
                while (isActive) {
                    delay(250L)
                    if (ProfileTestRunner.isActiveRun(workerRunId)) continue
                    DiagnosticLog.critical(
                        "profile-test-worker",
                        "stop-signal-observed runId=$workerRunId activeInstances=${activeInstances.size} " +
                                "processed=${processedCounter.get()} remaining=${remaining.size}"
                    )
                    testJobs.forEach { it.cancel() }
                    activeInstances.forEach {
                        runCatching {
                            it.close()
                        }.onFailure { error ->
                            Logs.w(error)
                        }
                    }
                    break
                }
            }
            testJobs.joinAll()
            stopWatcher.cancel()
            enqueueContinuation = remaining.isNotEmpty() && ProfileTestRunner.isActiveRun(workerRunId)
            DiagnosticLog.critical(
                "profile-test-worker",
                "jobs-complete runId=$workerRunId processed=${processedCounter.get()} " +
                        "remaining=${remaining.size} enqueueContinuation=$enqueueContinuation " +
                        "finished=${finishedCounter.get()} working=${workingCounter.get()}"
            )
            Result.success()
        } catch (e: CancellationException) {
            DiagnosticLog.critical(
                "profile-test-worker",
                "cancelled runId=$workerRunId active=${ProfileTestRunner.isActiveRun(workerRunId)} " +
                        "processed=${processedCounter.get()} remaining=${remaining.size}"
            )
            if (ProfileTestRunner.isActiveRun(workerRunId) && remaining.isNotEmpty()) {
                enqueueContinuation = true
                Result.success()
            } else if (ProfileTestRunner.isActiveRun(workerRunId)) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            DiagnosticLog.error(
                "profile-test-worker",
                "worker-exception runId=$workerRunId active=${ProfileTestRunner.isActiveRun(workerRunId)} " +
                        "processed=${processedCounter.get()} remaining=${remaining.size}",
                e,
            )
            Logs.w(e)
            if (ProfileTestRunner.isActiveRun(workerRunId) && remaining.isNotEmpty()) {
                enqueueContinuation = true
                Result.success()
            } else if (ProfileTestRunner.isActiveRun(workerRunId)) {
                Result.retry()
            } else {
                Result.success()
            }
        } finally {
            val workerWasActive = isActive
            DiagnosticLog.critical(
                "profile-test-worker",
                "finally-start runId=$workerRunId workerWasActive=$workerWasActive " +
                        "activeRun=${ProfileTestRunner.isActiveRun(workerRunId)} " +
                        "processed=${processedCounter.get()} remaining=${remaining.size} " +
                        "activeInstances=${activeInstances.size} enqueueContinuation=$enqueueContinuation"
            )
            withContext(NonCancellable) {
                stopWatcher?.cancel()
                if (!workerWasActive || !ProfileTestRunner.isActiveRun(workerRunId)) {
                    testJobs.forEach { it.cancel() }
                    activeInstances.forEach {
                        runCatching {
                            it.close()
                        }.onFailure { error ->
                            Logs.w(error)
                        }
                    }
                }
                withTimeoutOrNull(PROFILE_TEST_WORKER_FINISH_JOIN_TIMEOUT_MS) {
                    testJobs.joinAll()
                }
                publishProgress(force = true, includePending = true)
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    DiagnosticLog.critical("profile-test-worker", "wakelock-released runId=$workerRunId")
                }
                if (remaining.isEmpty() || !ProfileTestRunner.isActiveRun(workerRunId)) {
                    ProfileTestRunner.markWorkerFinished(workerRunId, reloadProfiles = true)
                } else {
                    ProfileTestRunner.markWorkerInterrupted(
                        workerRunId,
                        enqueueContinuation = enqueueContinuation
                    )
                }
                DiagnosticLog.critical(
                    "profile-test-worker",
                    "finally-complete runId=$workerRunId remaining=${remaining.size} " +
                            "activeRun=${ProfileTestRunner.isActiveRun(workerRunId)}"
                )
            }
        }
    }
}
