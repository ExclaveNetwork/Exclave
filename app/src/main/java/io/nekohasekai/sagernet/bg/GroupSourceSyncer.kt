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
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libsagernetcore.Libsagernetcore
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

object GroupSourceSyncer {

    private const val WORK_NAME = "GroupSourceSyncer"
    private const val VPN_WORK_NAME = "GroupSourceVpnSyncer"
    private const val KEY_RETRY_ATTEMPT = "retryAttempt"
    private const val KEY_SEPARATOR = "\u001F"
    private const val CONFIG_SOURCE_KEY = "\u001Fgroup-source-configs"
    private const val CONFIG_SOURCE_FILE = "group_source_configs.txt"
    private const val CONFIG_SOURCE_NAME = "local"
    private val syncMutex = Mutex()

    fun isVpnRunning(): Boolean {
        return DataStore.serviceMode == Key.MODE_VPN && DataStore.startedProfile > 0L
    }

    fun shouldOwnVpnSync(): Boolean {
        return DataStore.groupSourceSyncEnabled &&
                DataStore.groupSourceVpnSyncEnabled &&
                DataStore.groupSourceUrl.trim().isNotBlank() &&
                isVpnRunning()
    }

    fun onVpnStarted() {
        if (shouldOwnVpnSync()) {
            scheduleVpnSync(vpnInitialDelayMinutes(), retryAttempt = 0)
        } else {
            cancelVpnSync()
        }
    }

    fun onVpnStopped() {
        cancelVpnSync()
    }

    fun reconfigureVpnSyncForState() {
        if (shouldOwnVpnSync()) {
            scheduleVpnSync(vpnInitialDelayMinutes(), retryAttempt = 0)
        } else {
            cancelVpnSync()
        }
    }

    data class SyncSummary(
        val disabled: Boolean = false,
        val added: Int = 0,
        val kept: Int = 0,
        val removed: Int = 0,
        val addedGroups: List<ProxyGroup> = emptyList(),
        val updateGroups: List<ProxyGroup> = emptyList(),
    )

    private data class ParsedSource(
        val subscriptions: List<SourceEntry>,
        val rawConfigText: String,
    ) {
        val isEmpty: Boolean
            get() = subscriptions.isEmpty() && rawConfigText.isBlank()
    }

    private data class SourceEntry(
        val link: String,
        val name: String,
        val key: String,
    )

    suspend fun reconfigureSync() {
        if (!DataStore.groupSourceSyncEnabled || DataStore.groupSourceUrl.trim().isBlank()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            cancelVpnSync()
            return
        }

        val intervalMinutes = DataStore.groupSourceSyncIntervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequest.Builder(
            SyncTask::class.java,
            intervalMinutes,
            TimeUnit.MINUTES
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            30,
            TimeUnit.MINUTES
        ).build()

        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        reconfigureVpnSyncForState()
        Logs.d("reconfigureGroupSourceSync, interval: $intervalMinutes min")
    }

    private fun scheduleVpnSync(delayMinutes: Long, retryAttempt: Int) {
        val request = OneTimeWorkRequestBuilder<VpnSyncTask>()
            .setInitialDelay(delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_RETRY_ATTEMPT to retryAttempt.coerceAtLeast(0)))
            .build()

        RemoteWorkManager.getInstance(app).enqueueUniqueWork(
            VPN_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Logs.d("GroupSourceVpnSyncer scheduled: $delayMinutes min, retry=$retryAttempt")
    }

    private fun cancelVpnSync() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(VPN_WORK_NAME)
    }

    private fun vpnInitialDelayMinutes(): Long {
        return DataStore.groupSourceVpnInitialDelayMinutes.coerceAtLeast(1).toLong()
    }

    private fun vpnIntervalMinutes(): Long {
        return DataStore.groupSourceVpnIntervalMinutes.coerceAtLeast(1).toLong()
    }

    private fun vpnRetryDelayMinutes(): Long {
        return DataStore.groupSourceVpnRetryIntervalMinutes.coerceAtLeast(1).toLong()
    }

    private fun vpnRetryCount(): Int {
        return DataStore.groupSourceVpnRetryCount.coerceAtLeast(0)
    }

    suspend fun syncNow(): SyncSummary = syncMutex.withLock {
        if (!DataStore.groupSourceSyncEnabled) return@withLock SyncSummary(disabled = true)
        val sourceUrl = DataStore.groupSourceUrl.trim()
        if (sourceUrl.isBlank()) return@withLock SyncSummary(disabled = true)

        val content = stage("download") {
            fetchSource(sourceUrl)
        }
        val source = stage("parse") {
            parseSource(content)
        }
        if (source.isEmpty) {
            error("parse: no subscription links or supported profile configs found in group source")
        }

        stage("apply") {
            applySource(source)
        }
    }

    suspend fun syncNowAndUpdate(): SyncSummary {
        val summary = syncNow()
        updateSyncedGroups(summary)
        return summary
    }

    private suspend inline fun <T> stage(name: String, crossinline block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            Logs.w(e)
            error("$name: ${e.readableMessage}")
        }
    }

    private suspend fun fetchSource(url: String): String = withContext(Dispatchers.IO) {
        val currentUrl = uncachedSourceUrl(normalizeSourceUrl(url))
        val client = Libsagernetcore.newHttpClient().apply {
            keepAlive()
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }
        try {
            stage("download/core") {
                client.newRequest().apply {
                    setURL(currentUrl)
                    setUserAgent(USER_AGENT)
                    setHeader("Cache-Control", "no-cache, no-store, max-age=0")
                    setHeader("Pragma", "no-cache")
                    SubscriptionRequestHeaders.applyTo { key, value -> setHeader(key, value) }
                    if (currentUrl.toUri().host.equals("api.github.com", ignoreCase = true)) {
                        setHeader("Accept", "application/vnd.github.raw")
                        setHeader("X-GitHub-Api-Version", "2022-11-28")
                    }
                }.execute().contentString
            }
        } finally {
            runCatching {
                client.close()
            }.onFailure {
                Logs.w(it)
            }
        }
    }

    private fun normalizeSourceUrl(url: String): String {
        return githubApiContentUrl(url) ?: runCatching {
            val uri = url.toUri()
            val segments = uri.pathSegments
                if (uri.host.equals("github.com", ignoreCase = true) &&
                segments.size >= 5 &&
                segments[2] == "blob"
            ) {
                val owner = segments[0]
                val repo = segments[1]
                val branch = segments[3]
                val path = segments.drop(4).joinToString("/")
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            } else {
                url
            }
        }.getOrElse {
            url
        }
    }

    private fun githubApiContentUrl(url: String): String? {
        return runCatching {
            val uri = url.toUri()
            val segments = uri.pathSegments
            when {
                uri.host.equals("github.com", ignoreCase = true) &&
                        segments.size >= 5 &&
                        segments[2] == "blob" -> {
                    githubApiContentUrl(
                        owner = segments[0],
                        repo = segments[1],
                        ref = segments[3],
                        pathSegments = segments.drop(4),
                    )
                }
                uri.host.equals("raw.githubusercontent.com", ignoreCase = true) &&
                        segments.size >= 4 -> {
                    githubApiContentUrl(
                        owner = segments[0],
                        repo = segments[1],
                        ref = segments[2],
                        pathSegments = segments.drop(3),
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun githubApiContentUrl(
        owner: String,
        repo: String,
        ref: String,
        pathSegments: List<String>,
    ): String {
        return "https://api.github.com".toUri().buildUpon()
            .appendPath("repos")
            .appendPath(owner)
            .appendPath(repo)
            .appendPath("contents")
            .apply {
                pathSegments.forEach { appendPath(it) }
            }
            .appendQueryParameter("ref", ref)
            .build()
            .toString()
    }

    private fun uncachedSourceUrl(url: String): String {
        return runCatching {
            val uri = url.toUri()
            if (uri.host.equals("api.github.com", ignoreCase = true) ||
                uri.host.equals("raw.githubusercontent.com", ignoreCase = true) ||
                uri.host.equals("github.com", ignoreCase = true)
            ) {
                uri.buildUpon()
                    .appendQueryParameter("_exclaved_sync", System.currentTimeMillis().toString())
                    .build()
                    .toString()
            } else {
                url
            }
        }.getOrElse {
            url
        }
    }

    private fun parseSource(content: String): ParsedSource {
        val subscriptions = ArrayList<SourceEntry>()
        val rawConfigs = ArrayList<String>()

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
            .forEach { line ->
                line.split(Regex("\\s+"))
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
                    .forEach { item ->
                        when {
                            isSubscriptionSourceLink(item) -> subscriptions += parseEntry(item)
                            isShareConfigLink(item) -> rawConfigs += item
                        }
                    }
            }

        return ParsedSource(
            subscriptions = subscriptions.distinctBy { it.key },
            rawConfigText = rawConfigs.distinct().joinToString("\n"),
        )
    }

    private fun isSubscriptionSourceLink(value: String): Boolean {
        return value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
    }

    private fun isShareConfigLink(value: String): Boolean {
        return value.startsWith("socks://", ignoreCase = true) ||
                value.startsWith("socks4://", ignoreCase = true) ||
                value.startsWith("socks4a://", ignoreCase = true) ||
                value.startsWith("socks5://", ignoreCase = true) ||
                value.startsWith("socks5h://", ignoreCase = true) ||
                value.startsWith("vmess://", ignoreCase = true) ||
                value.startsWith("vless://", ignoreCase = true) ||
                value.startsWith("trojan://", ignoreCase = true) ||
                value.startsWith("ss://", ignoreCase = true) ||
                value.startsWith("ssr://", ignoreCase = true) ||
                value.startsWith("naive+https", ignoreCase = true) ||
                value.startsWith("naive+quic", ignoreCase = true) ||
                value.startsWith("hysteria2://", ignoreCase = true) ||
                value.startsWith("hy2://", ignoreCase = true) ||
                value.startsWith("juicity://", ignoreCase = true) ||
                value.startsWith("tuic://", ignoreCase = true) ||
                value.startsWith("wireguard://", ignoreCase = true) ||
                value.startsWith("wg://", ignoreCase = true) ||
                value.startsWith("mierus://", ignoreCase = true) ||
                value.startsWith("quic://", ignoreCase = true) ||
                value.startsWith("anytls://", ignoreCase = true) ||
                value.startsWith("ssh://", ignoreCase = true) ||
                value.startsWith("tt://", ignoreCase = true)
    }

    private suspend fun writeRawConfigSource(text: String): String = withContext(Dispatchers.IO) {
        val file = File(app.cacheDir, CONFIG_SOURCE_FILE)
        file.writeText(text.trim() + "\n", Charsets.UTF_8)
        FileProvider.getUriForFile(app, "${app.packageName}.cache", file).toString()
    }

    private fun isGroupSourceConfigLink(link: String): Boolean {
        return link.startsWith("content://", ignoreCase = true) &&
                link.substringBefore('?')
                    .substringBefore('#')
                    .endsWith("/$CONFIG_SOURCE_FILE", ignoreCase = true)
    }

    private suspend fun validateRawConfigSource(text: String) {
        stage("apply/config-parse") {
            RawUpdater.parseRaw(text)?.takeIf { it.isNotEmpty() }
                ?: error("no supported profile configs found in group source")
        }
    }

    private fun newSubscriptionGroup(name: String, link: String): ProxyGroup {
        return ProxyGroup(
            type = GroupType.SUBSCRIPTION,
            name = name,
            subscription = SubscriptionBean().applyDefaultValues().apply {
                type = SubscriptionType.RAW
                this.link = link
                deduplication = DataStore.subscriptionRemoveDuplicates
                autoUpdate = true
                autoUpdateDelay = DataStore.subscriptionGlobalAutoUpdateDelay
            }
        )
    }

    private suspend fun createManagedGroup(group: ProxyGroup, nextOrder: Long): ProxyGroup {
        return stage("apply/create") {
            group.userOrder = nextOrder
            group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
            group
        }
    }

    private suspend fun updateManagedGroup(group: ProxyGroup) {
        stage("apply/update") {
            SagerDatabase.groupDao.updateGroup(group)
        }
    }

    private suspend fun deleteRawConfigSourceFile() {
        stage("apply/config-delete") {
            withContext(Dispatchers.IO) {
                File(app.cacheDir, CONFIG_SOURCE_FILE).delete()
            }
        }
    }

    private suspend fun createRawConfigGroup(link: String, nextOrder: Long): ProxyGroup {
        return createManagedGroup(
            newSubscriptionGroup(CONFIG_SOURCE_NAME, link),
            nextOrder,
        )
    }

    private fun configGroupIn(groups: List<ProxyGroup>): ProxyGroup? {
        return groups.firstOrNull { groupKey(it) == CONFIG_SOURCE_KEY }
    }

    private suspend fun applyRawConfigSource(
        text: String,
        subscriptions: List<ProxyGroup>,
        nextOrder: Long,
    ): Pair<ProxyGroup, Boolean> {
        val link = stage("apply/config-file") {
            writeRawConfigSource(text)
        }
        val existing = configGroupIn(subscriptions)
        if (existing != null) {
            val subscription = existing.subscription
            var changed = false
            if (existing.name != CONFIG_SOURCE_NAME) {
                existing.name = CONFIG_SOURCE_NAME
                changed = true
            }
            if (subscription?.link != link) {
                subscription?.link = link
                changed = true
            }
            if (changed) {
                updateManagedGroup(existing)
            }
            return existing to false
        }

        return createRawConfigGroup(link, nextOrder) to true
    }

    private fun parseEntry(value: String): SourceEntry {
        val separator = value.indexOf('#')
        val link = if (separator >= 0) value.substring(0, separator).trim() else value.trim()
        val normalizedLink = normalizeSubscriptionLink(link)
        val name = (if (separator >= 0) value.substring(separator + 1).trim() else "")
            .ifBlank { normalizedLink }
        return SourceEntry(
            link = normalizedLink,
            name = name,
            key = sourceKey(link),
        )
    }

    private suspend fun applySource(source: ParsedSource): SyncSummary {
        val entries = source.subscriptions
        if (source.rawConfigText.isNotBlank()) {
            validateRawConfigSource(source.rawConfigText)
        }
        val sourceKeys = stage("apply/source") {
            entries.mapTo(mutableSetOf()) { it.key }.apply {
                if (source.rawConfigText.isNotBlank()) add(CONFIG_SOURCE_KEY)
            }
        }
        val previousManagedKeys = stage("apply/load") {
            decodeManagedKeys(DataStore.groupSourceManagedKeys)
        }
        val managedKeys = sourceKeys.toMutableSet()
        val subscriptions = stage("apply/groups") {
            SagerDatabase.groupDao.subscriptions()
        }
        val groupsByKey = subscriptions.associateBy(::groupKey)
        val existingKeys = stage("apply/existing") {
            subscriptions.mapTo(mutableSetOf(), ::groupKey)
        }
        val addedGroups = mutableListOf<ProxyGroup>()
        val updateGroups = mutableListOf<ProxyGroup>()
        var nextOrder = stage("apply/order") {
            SagerDatabase.groupDao.nextOrder() ?: 1
        }

        var added = 0
        var kept = 0
        for (entry in entries) {
            val existing = groupsByKey[entry.key]
            if (existing != null) {
                kept++
                if ((existing.subscription?.lastUpdated ?: 0L) <= 0L) {
                    updateGroups += existing
                }
            } else {
                val group = createManagedGroup(
                    newSubscriptionGroup(entry.name, entry.link),
                    nextOrder++,
                )
                addedGroups += group
                updateGroups += group
                existingKeys += entry.key
                added++
            }
            managedKeys += entry.key
        }

        if (source.rawConfigText.isNotBlank()) {
            val (group, created) = applyRawConfigSource(source.rawConfigText, subscriptions, nextOrder)
            if (created) {
                addedGroups += group
                added++
            } else {
                kept++
            }
            updateGroups += group
            existingKeys += CONFIG_SOURCE_KEY
            managedKeys += CONFIG_SOURCE_KEY
        }

        val staleKeys = previousManagedKeys
            .filterNot { managedKeyCoveredBySource(it, sourceKeys) }
            .toSet()
        val groupsToRemove = subscriptions.filter {
            groupKey(it) in staleKeys || legacyGroupKey(it) in staleKeys
        }
        if (groupsToRemove.isNotEmpty()) {
            stage("apply/delete") {
                SagerDatabase.groupDao.deleteGroup(groupsToRemove)
                SagerDatabase.proxyDao.deleteByGroup(groupsToRemove.map { it.id }.toLongArray())
            }
        }
        if (CONFIG_SOURCE_KEY in staleKeys) {
            deleteRawConfigSourceFile()
        }

        stage("apply/save") {
            DataStore.groupSourceManagedKeys = encodeManagedKeys(managedKeys)
        }
        stage("apply/notify") {
            for (group in addedGroups) {
                GroupManager.iterator { groupAdd(group) }
            }
            for (group in groupsToRemove) {
                GroupManager.iterator { groupRemoved(group.id) }
            }
        }
        stage("apply/reconfigure") {
            runCatching {
                SubscriptionUpdater.reconfigureUpdater()
            }.onFailure {
                Logs.w(it)
            }
        }

        return SyncSummary(
            added = added,
            kept = kept,
            removed = groupsToRemove.size,
            addedGroups = addedGroups,
            updateGroups = updateGroups.distinctBy { it.id },
        )
    }

    private fun groupKey(group: ProxyGroup): String {
        val link = group.subscription?.link.orEmpty()
        if (isGroupSourceConfigLink(link)) return CONFIG_SOURCE_KEY
        return sourceKey(link)
    }

    private fun legacyGroupKey(group: ProxyGroup): String {
        val link = group.subscription?.link.orEmpty()
        if (isGroupSourceConfigLink(link)) return CONFIG_SOURCE_KEY
        return legacySourceKey(link, group.displayName())
    }

    private fun decodeManagedKeys(value: String): Set<String> {
        return value.lineSequence()
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun encodeManagedKeys(keys: Set<String>): String {
        return keys.sorted().joinToString("\n")
    }

    private fun managedKeyCoveredBySource(key: String, sourceKeys: Set<String>): Boolean {
        if (key in sourceKeys) return true
        val separator = key.indexOf(KEY_SEPARATOR)
        return separator > 0 && key.substring(0, separator) in sourceKeys
    }

    private fun sourceKey(link: String): String {
        return subscriptionAddressKey(link).lowercase(Locale.ROOT)
    }

    private fun legacySourceKey(link: String, name: String): String {
        return sourceKey(link) + KEY_SEPARATOR + name.trim().lowercase(Locale.ROOT)
    }

    private fun normalizeSubscriptionLink(link: String): String {
        val trimmed = link.trim()
        return runCatching {
            trimmed.toUri().buildUpon().fragment(null).build().toString()
        }.getOrElse {
            trimmed.substringBefore("#").trim()
        }
    }

    private fun subscriptionAddressKey(link: String): String {
        val normalized = normalizeSubscriptionLink(link)
        return githubApiContentUrl(normalized)?.let { githubApiUrl ->
            val uri = githubApiUrl.toUri()
            val segments = uri.pathSegments
            if (segments.size >= 5 && segments[0] == "repos" && segments[3] == "contents") {
                val ref = uri.getQueryParameter("ref").orEmpty()
                "github://${segments[1]}/${segments[2]}/$ref/${segments.drop(4).joinToString("/")}"
            } else {
                githubApiUrl
            }
        } ?: runCatching {
            val uri = normalized.toUri()
            uri.buildUpon()
                .fragment(null)
                .clearQuery()
                .apply {
                    uri.queryParameterNames
                        .filter { it != "_exclaved_sync" }
                        .sorted()
                        .forEach { name ->
                            uri.getQueryParameters(name).forEach { value ->
                                appendQueryParameter(name, value)
                            }
                        }
                }
                .build()
                .toString()
        }.getOrElse {
            normalized.substringBefore("#").trim()
        }
    }

    class SyncTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            if (shouldOwnVpnSync()) {
                Logs.d("GroupSourceSyncer skipped periodic sync: VPN sync loop is active")
                return Result.success()
            }
            return try {
                val summary = syncNow()
                updateSyncedGroups(summary)
                Result.success()
            } catch (e: Throwable) {
                Logs.w(e)
                Result.retry()
            }
        }
    }

    class VpnSyncTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            if (!shouldOwnVpnSync()) return Result.success()

            val retryAttempt = inputData.getInt(KEY_RETRY_ATTEMPT, 0).coerceAtLeast(0)
            runCatching {
                val summary = syncNow()
                updateSyncedGroups(summary)
                if (shouldOwnVpnSync()) {
                    scheduleVpnSync(vpnIntervalMinutes(), retryAttempt = 0)
                }
            }.onFailure {
                Logs.w(it)
                if (shouldOwnVpnSync()) {
                    if (retryAttempt < vpnRetryCount()) {
                        scheduleVpnSync(vpnRetryDelayMinutes(), retryAttempt = retryAttempt + 1)
                    } else {
                        scheduleVpnSync(vpnIntervalMinutes(), retryAttempt = 0)
                    }
                }
            }

            return Result.success()
        }
    }

    private suspend fun updateSyncedGroups(summary: SyncSummary) {
        if (summary.updateGroups.isNotEmpty()) {
            val updateSummary = SubscriptionUpdater.updateGroups(
                summary.updateGroups,
                byUser = false,
                forceUpdateWhenDisconnected = true,
            )
            AutoVpnManager.onSubscriptionsUpdated()
            if (updateSummary.errors > 0 || updateSummary.skipped > 0) {
                error("subscription updates failed: errors=${updateSummary.errors}, skipped=${updateSummary.skipped}")
            }
        }
    }
}
