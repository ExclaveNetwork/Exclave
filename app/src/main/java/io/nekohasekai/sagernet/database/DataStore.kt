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

package io.nekohasekai.sagernet.database

import android.content.Intent
import android.os.Binder
import android.os.Build
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.GroupSourceSyncer
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.SagerNet.Companion.application
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.bg.VpnSubscriptionUpdateScheduler
import io.nekohasekai.sagernet.database.preference.InMemoryDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.*
import java.io.BufferedReader
import java.io.StringReader
import java.util.Properties
import java.util.UUID

object DataStore : OnPreferenceDataStoreChangeListener {

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)
    val profileCacheStore = RoomPreferenceDataStore(InMemoryDatabase.kvPairDao)

    fun init() {
        configurationStore.registerChangeListener(this)

        // migrate from 0.14.10
        val ipv6Mode0 = configurationStore.getString("ipv6Mode0")?.toIntOrNull()
        // 0: Disable, 1: Enable, 2: Prefer, 3: Only
        if (configurationStore.getBoolean("enableVPNInterfaceIPv6Address") == null) {
            when (ipv6Mode0) {
                0 -> configurationStore.putBoolean("enableVPNInterfaceIPv6Address", false)
                1, 2, 3 -> configurationStore.putBoolean("enableVPNInterfaceIPv6Address", true)
            }
        }
        if (configurationStore.getBoolean("resolveDestination") == true
            && configurationStore.getString("outboundDomainStrategy") == null) {
            when (ipv6Mode0) {
                0 -> configurationStore.putString("outboundDomainStrategy", "UseIPv4")
                1 -> configurationStore.putString("outboundDomainStrategy", "PreferIPv4")
                2 -> configurationStore.putString("outboundDomainStrategy", "PreferIPv6")
                3 -> configurationStore.putString("outboundDomainStrategy", "UseIPv6")
            }
        }
        if (configurationStore.getBoolean("resolveDestinationForDirect") == true
            && configurationStore.getString("outboundDomainStrategyForDirect") == null) {
            when (ipv6Mode0) {
                0 -> configurationStore.putString("outboundDomainStrategyForDirect", "UseIPv4")
                1 -> configurationStore.putString("outboundDomainStrategyForDirect", "PreferIPv4")
                2 -> configurationStore.putString("outboundDomainStrategyForDirect", "PreferIPv6")
                3 -> configurationStore.putString("outboundDomainStrategyForDirect", "UseIPv6")
            }
        }
        // migrate from 0.14.14
        val outboundDomainStrategy = configurationStore.getString("outboundDomainStrategy")
        if (outboundDomainStrategy != null && outboundDomainStrategy != "AsIs"
            && configurationStore.getString("outboundDomainStrategyForServer") == null) {
            configurationStore.putString("outboundDomainStrategyForServer", outboundDomainStrategy)
        }
    }

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)
    var startedProfile by configurationStore.long(Key.PROFILE_STARTED)

    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) {
        SagerNet.currentProfile?.groupId ?: 0L
    }
    var disabledGroups by configurationStore.stringSet(Key.PROFILE_DISABLED_GROUPS)
    var allGroupOrder by configurationStore.int(Key.PROFILE_ALL_GROUP_ORDER) { GroupOrder.BY_DELAY }

    fun currentGroupId(): Long {
        val currentSelected = selectedGroup
        if (currentSelected > 0L) return currentSelected
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = selectedGroup
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        return groups.find { it.type == GroupType.BASIC }!!.id
    }

    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var domainStrategy by configurationStore.string(Key.DOMAIN_STRATEGY) { "AsIs" }
    var trafficSniffing by configurationStore.boolean(Key.TRAFFIC_SNIFFING) { true }
    var destinationOverride by configurationStore.boolean(Key.DESTINATION_OVERRIDE)
    var outboundDomainStrategy by configurationStore.string(Key.OUTBOUND_DOMAIN_STRATEGY) { "AsIs" }
    var outboundDomainStrategyForDirect by configurationStore.string(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_DIRECT) { "AsIs" }

    var outboundDomainStrategyForServer by configurationStore.string(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_SERVER) { "AsIs" }
    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN) { true }

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL)

    var remoteDns by configurationStore.stringNotBlack(Key.REMOTE_DNS) { "tcp://1.1.1.1" }
    var directDns by configurationStore.stringNotBlack(Key.DIRECT_DNS) { "tcp://223.5.5.5" }
    var bootstrapDns by configurationStore.stringNotBlack(Key.BOOTSTRAP_DNS)
    var useLocalDnsAsDirectDns by configurationStore.boolean(Key.USE_LOCAL_DNS_AS_DIRECT_DNS) { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }
    var useLocalDnsAsBootstrapDns by configurationStore.boolean(Key.USE_LOCAL_DNS_AS_BOOTSTRAP_DNS) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS)
    var hijackDns by configurationStore.boolean(Key.HIJACK_DNS)
    var hosts by configurationStore.string(Key.DNS_HOSTS)
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var remoteDnsQueryStrategy by configurationStore.string(Key.REMOTE_DNS_QUERY_STRATEGY) { "UseIP" }
    var directDnsQueryStrategy by configurationStore.string(Key.DIRECT_DNS_QUERY_STRATEGY) { "UseIP" }
    var ednsClientIp by configurationStore.string(Key.EDNS_CLIENT_IP)

    var routeMode by configurationStore.stringToInt(Key.ROUTE_MODE)
    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER)
    var rulesGeositeUrl by configurationStore.string(Key.RULES_GEOSITE_URL) { "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat" }
    var rulesGeoipUrl by configurationStore.string(Key.RULES_GEOIP_URL) { "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat" }
    var routeAssetsInitialUpdateDone by configurationStore.boolean(Key.ROUTE_ASSETS_INITIAL_UPDATE_DONE)
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL) { 2 }
    var logLevelDebugWarningDisable by configurationStore.boolean(Key.LOG_LEVEL_DEBUG_WARNING_DISABLE)
    var enableDebug by configurationStore.boolean(Key.ENABLE_DEBUG) { BuildConfig.DEBUG }
    var pprofServer by configurationStore.string(Key.PPROF_SERVER)
    var enablePcap by configurationStore.boolean(Key.ENABLE_PCAP)
    var allowAppsBypassVpn by configurationStore.boolean(Key.ALLOW_APPS_BYPASS_VPN)
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK) { true }
    var stunServers by configurationStore.string(Key.STUN_SERVERS)
    var fabStyle by configurationStore.stringToInt(Key.FAB_STYLE) { 1 }
    var useIECUnit by configurationStore.boolean(Key.USE_IEC_UNIT)
    var queryAllPackagesAlternativeMethod by configurationStore.boolean(Key.QUERY_ALL_PACKAGES_ALTERNATIVE_METHOD)

    var enableFragment by configurationStore.boolean(Key.ENABLE_FRAGMENT)
    var enableFragmentForDirect by configurationStore.boolean(Key.ENABLE_FRAGMENT_FOR_DIRECT)
    var fragmentMethod by configurationStore.stringToInt(Key.FRAGMENT_METHOD)
    var realityDisableX25519Mlkem768 by configurationStore.boolean(Key.REALITY_DISABLE_X25519MLKEM768)
    var hysteria2OmitMaxDatagramFrameSize by configurationStore.boolean(Key.HYSTERIA2_OMIT_MAX_DATAGRAM_FRAME_SIZE)
    var grpcServiceNameCompat by configurationStore.boolean(Key.GRPC_SERVICE_NAME_COMPAT)
    var profileSecurityAdvisory by configurationStore.boolean(Key.PROFILE_SECURITY_ADVISORY)

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }
    var socksPort: Int
        get() = getLocalPort(Key.SOCKS_PORT, 2080)
        set(value) = saveLocalPort(Key.SOCKS_PORT, value)
    var localDNSPort: Int
        get() = getLocalPort(Key.LOCAL_DNS_PORT, 6450)
        set(value) {
            saveLocalPort(Key.LOCAL_DNS_PORT, value)
        }
    var httpPort: Int
        get() = getLocalPort(Key.HTTP_PORT, 9080)
        set(value) = saveLocalPort(Key.HTTP_PORT, value)
    var transproxyPort: Int
        get() = getLocalPort(Key.TRANSPROXY_PORT, 9200)
        set(value) = saveLocalPort(Key.TRANSPROXY_PORT, value)

    fun initGlobal() {
        if (configurationStore.getString(Key.SOCKS_PORT) == null) {
            socksPort = socksPort
        }
        if (configurationStore.getString(Key.LOCAL_DNS_PORT) == null) {
            localDNSPort = localDNSPort
        }
        if (configurationStore.getString(Key.HTTP_PORT) == null) {
            httpPort = httpPort
        }
        if (configurationStore.getString(Key.TRANSPROXY_PORT) == null) {
            transproxyPort = transproxyPort
        }
        if (configurationStore.getString(Key.DNS_HOSTS) == null) {
            hosts = hosts
        }
        if (configurationStore.getString(Key.REMOTE_DNS).isNullOrBlank()) {
            remoteDns = remoteDns
        }
        if (configurationStore.getString(Key.DIRECT_DNS).isNullOrBlank()) {
            directDns = directDns
        }
        if (configurationStore.getString(Key.MTU).isNullOrBlank()) {
            mtu = mtu
        }
    }


    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var enableVPNInterfaceIPv6Address by configurationStore.boolean(Key.ENABLE_VPN_INTERFACE_IPV6_ADDRESS)

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED)

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT)

    var requireSocks by configurationStore.boolean(Key.REQUIRE_SOCKS) { true }
    var socksUsername by configurationStore.string(Key.SOCKS_USERNAME)
    var socksPassword by configurationStore.string(Key.SOCKS_PASSWORD)
    var socksUDP by configurationStore.boolean(Key.SOCKS_UDP) { true }
    var socksUDPWarningDisable by configurationStore.boolean(Key.SOCKS_UDP_WARNING_DISABLE)
    var requireHttp by configurationStore.boolean(Key.REQUIRE_HTTP) { false }
    var httpUsername by configurationStore.string(Key.HTTP_USERNAME)
    var httpPassword by configurationStore.string(Key.HTTP_PASSWORD)
    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY) { true }
    var httpProxyException by configurationStore.string(Key.HTTP_PROXY_EXCEPTION)
    var requireTransproxy by configurationStore.boolean(Key.REQUIRE_TRANSPROXY)
    // var transproxyMode by configurationStore.stringToInt(Key.TRANSPROXY_MODE)
    var requireDnsInbound by configurationStore.boolean(Key.REQUIRE_DNS_INBOUND)
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestTimeoutSeconds by configurationStore.stringToInt(Key.CONNECTION_TEST_TIMEOUT_SECONDS) { 4 }
    var connectionTestParallel by configurationStore.stringToInt(Key.CONNECTION_TEST_PARALLEL) { 25 }
    var connectionTestQuickFailureCount by configurationStore.stringToInt(Key.CONNECTION_TEST_QUICK_FAILURE_COUNT) { 20 }
    var connectionTestQuickFailureMillis by configurationStore.stringToInt(Key.CONNECTION_TEST_QUICK_FAILURE_MILLIS) { 1000 }
    var connectionTestQuickFailurePauseSeconds by configurationStore.stringToInt(Key.CONNECTION_TEST_QUICK_FAILURE_PAUSE_SECONDS) { 7 }
    var connectionTestBreakAfterCount by configurationStore.stringToInt(Key.CONNECTION_TEST_BREAK_AFTER_COUNT) { 25 }
    var connectionTestBreakSeconds by configurationStore.stringToInt(Key.CONNECTION_TEST_BREAK_SECONDS) { 5 }
    var connectionTestProgressIntervalSeconds by configurationStore.stringToInt(Key.CONNECTION_TEST_PROGRESS_INTERVAL_SECONDS) { 5 }
    var connectionTestSortIntervalSeconds by configurationStore.stringToInt(Key.CONNECTION_TEST_SORT_INTERVAL_SECONDS) { 10 }
    var manualGeminiTestFilterEnabled by configurationStore.boolean(Key.MANUAL_GEMINI_TEST_FILTER_ENABLED)
    var autoVpnEnabled by configurationStore.boolean(Key.AUTO_VPN_ENABLED)
    var autoVpnPrecheckEnabled by configurationStore.boolean(Key.AUTO_VPN_PRECHECK_ENABLED) { true }
    var autoVpnMode by configurationStore.int(Key.AUTO_VPN_MODE)
    var autoVpnCheckIntervalSeconds by configurationStore.stringToInt(Key.AUTO_VPN_CHECK_INTERVAL_SECONDS) { 120 }
    var autoVpnBadPingMillis by configurationStore.stringToInt(Key.AUTO_VPN_BAD_PING_MILLIS) { 3000 }
    var autoVpnFailuresBeforeSwitch by configurationStore.stringToInt(Key.AUTO_VPN_FAILURES_BEFORE_SWITCH) { 2 }
    var autoVpnCandidateLimit by configurationStore.stringToInt(Key.AUTO_VPN_CANDIDATE_LIMIT) { 40 }
    var autoVpnWorkingTarget by configurationStore.stringToInt(Key.AUTO_VPN_WORKING_TARGET) { 10 }
    var autoVpnInitialSelectionTimeoutSeconds by configurationStore.stringToInt(Key.AUTO_VPN_INITIAL_SELECTION_TIMEOUT_SECONDS) { 60 }
    var autoVpnLiteMode by configurationStore.boolean(Key.AUTO_VPN_LITE_MODE)
    var autoVpnSwitchCooldownSeconds by configurationStore.stringToInt(Key.AUTO_VPN_SWITCH_COOLDOWN_SECONDS) { 60 }
    var autoVpnQuarantineMinutes by configurationStore.stringToInt(Key.AUTO_VPN_QUARANTINE_MINUTES) { 45 }
    var autoVpnGeminiTestUrl by configurationStore.string(Key.AUTO_VPN_GEMINI_TEST_URL) { "https://gemini.google.com/" }
    var autoVpnGeminiRejectedKeys by configurationStore.stringSet(Key.AUTO_VPN_GEMINI_REJECTED_KEYS)
    var autoVpnGeminiRejectedNames by configurationStore.string(Key.AUTO_VPN_GEMINI_REJECTED_NAMES)
    var autoVpnGeminiRejectedRevision by configurationStore.long(Key.AUTO_VPN_GEMINI_REJECTED_REVISION)
    var autoVpnProfileScoreStats by configurationStore.string(Key.AUTO_VPN_PROFILE_SCORE_STATS)
    var autoVpnStatusText by configurationStore.string(Key.AUTO_VPN_STATUS_TEXT)
    var autoVpnDetailStatusText by configurationStore.string(Key.AUTO_VPN_DETAIL_STATUS_TEXT)
    var autoVpnNotificationText by configurationStore.string(Key.AUTO_VPN_NOTIFICATION_TEXT)
    var autoVpnProgressRunning by configurationStore.boolean(Key.AUTO_VPN_PROGRESS_RUNNING)
    var autoVpnProgressTotal by configurationStore.int(Key.AUTO_VPN_PROGRESS_TOTAL)
    var autoVpnProgressFinished by configurationStore.int(Key.AUTO_VPN_PROGRESS_FINISHED)
    var autoVpnProgressWorking by configurationStore.int(Key.AUTO_VPN_PROGRESS_WORKING)
    var autoVpnProgressSnapshot by configurationStore.string(Key.AUTO_VPN_PROGRESS_SNAPSHOT)
    var autoVpnProgressGeneration by configurationStore.long(Key.AUTO_VPN_PROGRESS_GENERATION)
    var autoVpnLastPingMillis by configurationStore.int(Key.AUTO_VPN_LAST_PING_MILLIS)
    var autoVpnLastPingFailed by configurationStore.boolean(Key.AUTO_VPN_LAST_PING_FAILED)
    var autoVpnResultsResetGeneration by configurationStore.int(Key.AUTO_VPN_RESULTS_RESET_GENERATION)
    var autoVpnStatusUpdatedAt by configurationStore.long(Key.AUTO_VPN_STATUS_UPDATED_AT)
    var autoVpnReserveCountAny by configurationStore.int(Key.AUTO_VPN_RESERVE_COUNT_ANY)
    var autoVpnReserveCountNonRu by configurationStore.int(Key.AUTO_VPN_RESERVE_COUNT_NON_RU)
    var autoVpnReserveCountGemini by configurationStore.int(Key.AUTO_VPN_RESERVE_COUNT_GEMINI)
    var autoVpnReserveIdsAny by configurationStore.string(Key.AUTO_VPN_RESERVE_IDS_ANY)
    var autoVpnReserveIdsNonRu by configurationStore.string(Key.AUTO_VPN_RESERVE_IDS_NON_RU)
    var autoVpnReserveIdsGemini by configurationStore.string(Key.AUTO_VPN_RESERVE_IDS_GEMINI)
    var autoVpnScannedIdsAny by configurationStore.string(Key.AUTO_VPN_SCANNED_IDS_ANY)
    var autoVpnScannedIdsNonRu by configurationStore.string(Key.AUTO_VPN_SCANNED_IDS_NON_RU)
    var autoVpnScannedIdsGemini by configurationStore.string(Key.AUTO_VPN_SCANNED_IDS_GEMINI)
    var autoVpnServiceStartRequested by configurationStore.boolean(Key.AUTO_VPN_SERVICE_START_REQUESTED)
    var autoVpnDirectSearchToken by configurationStore.long(Key.AUTO_VPN_DIRECT_SEARCH_TOKEN)
    var autoVpnDirectSearchHeartbeat by configurationStore.long(Key.AUTO_VPN_DIRECT_SEARCH_HEARTBEAT)
    var autoVpnFloatingSwitchEnabled by configurationStore.boolean(Key.AUTO_VPN_FLOATING_SWITCH_ENABLED)
    var autoVpnFloatingSwitchX by configurationStore.int(Key.AUTO_VPN_FLOATING_SWITCH_X) { -1 }
    var autoVpnFloatingSwitchY by configurationStore.int(Key.AUTO_VPN_FLOATING_SWITCH_Y) { -1 }
    var subscriptionVpnBackgroundUpdate by configurationStore.boolean(Key.SUBSCRIPTION_VPN_BACKGROUND_UPDATE) { true }
    var subscriptionVpnInitialDelayMinutes by configurationStore.stringToInt(Key.SUBSCRIPTION_VPN_INITIAL_DELAY_MINUTES) { 5 }
    var subscriptionVpnIntervalMinutes by configurationStore.stringToInt(Key.SUBSCRIPTION_VPN_INTERVAL_MINUTES) { 90 }
    var subscriptionVpnRetryIntervalMinutes by configurationStore.stringToInt(Key.SUBSCRIPTION_VPN_RETRY_INTERVAL_MINUTES) { 15 }
    var subscriptionVpnRetryCount by configurationStore.stringToInt(Key.SUBSCRIPTION_VPN_RETRY_COUNT) { 3 }
    var subscriptionSendHwid by configurationStore.boolean(Key.SUBSCRIPTION_SEND_HWID) { true }
    private var subscriptionHwidValue by configurationStore.string(Key.SUBSCRIPTION_HWID)
    var groupHealthCheckStatsEnabled by configurationStore.boolean(Key.GROUP_HEALTH_CHECK_STATS_ENABLED)
    var groupSourceSyncEnabled by configurationStore.boolean(Key.GROUP_SOURCE_SYNC_ENABLED) { true }
    var groupSourceVpnSyncEnabled by configurationStore.boolean(Key.GROUP_SOURCE_VPN_SYNC_ENABLED) { true }
    var groupSourceVpnInitialDelayMinutes by configurationStore.stringToInt(Key.GROUP_SOURCE_VPN_INITIAL_DELAY_MINUTES) { 5 }
    var groupSourceVpnIntervalMinutes by configurationStore.stringToInt(Key.GROUP_SOURCE_VPN_INTERVAL_MINUTES) { 60 }
    var groupSourceVpnRetryIntervalMinutes by configurationStore.stringToInt(Key.GROUP_SOURCE_VPN_RETRY_INTERVAL_MINUTES) { 15 }
    var groupSourceVpnRetryCount by configurationStore.stringToInt(Key.GROUP_SOURCE_VPN_RETRY_COUNT) { 3 }
    var groupSourceUrl by configurationStore.string(Key.GROUP_SOURCE_URL) {
        "https://github.com/denzikplay48-dot/Sub-ExclaveD/blob/main/Sub"
    }
    var groupSourceManagedKeys by configurationStore.string(Key.GROUP_SOURCE_MANAGED_KEYS)
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)
    var showGroupName by configurationStore.boolean(Key.SHOW_GROUP_NAME) { true }
    var subscriptionGlobalAutoUpdate by configurationStore.boolean(Key.SUBSCRIPTION_GLOBAL_AUTO_UPDATE) { true }
    var subscriptionGlobalAutoUpdateDelay by configurationStore.stringToInt(Key.SUBSCRIPTION_GLOBAL_AUTO_UPDATE_DELAY) { 90 }
    var groupSourceSyncIntervalMinutes by configurationStore.stringToInt(Key.GROUP_SOURCE_SYNC_INTERVAL_MINUTES) { 60 }
    var subscriptionRemoveDuplicates by configurationStore.boolean(Key.SUBSCRIPTION_REMOVE_DUPLICATES) { true }
    var subscriptionAutoSortGroups by configurationStore.boolean(Key.SUBSCRIPTION_AUTO_SORT_GROUPS) { true }
    var subscriptionUpdateParallel by configurationStore.stringToInt(Key.SUBSCRIPTION_UPDATE_PARALLEL) { 60 }
    var subscriptionUpdateTimeoutSeconds by configurationStore.stringToInt(Key.SUBSCRIPTION_UPDATE_TIMEOUT_SECONDS) { 30 }
    var profileTestPendingIds by configurationStore.string(Key.PROFILE_TEST_PENDING_IDS)
    var profileTestTotal by configurationStore.int(Key.PROFILE_TEST_TOTAL)
    var profileTestFinished by configurationStore.int(Key.PROFILE_TEST_FINISHED)
    var profileTestWorking by configurationStore.int(Key.PROFILE_TEST_WORKING)
    var profileTestRunning by configurationStore.boolean(Key.PROFILE_TEST_RUNNING)
    var profileTestRefreshDeferred by configurationStore.boolean(Key.PROFILE_TEST_REFRESH_DEFERRED)
    var profileTestResumeAvailable by configurationStore.boolean(Key.PROFILE_TEST_RESUME_AVAILABLE)
    var profileTestUpdatedAt by configurationStore.long(Key.PROFILE_TEST_UPDATED_AT)
    var profileTestRunId by configurationStore.long(Key.PROFILE_TEST_RUN_ID)
    var profileTestUrl by configurationStore.string(Key.PROFILE_TEST_URL)
    var profileTestGeminiFilter by configurationStore.boolean(Key.PROFILE_TEST_GEMINI_FILTER)

    fun getSubscriptionHwid(): String {
        val current = subscriptionHwidValue.trim()
        if (current.isNotEmpty()) return current

        val generated = UUID.randomUUID().toString().replace("-", "")
        subscriptionHwidValue = generated
        return generated
    }

    fun isGroupEnabled(groupId: Long): Boolean {
        return groupId.toString() !in disabledGroups
    }

    fun setGroupEnabled(groupId: Long, enabled: Boolean) {
        val ids = disabledGroups.toMutableSet()
        if (enabled) {
            ids.remove(groupId.toString())
        } else {
            ids.add(groupId.toString())
        }
        disabledGroups = ids
    }

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }

    var mtu by configurationStore.stringToInt(Key.MTU) { VpnService.DEFAULT_MTU }

    var discardICMP by configurationStore.boolean(Key.DISCARD_ICMP)

    var appTrafficStatistics by configurationStore.boolean(Key.APP_TRAFFIC_STATISTICS)
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    // protocol

    var shadowsocks2022Implementation by configurationStore.stringToInt(Key.SHADOWSOCKS_2022_IMPLEMENTATION)
    var providerRootCA by configurationStore.stringToInt(Key.PROVIDER_ROOT_CA) { 1 }
    var interruptReusedConnections by configurationStore.boolean(Key.INTERRUPT_REUSED_CONNECTIONS) { true }

    // cache

    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)
    var serverPlugin by profileCacheStore.string(Key.SERVER_PLUGIN)

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverProtocolParam by profileCacheStore.string(Key.SERVER_PROTOCOL_PARAM)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)
    var serverObfsParam by profileCacheStore.string(Key.SERVER_OBFS_PARAM)

    var serverUserId by profileCacheStore.string(Key.SERVER_USER_ID)
    var serverAlterId by profileCacheStore.stringToInt(Key.SERVER_ALTER_ID)
    var serverSecurity by profileCacheStore.string(Key.SERVER_SECURITY)
    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHeader by profileCacheStore.string(Key.SERVER_HEADER)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverEncryptionForVless by profileCacheStore.string(Key.SERVER_ENCRYPTION_FOR_VLESS)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverPinnedCertificateChain by profileCacheStore.string(Key.SERVER_PINNED_CERTIFICATE_CHAIN)
    var serverPinnedCertificatePublicKey by profileCacheStore.string(Key.SERVER_PINNED_CERTIFICATE_PUBLIC_KEY)
    var serverPinnedCertificate by profileCacheStore.string(Key.SERVER_PINNED_CERTIFICATE)
    var serverMtlsCertificate by profileCacheStore.string(Key.SERVER_MTLS_CERTIFICATE)
    var serverMtlsCertificatePrivateKey by profileCacheStore.string(Key.SERVER_MTLS_CERTIFICATE_PRIVATE_KEY)
    var serverFlow by profileCacheStore.string(Key.SERVER_FLOW)
    var serverQuicSecurity by profileCacheStore.string(Key.SERVER_QUIC_SECURITY)
    var serverWsMaxEarlyData by profileCacheStore.stringToInt(Key.SERVER_WS_MAX_EARLY_DATA)
    var serverSplithttpMode by profileCacheStore.string(Key.SERVER_SPLITHTTP_MODE)
    var serverSplithttpExtra by profileCacheStore.string(Key.SERVER_SPLITHTTP_EXTRA)
    var serverWsBrowserForwarding by profileCacheStore.boolean(Key.SERVER_WS_BROWSER_FORWARDING)
    var serverShBrowserForwarding by profileCacheStore.boolean(Key.SERVER_SH_BROWSER_FORWARDING)
    var serverEarlyDataHeaderName by profileCacheStore.string(Key.SERVER_EARLY_DATA_HEADER_NAME)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)
    var serverPacketEncoding by profileCacheStore.string(Key.SERVER_PACKET_ENCODING)
    var serverUTLSFingerprint by profileCacheStore.string(Key.SERVER_UTLS_FINGERPRINT)
    var serverEchEnabled by profileCacheStore.boolean(Key.SERVER_ECH_ENABLED)
    var serverEchConfig by profileCacheStore.string(Key.SERVER_ECH_CONFIG)
    var serverRealityPublicKey by profileCacheStore.string(Key.SERVER_REALITY_PUBLIC_KEY)
    var serverRealityShortId by profileCacheStore.string(Key.SERVER_REALITY_SHORT_ID)
    var serverRealityMldsa65Verify by profileCacheStore.string(Key.SERVER_REALITY_MLDSA65_VERIFY)
    var serverRealityFingerprint by profileCacheStore.string(Key.SERVER_REALITY_FINGERPRINT)
    var serverRealityDisableX25519Mlkem768 by profileCacheStore.boolean(Key.SERVER_REALITY_DISABLE_X25519MLKEM768)
    var serverGrpcServiceNameCompat by profileCacheStore.boolean(Key.SERVER_GRPC_SERVICE_NAME_COMPAT)
    var serverGrpcMultiMode by profileCacheStore.boolean(Key.SERVER_GRPC_MULTI_MODE)
    var serverMekyaKcpSeed by profileCacheStore.string(Key.SERVER_MEKYA_KCP_SEED)
    var serverMekyaKcpHeaderType by profileCacheStore.string(Key.SERVER_MEKYA_KCP_HEADER_TYPE)
    var serverMekyaUrl by profileCacheStore.string(Key.SERVER_MEKYA_URL)

    var serverPorts by profileCacheStore.string(Key.SERVER_PORTS)
    var serverHopInterval by profileCacheStore.stringToLong(Key.SERVER_HOP_INTERVAL)
    var serverHopIntervalMin by profileCacheStore.stringToLong(Key.SERVER_HOP_INTERVAL_MIN)
    var serverHopIntervalMax by profileCacheStore.stringToLong(Key.SERVER_HOP_INTERVAL_MAX)
    var serverHysteria2BBRProfile by profileCacheStore.string(Key.SERVER_HYSTERIA2_BBR_PROFILE)

    var serverVMessExperimentalAuthenticatedLength by profileCacheStore.boolean(Key.SERVER_VMESS_EXPERIMENTAL_AUTHENTICATED_LENGTH)
    var serverVMessExperimentalNoTerminationSignal by profileCacheStore.boolean(Key.SERVER_VMESS_EXPERIMENTAL_NO_TERMINATION_SIGNAL)

    var serverMux by profileCacheStore.boolean(Key.SERVER_MUX)
    var serverMuxConcurrency by profileCacheStore.stringToInt(Key.SERVER_MUX_CONCURRENCY) { 8 }
    var serverMuxPacketEncoding by profileCacheStore.string(Key.SERVER_MUX_PACKET_ENCODING)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToLong(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToLong(Key.SERVER_DOWNLOAD_SPEED)
    var serverProtocolVersion by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverLocalAddress by profileCacheStore.string(Key.SERVER_LOCAL_ADDRESS)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverReducedIvHeadEntropy by profileCacheStore.boolean(Key.SERVER_REDUCED_IV_HEAD_ENTROPY)
    var serverBrookUdpOverStream by profileCacheStore.boolean(Key.SERVER_BROOK_UDP_OVER_STREAM)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var serverShadowTLSProtocolVersion by profileCacheStore.stringToInt(Key.SERVER_SHADOWTLS_PROTOCOL_VERSION) { 2 }
    var serverMieruMuxLevel by profileCacheStore.stringToInt(Key.SERVER_MIERU_MUX_LEVEL)
    var serverMieruHandshakeMode by profileCacheStore.stringToInt(Key.SERVER_MIERU_HANDSHAKE_MODE)
    var serverMieruTrafficPattern by profileCacheStore.string(Key.SERVER_MIERU_TRAFFIC_PATTERN)
    var serverWireGuardReserved by profileCacheStore.string(Key.SERVER_WIREGUARD_RESERVED)
    var serverWireGuardKeepaliveInterval by profileCacheStore.stringToInt(Key.SERVER_WIREGUARD_KEEPALIVE_INTERVAL)
    var serverAnyTLSIdleSessionCheckInterval by profileCacheStore.stringToInt(Key.SERVER_ANYTLS_IDLE_SESSION_CHECK_INTERVAL) { 30 }
    var serverAnyTLSIdleSessionTimeout by profileCacheStore.stringToInt(Key.SERVER_ANYTLS_IDLE_SESSION_TIMEOUT) { 30 }
    var serverAnyTLSMinIdleSession by profileCacheStore.stringToInt(Key.SERVER_ANYTLS_MIN_IDLE_SESSION)
    var serverTrustTunnelServerNameToVerify by profileCacheStore.string(Key.SERVER_TRUSTTUNNEL_SERVER_NAME_TO_VERIFY)
    var serverHysteria2OmitMaxDatagramFrameSize by profileCacheStore.boolean(Key.SERVER_HYSTERIA2_OMIT_MAX_DATAGRAM_FRAME_SIZE)
    var serverSSHKeepaliveInterval by profileCacheStore.stringToInt(Key.SERVER_SSH_KEEPALIVE_INTERVAL)

    var serverNaiveNoPostQuantum by profileCacheStore.boolean(Key.SERVER_NAIVE_NO_POST_QUANTUM)
    var serverShadowQUICDisableALPN by profileCacheStore.boolean(Key.SERVER_SHADOWQUIC_DISABLE_ALPN)
    var serverShadowQUICUseSunnyQUIC by profileCacheStore.boolean(Key.SERVER_SHADOWQUIC_USE_SUNNYQUIC)
    var serverSingUot by profileCacheStore.boolean(Key.SERVER_SING_UOT)
    var serverSingMux by profileCacheStore.boolean(Key.SERVER_SING_MUX)
    var serverSingMuxProtocol by profileCacheStore.string(Key.SERVER_SING_MUX_PROTOCOL)
    var serverSingMuxMaxConnections by profileCacheStore.stringToInt(Key.SERVER_SING_MUX_MAX_CONNECTIONS)
    var serverSingMuxMinStreams by profileCacheStore.stringToInt(Key.SERVER_SING_MUX_MIN_STREAMS)
    var serverSingMuxMaxStreams by profileCacheStore.stringToInt(Key.SERVER_SING_MUX_MAX_STREAMS)
    var serverSingMuxPadding by profileCacheStore.boolean(Key.SERVER_SING_MUX_PADDING)

    var experimentalFlags by configurationStore.string(Key.EXPERIMENTAL_FLAGS)

    var balancerType by profileCacheStore.stringToInt(Key.BALANCER_TYPE)
    var balancerGroup by profileCacheStore.stringToLong(Key.BALANCER_GROUP)
    var balancerStrategy by profileCacheStore.string(Key.BALANCER_STRATEGY)
    var balancerProbeUrl by profileCacheStore.string(Key.PROBE_URL)
    var balancerProbeInterval by profileCacheStore.stringToInt(Key.PROBE_INTERVAL) { 300 }
    var balancerNameFilter by profileCacheStore.string(Key.BALANCER_NAME_FILTER)
    var balancerNameFilter1 by profileCacheStore.string(Key.BALANCER_NAME_FILTER1)
    var balancerUseLandingProxy by profileCacheStore.boolean(Key.BALANCER_USE_LANDING_PROXY)
    var balancerUseFrontProxy by profileCacheStore.boolean(Key.BALANCER_USE_FRONT_PROXY)


    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeAttrs by profileCacheStore.string(Key.ROUTE_ATTRS)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND_RULE)
    var routeReverse by profileCacheStore.boolean(Key.ROUTE_REVERSE)
    var routeRedirect by profileCacheStore.string(Key.ROUTE_REDIRECT)
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)
    var routeCustomPackageNameOrUid by profileCacheStore.string(Key.ROUTE_CUSTOM_PACKAGE_NAME_OR_UID)
    var routeNetworkType by profileCacheStore.stringSet(Key.ROUTE_NETWORK_TYPE)
    var routeSSID by profileCacheStore.string(Key.ROUTE_SSID)

    var frontProxyOutbound by profileCacheStore.long(Key.GROUP_FRONT_PROXY_OUTBOUND)
    var landingProxyOutbound by profileCacheStore.long(Key.GROUP_LANDING_PROXY_OUTBOUND)
    var frontProxy by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxy by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)

    var subscriptionType by profileCacheStore.stringToInt(Key.SUBSCRIPTION_TYPE)
    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 200 }
    var subscriptionLastUpdated by profileCacheStore.long(Key.SUBSCRIPTION_LAST_UPDATED)
    var subscriptionBytesUsed by profileCacheStore.long(Key.SUBSCRIPTION_BYTES_USED)
    var subscriptionBytesRemaining by profileCacheStore.long(Key.SUBSCRIPTION_BYTES_REMAINING)
    var subscriptionExpiryDate by profileCacheStore.long(Key.SUBSCRIPTION_EXPIRY_DATE)
    var subscriptionNameFilter by profileCacheStore.string(Key.SUBSCRIPTION_NAME_FILTER)
    var subscriptionNameFilter1 by profileCacheStore.string(Key.SUBSCRIPTION_NAME_FILTER1)

    var editingAssetName by profileCacheStore.string(Key.EDITING_ASSET_NAME)
    var assetName by profileCacheStore.string(Key.ASSET_NAME)
    var assetUrl by profileCacheStore.string(Key.ASSET_URL)

    var taskerAction by profileCacheStore.stringToInt(Key.TASKER_ACTION)
    var taskerProfile by profileCacheStore.stringToInt(Key.TASKER_PROFILE)
    var taskerProfileId by profileCacheStore.long(Key.TASKER_PROFILE_ID) { -1L }

    var rulesFirstCreate by configurationStore.boolean(Key.RULES_FIRST_CREATE)
    var doNotShowRuleExportWarning by configurationStore.boolean(Key.DO_NOT_SHOW_RULE_EXPORT_WARNING)

    var experimentalFlagsProperties = Properties().apply {
        load(BufferedReader(StringReader(experimentalFlags)))
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.PROFILE_ID -> {}
            Key.APP_THEME -> {
                application.sendBroadcast(Intent(Action.THEME_CHANGED).setPackage(application.packageName))
            }
            Key.EXPERIMENTAL_FLAGS -> {
                experimentalFlagsProperties = Properties().apply {
                    load(BufferedReader(StringReader(experimentalFlags)))
                }
            }
            Key.SUBSCRIPTION_GLOBAL_AUTO_UPDATE,
            Key.SUBSCRIPTION_GLOBAL_AUTO_UPDATE_DELAY -> {
                runOnDefaultDispatcher {
                    SubscriptionUpdater.reconfigureUpdater()
                    VpnSubscriptionUpdateScheduler.reconfigureForState()
                }
            }
            Key.SUBSCRIPTION_VPN_BACKGROUND_UPDATE,
            Key.SUBSCRIPTION_VPN_INITIAL_DELAY_MINUTES,
            Key.SUBSCRIPTION_VPN_INTERVAL_MINUTES,
            Key.SUBSCRIPTION_VPN_RETRY_INTERVAL_MINUTES,
            Key.SUBSCRIPTION_VPN_RETRY_COUNT -> {
                runOnDefaultDispatcher {
                    VpnSubscriptionUpdateScheduler.reconfigureForState()
                }
            }
            Key.GROUP_SOURCE_SYNC_ENABLED,
            Key.GROUP_SOURCE_URL,
            Key.GROUP_SOURCE_SYNC_INTERVAL_MINUTES -> {
                runOnDefaultDispatcher {
                    GroupSourceSyncer.reconfigureSync()
                }
            }
            Key.GROUP_SOURCE_VPN_SYNC_ENABLED,
            Key.GROUP_SOURCE_VPN_INITIAL_DELAY_MINUTES,
            Key.GROUP_SOURCE_VPN_INTERVAL_MINUTES,
            Key.GROUP_SOURCE_VPN_RETRY_INTERVAL_MINUTES,
            Key.GROUP_SOURCE_VPN_RETRY_COUNT -> {
                runOnDefaultDispatcher {
                    GroupSourceSyncer.reconfigureVpnSyncForState()
                }
            }
        }
    }
}
