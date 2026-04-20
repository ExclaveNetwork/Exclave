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
import io.nekohasekai.sagernet.SagerNet.Companion.application
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.InMemoryDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.*
import java.io.BufferedReader
import java.io.StringReader
import java.util.Properties

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
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL) { 2 }
    var logLevelDebugWarningDisable by configurationStore.boolean(Key.LOG_LEVEL_DEBUG_WARNING_DISABLE)
    var enableDebug by configurationStore.boolean(Key.ENABLE_DEBUG) { BuildConfig.DEBUG }
    var pprofServer by configurationStore.string(Key.PPROF_SERVER)
    var enablePcap by configurationStore.boolean(Key.ENABLE_PCAP)
    var allowAppsBypassVpn by configurationStore.boolean(Key.ALLOW_APPS_BYPASS_VPN)
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)
    var stunServers by configurationStore.string(Key.STUN_SERVERS)
    var fabStyle by configurationStore.stringToInt(Key.FAB_STYLE) { 1 }
    var useIECUnit by configurationStore.boolean(Key.USE_IEC_UNIT)
    var queryAllPackagesAlternativeMethod by configurationStore.boolean(Key.QUERY_ALL_PACKAGES_ALTERNATIVE_METHOD)

    var enableFragment by configurationStore.boolean(Key.ENABLE_FRAGMENT)
    var enableFragmentForDirect by configurationStore.boolean(Key.ENABLE_FRAGMENT_FOR_DIRECT)
    var fragmentMethod by configurationStore.stringToInt(Key.FRAGMENT_METHOD)
    var realityDisableX25519Mlkem768 by configurationStore.boolean(Key.REALITY_DISABLE_X25519MLKEM768)
    var grpcServiceNameCompat by configurationStore.boolean(Key.GRPC_SERVICE_NAME_COMPAT)
    var profileSecurityAdvisory by configurationStore.boolean(Key.PROFILE_SECURITY_ADVISORY) { true }

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
    var requireHttp by configurationStore.boolean(Key.REQUIRE_HTTP) { false }
    var httpUsername by configurationStore.string(Key.HTTP_USERNAME)
    var httpPassword by configurationStore.string(Key.HTTP_PASSWORD)
    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY) { true }
    var httpProxyException by configurationStore.string(Key.HTTP_PROXY_EXCEPTION)
    var requireTransproxy by configurationStore.boolean(Key.REQUIRE_TRANSPROXY)
    // var transproxyMode by configurationStore.stringToInt(Key.TRANSPROXY_MODE)
    var requireDnsInbound by configurationStore.boolean(Key.REQUIRE_DNS_INBOUND)
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)
    var showGroupName by configurationStore.boolean(Key.SHOW_GROUP_NAME)

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
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 1440 }
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
        }
    }
}