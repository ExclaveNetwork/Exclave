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

package io.nekohasekai.sagernet

const val CONNECTION_TEST_URL = "https://www.google.com/generate_204"

object Key {

    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val PERSIST_ACROSS_REBOOT = "isAutoConnect"
    const val APP_THEME = "appTheme"
    const val NIGHT_THEME = "nightTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val REMOTE_DNS = "remoteDns"
    const val DIRECT_DNS = "directDns"
    const val BOOTSTRAP_DNS = "bootstrapDns"
    const val USE_LOCAL_DNS_AS_DIRECT_DNS = "useLocalDnsAsDirectDns"
    const val USE_LOCAL_DNS_AS_BOOTSTRAP_DNS = "useLocalDnsAsBootstrapDns"
    const val ENABLE_DNS_ROUTING = "enableDnsRouting"
    const val ENABLE_FAKEDNS = "enableFakeDns"
    const val HIJACK_DNS = "hijackDns0"
    const val DNS_HOSTS = "dnsHosts0"
    const val REMOTE_DNS_QUERY_STRATEGY = "remoteDnsQueryStrategy"
    const val DIRECT_DNS_QUERY_STRATEGY = "directDnsQueryStrategy"
    const val EDNS_CLIENT_IP = "ednsClientIp"

    const val ENABLE_VPN_INTERFACE_IPV6_ADDRESS = "enableVPNInterfaceIPv6Address"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val METERED_NETWORK = "meteredNetwork"

    const val DOMAIN_STRATEGY = "domainStrategy"
    const val TRAFFIC_SNIFFING = "trafficSniffing"
    const val DESTINATION_OVERRIDE = "destinationOverride"
    const val OUTBOUND_DOMAIN_STRATEGY = "outboundDomainStrategy"
    const val OUTBOUND_DOMAIN_STRATEGY_FOR_DIRECT = "outboundDomainStrategyForDirect"
    const val OUTBOUND_DOMAIN_STRATEGY_FOR_SERVER = "outboundDomainStrategyForServer"

    const val BYPASS_LAN = "bypassLan"

    const val SOCKS_PORT = "socksPort"
    const val ALLOW_ACCESS = "allowAccess"
    const val SPEED_INTERVAL = "speedInterval"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"
    const val LOCAL_DNS_PORT = "portLocalDns"
    const val REQUIRE_DNS_INBOUND = "requireDnsInbound"

    const val REQUIRE_SOCKS = "requireSocks"
    const val SOCKS_USERNAME = "socksUsername"
    const val SOCKS_PASSWORD = "socksPassword"
    const val SOCKS_UDP = "socksUDP"
    const val REQUIRE_HTTP = "requireHttp"
    const val HTTP_USERNAME = "httpUsername"
    const val HTTP_PASSWORD = "httpPassword"
    const val APPEND_HTTP_PROXY = "appendHttpProxy"
    const val HTTP_PROXY_EXCEPTION = "httpProxyException"
    const val HTTP_PORT = "httpPort"

    const val REQUIRE_TRANSPROXY = "requireTransproxy"
    const val TRANSPROXY_PORT = "transproxyPort"

    const val CONNECTION_TEST_URL = "connectionTestURL"
    const val PROBE_URL = "probeUrl"
    const val PROBE_INTERVAL = "probeInterval"

    const val ROUTE_MODE = "routeMode"

    const val RULES_PROVIDER = "rulesProvider"
    const val RULES_GEOSITE_URL = "rulesGeositeUrl"
    const val RULES_GEOIP_URL = "rulesGeoipUrl"
    const val LOG_LEVEL = "logLevel"

    const val LOG_LEVEL_DEBUG_WARNING_DISABLE = "logLevelDebugWarningDisable"
    const val ENABLE_DEBUG = "enableDebug"
    const val PPROF_SERVER = "pprofServer"

    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"
    const val SHOW_GROUP_NAME = "showGroupName"

    const val SHADOWSOCKS_2022_IMPLEMENTATION = "shadowsocks2022Implementation"
    const val PROVIDER_ROOT_CA = "providerRootCA"

    const val INTERRUPT_REUSED_CONNECTIONS = "interruptReusedConnections0"

    const val TUN_IMPLEMENTATION = "tunImplementation"
    const val ENABLE_PCAP = "enablePcap"
    const val MTU = "mtu"
    const val DISCARD_ICMP = "discardICMP"
    const val ALLOW_APPS_BYPASS_VPN = "allowAppsBypassVpn"
    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"
    const val STUN_SERVERS = "stunServers"
    const val FAB_STYLE = "fabStyle"
    const val USE_IEC_UNIT = "useIECUnit"
    const val QUERY_ALL_PACKAGES_ALTERNATIVE_METHOD = "queryAllPackagesAlternativeMethod"

    const val ENABLE_FRAGMENT = "enableFragment"
    const val ENABLE_FRAGMENT_FOR_DIRECT = "enableFragmentForDirect"
    const val FRAGMENT_METHOD = "fragmentMethod"
    const val REALITY_DISABLE_X25519MLKEM768 = "realityDisableX25519Mlkem768"
    const val GRPC_SERVICE_NAME_COMPAT = "grpcServiceNameCompat"
    const val PROFILE_SECURITY_ADVISORY = "profileSecurityAdvisory"

    const val APP_TRAFFIC_STATISTICS = "appTrafficStatistics"
    const val PROFILE_TRAFFIC_STATISTICS = "profileTrafficStatistics"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_NAME = "profileName"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_STARTED = "profileStarted"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_ADDRESS = "serverAddress"
    const val SERVER_PORT = "serverPort"
    const val SERVER_USERNAME = "serverUsername"
    const val SERVER_PASSWORD = "serverPassword"
    const val SERVER_METHOD = "serverMethod"
    const val SERVER_PLUGIN_CATEGORY = "serverPluginCategory"
    const val SERVER_PLUGIN = "serverPlugin"
    const val SERVER_PLUGIN_CONFIGURE = "serverPluginConfigure"
    const val SERVER_PASSWORD1 = "serverPassword1"

    const val SERVER_PROTOCOL = "serverProtocol"
    const val SERVER_PROTOCOL_PARAM = "serverProtocolParam"
    const val SERVER_OBFS = "serverObfs"
    const val SERVER_OBFS_PARAM = "serverObfsParam"

    const val SERVER_USER_ID = "serverUserId"
    const val SERVER_ALTER_ID = "serverAlterId"
    const val SERVER_SECURITY = "serverSecurity"
    const val SERVER_NETWORK = "serverNetwork"
    const val SERVER_HEADER = "serverHeader"
    const val SERVER_HOST = "serverHost"
    const val SERVER_PATH = "serverPath"
    const val SERVER_SNI = "serverSNI"
    const val SERVER_ENCRYPTION = "serverEncryption"
    const val SERVER_ENCRYPTION_FOR_VLESS = "serverEncryptionForVless"
    const val SERVER_ALPN = "serverALPN"
    const val SERVER_CERTIFICATES = "serverCertificates"
    const val SERVER_PINNED_CERTIFICATE_CHAIN = "serverPinnedCertificateChain"
    const val SERVER_PINNED_CERTIFICATE_PUBLIC_KEY = "serverPinnedCertificatePublicKey"
    const val SERVER_PINNED_CERTIFICATE = "serverPinnedCertificate"
    const val SERVER_FLOW = "serverFlow"
    const val SERVER_QUIC_SECURITY = "serverQuicSecurity"
    const val SERVER_WS_MAX_EARLY_DATA = "serverWsMaxEarlyData"
    const val SERVER_SPLITHTTP_MODE = "serverSplithttpMode"
    const val SERVER_SPLITHTTP_EXTRA = "serverSplithttpExtra"
    const val SERVER_WS_BROWSER_FORWARDING = "serverWsBrowserForwarding"
    const val SERVER_SH_BROWSER_FORWARDING = "serverShBrowserForwarding"
    const val SERVER_EARLY_DATA_HEADER_NAME = "serverEarlyDataHeaderName"
    const val SERVER_CONFIG = "serverConfig"
    const val SERVER_PACKET_ENCODING = "serverPacketEncoding"
    const val SERVER_UTLS_FINGERPRINT = "serverUTLSFingerprint"
    const val SERVER_ECH_ENABLED = "serverEchEnabled"
    const val SERVER_ECH_CONFIG = "serverEchConfig"
    const val SERVER_MTLS_CERTIFICATE = "serverMtlsCertificate"
    const val SERVER_MTLS_CERTIFICATE_PRIVATE_KEY = "serverMtlsCertificatePrivateKey"

    const val SERVER_REALITY_PUBLIC_KEY = "serverRealityPublicKey"
    const val SERVER_REALITY_SHORT_ID = "serverRealityShortId"
    const val SERVER_REALITY_FINGERPRINT = "serverRealityFingerprint"
    const val SERVER_REALITY_DISABLE_X25519MLKEM768 = "serverRealityDisableX25519Mlkem768"

    const val SERVER_GRPC_CATAGORY = "serverGrpcCategory"
    const val SERVER_GRPC_SERVICE_NAME_COMPAT = "serverGrpcServiceNameCompat"
    const val SERVER_GRPC_MULTI_MODE = "serverGrpcMultiMode"

    const val SERVER_MEKYA_KCP_SEED = "serverMekyaKcpSeed"
    const val SERVER_MEKYA_KCP_HEADER_TYPE = "serverMekyaKcpHeaderType"
    const val SERVER_MEKYA_URL = "serverMekyaUrl"

    const val SERVER_SECURITY_CATEGORY = "serverSecurityCategory"
    const val SERVER_WS_CATEGORY = "serverWsCategory"
    const val SERVER_SH_CATEGORY = "serverShCategory"
    const val SERVER_HEADERS = "serverHeaders"
    const val SERVER_ALLOW_INSECURE = "serverAllowInsecure"

    const val SERVER_AUTH_TYPE = "serverAuthType"
    const val SERVER_UPLOAD_SPEED = "serverUploadSpeed"
    const val SERVER_DOWNLOAD_SPEED = "serverDownloadSpeed"

    const val SERVER_VMESS_EXPERIMENTS_CATEGORY = "serverVMessExperimentsCategory"
    const val SERVER_VMESS_EXPERIMENTAL_AUTHENTICATED_LENGTH = "serverVMessExperimentalAuthenticatedLength"
    const val SERVER_VMESS_EXPERIMENTAL_NO_TERMINATION_SIGNAL = "serverVMessExperimentalNoTerminationSignal"
    const val SERVER_MUX = "serverMux"
    const val SERVER_MUX_CONCURRENCY = "serverMuxConcurrency"
    const val SERVER_MUX_PACKET_ENCODING = "serverMuxPacketEncoding"

    const val SERVER_PRIVATE_KEY = "serverPrivateKey"
    const val SERVER_LOCAL_ADDRESS = "serverLocalAddress"
    const val SERVER_INSECURE_CONCURRENCY = "serverInsecureConcurrency"
    const val SERVER_MTU = "serverMTU"
    const val SERVER_SS_EXPERIMENTS_CATEGORY = "serverSsExperimentsCategory"
    const val SERVER_REDUCED_IV_HEAD_ENTROPY = "serverReducedIvHeadEntropy"
    const val SERVER_BROOK_UDP_OVER_STREAM = "serverBrookUdpOverStream"

    const val SERVER_UDP_RELAY_MODE = "serverUDPRelayMode"
    const val SERVER_CONGESTION_CONTROLLER = "serverCongestionController"
    const val SERVER_DISABLE_SNI = "serverDisableSNI"
    const val SERVER_REDUCE_RTT= "serverReduceRTT"

    const val SERVER_SHADOWTLS_PROTOCOL_VERSION = "serverShadowTLSProtocolVersion"
    const val SERVER_MIERU_MUX_LEVEL = "serverMieruMuxLevel"
    const val SERVER_MIERU_HANDSHAKE_MODE = "serverMieruHandshakeMode"
    const val SERVER_MIERU_TRAFFIC_PATTERN = "serverMieruTrafficPattern"
    const val SERVER_WIREGUARD_RESERVED = "serverWireGuardReserved"
    const val SERVER_WIREGUARD_KEEPALIVE_INTERVAL = "serverWireGuardKeepaliveInterval"
    const val SERVER_ANYTLS_IDLE_SESSION_CHECK_INTERVAL = "serverAnyTLSIdleSessionCheckInterval"
    const val SERVER_ANYTLS_IDLE_SESSION_TIMEOUT = "serverAnyTLSIdleSessionTimeout"
    const val SERVER_ANYTLS_MIN_IDLE_SESSION = "serverAnyTLSMinIdleSession"
    const val SERVER_TRUSTTUNNEL_SERVER_NAME_TO_VERIFY = "serverTrustTunnelServerNameToVerify"

    const val SERVER_PORTS = "serverPorts"
    const val SERVER_HOP_INTERVAL = "serverHopInterval"
    const val SERVER_HOP_INTERVAL_MIN = "serverHopIntervalMin"
    const val SERVER_HOP_INTERVAL_MAX = "serverHopIntervalMax"
    const val SERVER_HYSTERIA2_BBR_PROFILE = "serverHysteria2BBRProfile"

    const val SERVER_NAIVE_NO_POST_QUANTUM = "serverNaiveNoPostQuantum"
    const val SERVER_SHADOWQUIC_DISABLE_ALPN = "serverShadowQUICDisableALPN"
    const val SERVER_SHADOWQUIC_USE_SUNNYQUIC = "serverShadowQUICUseSunnyQUIC"

    const val SERVER_SING_UOT_CATEGORY = "serverSingUotCategory"
    const val SERVER_SING_MUX_CATEGORY = "serverSingMuxCategory"
    const val SERVER_SING_UOT = "serverSingUot"
    const val SERVER_SING_MUX = "serverSingMux"
    const val SERVER_SING_MUX_PROTOCOL = "serverSingMuxProtocol"
    const val SERVER_SING_MUX_MAX_CONNECTIONS = "serverSingMuxMaxConnections"
    const val SERVER_SING_MUX_MIN_STREAMS = "serverSingMuxMinStreams"
    const val SERVER_SING_MUX_MAX_STREAMS = "serverSingMuxMaxStreams"
    const val SERVER_SING_MUX_PADDING = "serverSingMuxPadding"

    const val EXPERIMENTAL_FLAGS = "experimentalFlags"

    const val BALANCER_TYPE = "balancerType"
    const val BALANCER_GROUP = "balancerGroup"
    const val BALANCER_STRATEGY = "balancerStrategy"
    const val BALANCER_NAME_FILTER = "balancerNameFilter"
    const val BALANCER_NAME_FILTER1 = "balancerNameFilter1"
    const val BALANCER_USE_LANDING_PROXY = "balancerUseLandingProxy"
    const val BALANCER_USE_FRONT_PROXY = "balancerUseFrontProxy"

    const val ROUTE_NAME = "routeName"
    const val ROUTE_DOMAIN = "routeDomain"
    const val ROUTE_IP = "routeIP"
    const val ROUTE_PORT = "routePort"
    const val ROUTE_SOURCE_PORT = "routeSourcePort"
    const val ROUTE_NETWORK = "routeNetwork"
    const val ROUTE_SOURCE = "routeSource"
    const val ROUTE_PROTOCOL = "routeProtocol"
    const val ROUTE_ATTRS = "routeAttrs"
    const val ROUTE_OUTBOUND = "routeOutbound"
    const val ROUTE_OUTBOUND_RULE = "routeOutboundRule"
    const val ROUTE_REVERSE = "routeReverse"
    const val ROUTE_REDIRECT = "routeRedirect"
    const val ROUTE_PACKAGES = "routePackages"
    const val ROUTE_CUSTOM_PACKAGE_NAME_OR_UID = "routeCustomPackageNameOrUid"
    const val ROUTE_NETWORK_TYPE = "routeNetworkType"
    const val ROUTE_SSID = "routeSSID"

    const val GROUP_NAME = "groupName"
    const val GROUP_TYPE = "groupType"
    const val GROUP_ORDER = "groupOrder"

    const val GROUP_FRONT_PROXY_OUTBOUND = "groupFrontProxyOutbound"
    const val GROUP_LANDING_PROXY_OUTBOUND = "groupLandingOutbound"
    const val GROUP_FRONT_PROXY = "groupFrontProxy"
    const val GROUP_LANDING_PROXY = "groupLandingProxy"

    const val GROUP_SUBSCRIPTION = "groupSubscription"
    const val SUBSCRIPTION_TYPE = "subscriptionType"
    const val SUBSCRIPTION_LINK = "subscriptionLink"
    const val SUBSCRIPTION_DEDUPLICATION = "subscriptionDeduplication"
    const val SUBSCRIPTION_UPDATE = "subscriptionUpdate"
    const val SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY = "subscriptionUpdateWhenConnectedOnly"
    const val SUBSCRIPTION_USER_AGENT = "subscriptionUserAgent"
    const val SUBSCRIPTION_AUTO_UPDATE = "subscriptionAutoUpdate"
    const val SUBSCRIPTION_AUTO_UPDATE_DELAY = "subscriptionAutoUpdateDelay"
    const val SUBSCRIPTION_LAST_UPDATED = "subscriptionLastUpdated"
    const val SUBSCRIPTION_BYTES_USED = "subscriptionBytesUsed"
    const val SUBSCRIPTION_BYTES_REMAINING = "subscriptionBytesRemaining"
    const val SUBSCRIPTION_EXPIRY_DATE = "subscriptionExpiryDate"
    const val SUBSCRIPTION_NAME_FILTER = "subscriptionNameFilter"
    const val SUBSCRIPTION_NAME_FILTER1 = "subscriptionNameFilter1"

    const val EDITING_ASSET_NAME = "editingAssetName"
    const val ASSET_NAME = "assetName"
    const val ASSET_URL = "assetUrl"

    const val TASKER_ACTION = "taskerAction"
    const val TASKER_PROFILE = "taskerProfile"
    const val TASKER_PROFILE_ID = "taskerProfileId"

    const val RULES_FIRST_CREATE = "rulesFirstCreate"
    const val DO_NOT_SHOW_RULE_EXPORT_WARNING = "doNotShowRuleExportWarning"

}

object TunImplementation {
    const val GVISOR = 0
    const val SYSTEM = 1
}

object Shadowsocks2022Implementation {
    const val SAGERNET_SING_SHADOWSOCKS2 = 0
    const val V2FLY_V2RAY_CORE = 1
}

object RootCAProvider {
    const val MOZILLA = 0
    const val SYSTEM = 1
    const val SYSTEM_AND_USER = 2 // for https://github.com/golang/go/issues/71258
    const val CUSTOM = 3
}

object GroupType {
    const val BASIC = 0
    const val SUBSCRIPTION = 1
}

object SubscriptionType {
    const val RAW = 0
    const val OOCv1 = 1 // removed
    const val SIP008 = 2
}

object ExtraType {
    const val NONE = 0
    const val OOCv1 = 1 // removed
    const val SIP008 = 2
}

object GroupOrder {
    const val ORIGIN = 0
    const val BY_NAME = 1
    const val BY_DELAY = 2
}

object Action {
    const val SERVICE = "io.nekohasekai.sagernet.SERVICE"
    const val CLOSE = "io.nekohasekai.sagernet.CLOSE"
    const val RELOAD = "io.nekohasekai.sagernet.RELOAD"
    const val ABORT = "io.nekohasekai.sagernet.ABORT"

    const val THEME_CHANGED = "io.nekohasekai.sagernet.THEME_CHANGED"

    const val EXTRA_PROFILE_ID = "io.nekohasekai.sagernet.EXTRA_PROFILE_ID"
}

object LogLevel {
    const val NONE = 0
    const val ERROR = 1
    const val WARNING = 2
    const val INFO = 3
    const val DEBUG = 4
}

object FabStyle {
    const val SagerNet = 0
    const val Shadowsocks = 1
}

object RouteMode {
    const val RULE = 0
    const val GLOBAL = 1
    const val DIRECT = 2
}

object TLS_FRAGMENTATION_METHOD {
    const val TLS_RECORD_FRAGMENTATION = 0
    const val TCP_SEGMENTATION = 1
    const val TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION = 2
}
