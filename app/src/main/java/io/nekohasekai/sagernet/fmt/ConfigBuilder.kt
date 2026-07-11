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

package io.nekohasekai.sagernet.fmt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.RouteMode
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TLS_FRAGMENTATION_METHOD
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.V2rayBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.gson.gson
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http3.Http3Bean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.BrowserDialerObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.BrowserForwarderObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.DNSOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.DnsObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.DokodemoDoorInboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.FakeDnsObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.FreedomOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.GrpcObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.HTTPInboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.HTTPOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.HTTPUpgradeObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.HttpObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.Hysteria2Object
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.Hysteria2OutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.InboundObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.KcpObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.LazyInboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.LazyOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.LogObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.MeekObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.MekyaObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.MultiObservatoryObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.ObservatoryObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.OutboundObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.PolicyObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.QuicObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.RealityObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.RoutingObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.RoutingObject.BalancerObject.StrategyObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.SSHOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.ShadowsocksOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.SocksInboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.SocksOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.SplitHTTPObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.StreamSettingsObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.TLSObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.TcpObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.TrojanOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.VLESSOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.VMessOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.WebSocketObject
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig.WireGuardOutboundConfigurationObject
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getArray
import io.nekohasekai.sagernet.ktx.getBoolean
import io.nekohasekai.sagernet.ktx.getBooleanProperty
import io.nekohasekai.sagernet.ktx.getInt
import io.nekohasekai.sagernet.ktx.getObject
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.getStringArray
import io.nekohasekai.sagernet.ktx.isValidHysteriaMultiPort
import io.nekohasekai.sagernet.ktx.joinHostPort
import io.nekohasekai.sagernet.ktx.listByLine
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.parseJson
import io.nekohasekai.sagernet.ktx.toHysteriaPort
import io.nekohasekai.sagernet.ktx.unescapeLineFeed
import io.nekohasekai.sagernet.ktx.uuidOrGenerate
import io.nekohasekai.sagernet.utils.PackageCache
import kotlin.io.encoding.Base64
import libexclavecore.Libexclavecore
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val TAG_SOCKS = "socks"
const val TAG_HTTP = "http"
const val TAG_TRANS = "trans"
const val TAG_TRANS6 = "trans6"

const val TAG_AGENT = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val TAG_DNS_IN = "dns-in"
const val TAG_DNS_OUT = "dns-out"

const val TAG_DNS_DIRECT = "dns-direct"

const val LOCALHOST = "127.0.0.1"
const val LOCALHOST6 = "::1"

class V2rayBuildResult(
    var config: String,
    var index: List<IndexEntity>,
    var requireWs: Boolean,
    var wsPort: Int,
    var requireSh: Boolean,
    var shPort: Int,
    var outboundTags: List<String>,
    var outboundTagsCurrent: List<String>,
    var outboundTagsAll: Map<String, ProxyEntity>,
    var bypassTag: String,
    var observerTag: String,
    var observatoryTags: Set<String>,
    val dumpUID: Boolean,
    val alerts: List<Pair<Int, String>>,
    val useFakeDNS: Boolean,
) {
    data class IndexEntity(var isBalancer: Boolean, var chain: LinkedHashMap<Triple<Int, String, String>, ProxyEntity>)
}

@OptIn(ExperimentalUuidApi::class)
fun buildV2RayConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): V2rayBuildResult {
    if (proxy.type == ProxyEntity.TYPE_CONFIG && proxy.configBean!!.type == "v2ray") {
        return buildCustomConfig(proxy, forTest, forExport)
    }

    val outboundTags = ArrayList<String>()
    val outboundTagsCurrent = ArrayList<String>()
    val outboundTagsAll = HashMap<String, ProxyEntity>()
    val globalOutbounds = ArrayList<String>()

    fun ProxyEntity.resolveChainRecursively(): MutableList<ProxyEntity> {
        when (type) {
            ProxyEntity.TYPE_BALANCER -> error("balancer in proxy chain is not supported")
            ProxyEntity.TYPE_CHAIN -> {
                val beans = SagerDatabase.proxyDao.getEntities(chainBean!!.proxies)
                val beansMap = beans.associateBy { it.id }
                val beanList = ArrayList<ProxyEntity>()
                for ((index, proxyId) in chainBean!!.proxies.withIndex()) {
                    val item = beansMap[proxyId] ?: continue
                    if (!item.requireBean().canMapping() && index != 0) error("${item.displayName()} can be the front proxy only")
                    if (item.type == ProxyEntity.TYPE_CONFIG && item.configBean!!.type == "v2ray") error("custom config in proxy chain is not supported")
                    beanList.addAll(item.resolveChainRecursively())
                }
                return beanList
            }
            else -> return mutableListOf(this)
        }
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        if (type == ProxyEntity.TYPE_BALANCER) {
            val beans = if (balancerBean!!.type == BalancerBean.TYPE_LIST) {
                SagerDatabase.proxyDao.getEntities(balancerBean!!.proxies)
            } else {
                SagerDatabase.proxyDao.getByGroup(balancerBean!!.groupId)
                    .filter { if (balancerBean!!.nameFilter.isEmpty()) { true } else { !Regex(balancerBean!!.nameFilter).containsMatchIn(it.requireBean().name) } }
                    .filter { if (balancerBean!!.nameFilter1.isEmpty()) { true } else { Regex(balancerBean!!.nameFilter1).containsMatchIn(it.requireBean().name) } }
            }
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()

            // For balancer, we don't add landing proxy here
            // It will be handled in buildChain() function
            for (proxyId in beansMap.keys) {
                val item = beansMap[proxyId] ?: continue
                if (item.id == id) continue
                when (item.type) {
                    ProxyEntity.TYPE_BALANCER -> error("balancer in balancer is not supported")
                    ProxyEntity.TYPE_CHAIN -> error("proxy chain in balancer is not supported")
                    ProxyEntity.TYPE_CONFIG -> if (item.configBean!!.type == "v2ray") error("custom config in balancer is not supported")
                }
                beanList.add(item)
            }
            return beanList
        }
        val list = resolveChainRecursively().asReversed()
        if (type == ProxyEntity.TYPE_CHAIN) return list
        if (type == ProxyEntity.TYPE_CONFIG && configBean!!.type == "v2ray") return list
        SagerDatabase.groupDao.getById(groupId)?.let { group ->
            group.frontProxy.takeIf { it > 0L }?.let { id ->
                SagerDatabase.proxyDao.getById(id)?.let {
                    when (it.type) {
                        ProxyEntity.TYPE_BALANCER -> error("balancer can not be the front proxy")
                        ProxyEntity.TYPE_CHAIN -> list.addAll(it.resolveChainRecursively().asReversed())
                        else -> {
                            if (it.type == ProxyEntity.TYPE_CONFIG && it.configBean!!.type == "v2ray") error("custom config can not be the front proxy")
                            list.add(it)
                        }
                    }
                } ?: error("front proxy not found for ${group.displayName()}")
            }
            group.landingProxy.takeIf { it > 0L }?.let { id ->
                SagerDatabase.proxyDao.getById(id)?.let {
                    when (it.type) {
                        ProxyEntity.TYPE_BALANCER -> error("balancer can not be the landing proxy")
                        ProxyEntity.TYPE_CHAIN -> list.addAll(0, it.resolveChainRecursively().asReversed())
                        else -> {
                            if (it.type == ProxyEntity.TYPE_CONFIG && it.configBean!!.type == "v2ray") error("custom config can not be the landing proxy")
                            if (!it.requireBean().canMapping()) error("${it.displayName()} can be the front proxy only and can not be the landing proxy")
                            list.add(0, it)
                        }
                    }
                } ?: error("landing proxy not found for ${group.displayName()}")
            }
        }
        return list
    }

    val routeMode = DataStore.routeMode
    val proxies = proxy.resolveChain()
    val extraRules = if (forTest || routeMode != RouteMode.RULE) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies = if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
        rule.outbound.takeIf { it > 0 && it != proxy.id }
    }.toHashSet().toList()).associate {
        (it.id to ((it.type == ProxyEntity.TYPE_BALANCER) to lazy {
            it.balancerBean
        })) to it.resolveChain()
    }

    val allowAccess = DataStore.allowAccess
    val bind = if (!forTest && allowAccess) "0.0.0.0" else LOCALHOST

    var directDNS = DataStore.directDns.listByLineOrComma().filter { !it.startsWith("#") }
    if (DataStore.useLocalDnsAsDirectDns) directDNS = listOf("localhost")
    val remoteDNS = if (routeMode == RouteMode.DIRECT) {
        directDNS
    } else {
        DataStore.remoteDns.listByLineOrComma().filter { !it.startsWith("#") }
    }
    var bootstrapDNS = DataStore.bootstrapDns.listByLineOrComma().filter { !it.startsWith("#") }
    if (DataStore.useLocalDnsAsBootstrapDns) bootstrapDNS = listOf("localhost")
    val useFakeDns = DataStore.enableFakeDns
    val remoteDnsQueryStrategy = DataStore.remoteDnsQueryStrategy
    val directDnsQueryStrategy = DataStore.directDnsQueryStrategy
    val trafficSniffing = DataStore.trafficSniffing
    val indexMap = ArrayList<IndexEntity>()
    var requireWs = false
    var requireSh = false
    val destinationOverride = DataStore.destinationOverride
    val trafficStatistics = !forTest && DataStore.profileTrafficStatistics
    var hasTagDirect = false
    var directNeedsInterruption = false

    val shouldDumpUID = extraRules.any { it.packages.isNotEmpty() || it.customPackageNames.isNotEmpty() }
    val alerts = mutableListOf<Pair<Int, String>>()

    lateinit var result: V2rayBuildResult
    V2RayConfig().apply {

        dns = DnsObject().apply {
            if (DataStore.hosts.isNotEmpty()) {
                hosts = mutableMapOf()
                for (singleLine in DataStore.hosts.listByLine()) {
                    val key = singleLine.substringBefore(" ")
                    val values = singleLine.substringAfter(" ").split("\\s+".toRegex()).toMutableList()
                    if (hosts.contains(key)) {
                        if (!hosts[key]!!.valueX.isNullOrEmpty()) {
                            values.add(hosts[key]!!.valueX)
                        } else if (!hosts[key]!!.valueY.isNullOrEmpty()) {
                            values.addAll(hosts[key]!!.valueY)
                        }
                    }
                    if (values.size > 1) {
                        hosts[key] = DnsObject.StringOrListObject().apply {
                            valueX = null
                            valueY = values
                        }
                    } else if (values.size == 1) {
                        hosts[key] = DnsObject.StringOrListObject().apply {
                            valueX = values[0]
                            valueY = null
                        }
                    }
                }
            }
            servers = mutableListOf()
            fallbackStrategy = "disabledIfAnyMatch"
        }

        log = LogObject().apply {
            loglevel = when (DataStore.logLevel) {
                LogLevel.DEBUG -> "debug"
                LogLevel.INFO -> "info"
                LogLevel.WARNING -> "warning"
                LogLevel.ERROR -> "error"
                else -> "none"
            }
            if (DataStore.logLevel == LogLevel.NONE) {
                access = "none"
            }
        }

        policy = PolicyObject().apply {
            levels = mapOf(
                // dns
                "1" to PolicyObject.LevelPolicyObject().apply {
                    connIdle = 30
                })

            if (trafficStatistics) {
                system = PolicyObject.SystemPolicyObject().apply {
                    statsOutboundDownlink = true
                    statsOutboundUplink = true
                }
            }
        }
        inbounds = mutableListOf()

        if (!forTest) {
            if (!forExport) {
                inbounds.add(InboundObject().apply {
                    tag = "ipc-in"
                    protocol = "ipc"
                    val path = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock"
                    val udsFile = File(path)
                    if (udsFile.exists()) udsFile.delete()
                    listen = path
                    if (trafficSniffing || useFakeDns) {
                        sniffing = InboundObject.SniffingObject().apply {
                            enabled = true
                            destOverride = when {
                                useFakeDns && !trafficSniffing -> listOf("fakedns")
                                useFakeDns -> listOf("fakedns", "http", "tls", "quic")
                                else -> listOf("http", "tls", "quic")
                            }
                            metadataOnly = useFakeDns && !trafficSniffing
                            routeOnly = !destinationOverride
                        }
                    }
                })
            }
            if (DataStore.requireSocks) {
                inbounds.add(InboundObject().apply {
                    tag = TAG_SOCKS
                    listen = bind
                    port = DataStore.socksPort
                    protocol = "socks"
                    settings = LazyInboundConfigurationObject(this, SocksInboundConfigurationObject().apply {
                        if (DataStore.socksUsername.isEmpty() && DataStore.socksPassword.isEmpty()) {
                            auth = "noauth"
                        } else if (DataStore.socksUsername.isEmpty() && DataStore.socksPassword.isNotEmpty()) {
                            error("username is empty but password is not empty for SOCKS5 inbound")
                        } else if (DataStore.socksUsername.isNotEmpty() && DataStore.socksPassword.isEmpty()) {
                            error("username is not empty but password is empty for SOCKS5 inbound")
                        } else {
                            auth = "password"
                            accounts = listOf(SocksInboundConfigurationObject.AccountObject().apply {
                                user = DataStore.socksUsername
                                pass = DataStore.socksPassword
                            })
                        }
                        udp = DataStore.socksUDP
                    })
                    if (trafficSniffing || useFakeDns) {
                        sniffing = InboundObject.SniffingObject().apply {
                            enabled = true
                            destOverride = when {
                                useFakeDns && !trafficSniffing -> listOf("fakedns")
                                useFakeDns -> listOf("fakedns", "http", "tls", "quic")
                                else -> listOf("http", "tls", "quic")
                            }
                            metadataOnly = useFakeDns && !trafficSniffing
                            routeOnly = !destinationOverride
                        }
                    }
                    if (shouldDumpUID) dumpUID = true
                })
            }
            if (DataStore.requireHttp) {
                inbounds.add(InboundObject().apply {
                    tag = TAG_HTTP
                    listen = bind
                    port = DataStore.httpPort
                    protocol = "http"
                    settings = LazyInboundConfigurationObject(this,
                        HTTPInboundConfigurationObject().apply {
                            allowTransparent = true
                            if (DataStore.httpUsername.isNotEmpty() || DataStore.httpPassword.isNotEmpty()) {
                                accounts = listOf(HTTPInboundConfigurationObject.AccountObject().apply {
                                    user = DataStore.httpUsername
                                    pass = DataStore.httpPassword
                                })
                            }
                        })
                    if (trafficSniffing || useFakeDns) {
                        sniffing = InboundObject.SniffingObject().apply {
                            enabled = true
                            destOverride = when {
                                useFakeDns && !trafficSniffing -> listOf("fakedns")
                                useFakeDns -> listOf("fakedns", "http", "tls")
                                else -> listOf("http", "tls")
                            }
                            metadataOnly = useFakeDns && !trafficSniffing
                            routeOnly = !destinationOverride
                        }
                    }
                    if (shouldDumpUID) dumpUID = true
                })
            }

            if (DataStore.requireTransproxy) {
                inbounds.add(InboundObject().apply {
                    tag = TAG_TRANS
                    listen = bind
                    port = DataStore.transproxyPort
                    protocol = "dokodemo-door"
                    settings = LazyInboundConfigurationObject(this,
                        DokodemoDoorInboundConfigurationObject().apply {
                            network = "tcp"
                            followRedirect = true
                        })
                    if (trafficSniffing || useFakeDns) {
                        sniffing = InboundObject.SniffingObject().apply {
                            enabled = true
                            destOverride = when {
                                useFakeDns && !trafficSniffing -> listOf("fakedns")
                                useFakeDns -> listOf("fakedns", "http", "tls")
                                else -> listOf("http", "tls")
                            }
                            metadataOnly = useFakeDns && !trafficSniffing
                            routeOnly = !destinationOverride
                        }
                    }
                    if (shouldDumpUID) dumpUID = true
                })
                if (bind == LOCALHOST) {
                    inbounds.add(InboundObject().apply {
                        tag = TAG_TRANS6
                        listen = LOCALHOST6
                        port = DataStore.transproxyPort
                        protocol = "dokodemo-door"
                        settings = LazyInboundConfigurationObject(this,
                            DokodemoDoorInboundConfigurationObject().apply {
                                network = "tcp"
                                followRedirect = true
                            })
                        if (trafficSniffing || useFakeDns) {
                            sniffing = InboundObject.SniffingObject().apply {
                                enabled = true
                                destOverride = when {
                                    useFakeDns && !trafficSniffing -> listOf("fakedns")
                                    useFakeDns -> listOf("fakedns", "http", "tls")
                                    else -> listOf("http", "tls")
                                }
                                metadataOnly = useFakeDns && !trafficSniffing
                                routeOnly = !destinationOverride
                            }
                        }
                        if (shouldDumpUID) dumpUID = true
                    })
                }
            }
        }

        outbounds = mutableListOf()

        routing = RoutingObject().apply {
            domainStrategy = DataStore.domainStrategy

            rules = mutableListOf()

            val wsRules = HashMap<String, RoutingObject.RuleObject>()

            for (proxyEntity in proxies) {
                val bean = proxyEntity.requireBean()

                val needBrowserForwarder = when {
                    bean !is StandardV2RayBean -> false
                    bean.type == "ws" && bean.wsUseBrowserForwarder -> true
                    bean.type == "splithttp" && bean.shUseBrowserForwarder -> true
                    else -> false
                }
                if (needBrowserForwarder) {
                    hasTagDirect = true
                    bean as StandardV2RayBean
                    // dirty hack to exclude browser forwarder traffic from VpnService
                    // this will not work on all cases,
                    // but this is not the main function of this software, just keep it broken
                    if (bean.security == "none" && bean.host.isNotEmpty()) {
                        val host = try {
                            val u = Libexclavecore.newURL("placeholder").apply {
                                rawHost = if (Libexclavecore.isIPv6(bean.host)) "[${bean.host}]" else bean.host
                            }.string
                            Libexclavecore.parseURL(u).host
                        } catch (_: Exception) {
                            bean.host
                        }
                        wsRules[host] = RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = TAG_DIRECT
                            port = bean.serverPort.toString()
                            if (Libexclavecore.isIP(host)) {
                                ip = listOf(host)
                                if (DataStore.domainStrategy != "AsIs") {
                                    skipDomain = true
                                }
                            } else {
                                domains = listOf(host)
                            }
                        }
                    }
                    if (bean.security != "none" && bean.sni.isNotEmpty()) {
                        wsRules[bean.sni] = RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = TAG_DIRECT
                            port = bean.serverPort.toString()
                            if (!Libexclavecore.isIP(bean.sni)) {
                                domains = listOf(bean.sni)
                            }
                        }
                    }
                    if (bean.serverAddress.isNotEmpty()) {
                        wsRules[bean.serverAddress] = RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = TAG_DIRECT
                            port = bean.serverPort.toString()
                            if (Libexclavecore.isIP(bean.serverAddress)) {
                                ip = listOf(bean.serverAddress)
                                if (DataStore.domainStrategy != "AsIs") {
                                    skipDomain = true
                                }
                            } else {
                                domains = listOf(bean.serverAddress)
                            }
                        }
                    }
                }

            }

            rules.addAll(wsRules.values)
        }

        var rootBalancer: RoutingObject.RuleObject? = null
        var rootObserver: MultiObservatoryObject.MultiObservatoryItem? = null

        fun buildChain(
            tagOutbound: String,
            profileList: List<ProxyEntity>,
            isBalancer: Boolean,
            balancer: () -> BalancerBean?,
        ): String {
            var pastExternal = false
            lateinit var pastOutbound: OutboundObject
            lateinit var currentOutbound: OutboundObject
            lateinit var pastInboundTag: String
            val chainMap = LinkedHashMap<Triple<Int, String, String>, ProxyEntity>()
            indexMap.add(IndexEntity(isBalancer, chainMap))
            val chainOutbounds = ArrayList<OutboundObject>()
            var chainOutbound = ""

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                currentOutbound = OutboundObject()

                val tagIn: String
                var needGlobal: Boolean

                if (isBalancer || index == profileList.lastIndex && !pastExternal) {
                    tagIn = "$TAG_AGENT-global-${proxyEntity.id}"
                    needGlobal = true
                } else {
                    tagIn = if (index == 0) tagOutbound else {
                        "$tagOutbound-${proxyEntity.id}"
                    }
                    needGlobal = false
                }

                if (index == 0) {
                    chainOutbound = tagIn
                }

                if (needGlobal) {
                    if (!globalOutbounds.contains(tagIn)) {
                        needGlobal = false
                        globalOutbounds.add(tagIn)
                    }
                }

                if (!needGlobal) {

                    outboundTagsAll[tagIn] = proxyEntity

                    if (isBalancer || index == 0) {
                        outboundTags.add(tagIn)
                        if (tagOutbound == TAG_AGENT) {
                            outboundTagsCurrent.add(tagIn)
                        }
                    }

                    var currentDomainStrategy = DataStore.outboundDomainStrategy

                    if (proxyEntity.needExternal()) {
                        val localPort = mkPort()
                        val username = Uuid.generateV4().toHexString()
                        val password = Uuid.generateV4().toHexString()
                        chainMap[Triple(localPort, username, password)] = proxyEntity
                        currentOutbound.apply {
                            protocol = "socks"
                            settings = LazyOutboundConfigurationObject(this, SocksOutboundConfigurationObject().apply {
                                servers = listOf(SocksOutboundConfigurationObject.ServerObject().apply {
                                    address = LOCALHOST
                                    port = localPort
                                    users = listOf(SocksOutboundConfigurationObject.ServerObject.UserObject().apply {
                                        user = username
                                        pass = password
                                    })
                                })
                                if (proxyEntity.naiveBean != null && proxyEntity.naiveBean!!.singUoT && DataStore.experimentalFlagsProperties.getBooleanProperty( "singuot")) {
                                    uot = true
                                }
                                if (proxyEntity.naiveBean != null || proxyEntity.shadowquicBean != null) {
                                    directNeedsInterruption = true
                                }
                            })
                        }
                    } else {
                        currentOutbound.apply {
                            if (bean is StandardV2RayBean) {
                                if (bean is VMessBean) {
                                    protocol = "vmess"
                                    settings = LazyOutboundConfigurationObject(this,
                                        VMessOutboundConfigurationObject().apply {
                                            vnext = listOf(VMessOutboundConfigurationObject.ServerObject()
                                                .apply {
                                                    address = bean.serverAddress
                                                    port = bean.serverPort
                                                    users = listOf(VMessOutboundConfigurationObject.ServerObject.UserObject()
                                                        .apply {
                                                            id = uuidOrGenerate(bean.uuid)
                                                            if (bean.alterId > 0) {
                                                                alterId = bean.alterId
                                                            }
                                                            security = bean.encryption.takeIf { it.isNotEmpty() }
                                                                ?: "auto"
                                                            experiments = ""
                                                            if (bean.experimentalAuthenticatedLength) {
                                                                experiments += "AuthenticatedLength"
                                                            }
                                                            if (bean.experimentalNoTerminationSignal) {
                                                                if (experiments != "") {
                                                                    experiments += "|"
                                                                }
                                                                experiments += "NoTerminationSignal"
                                                            }
                                                            if (experiments.isEmpty()) experiments = null
                                                        })
                                                })
                                            when (bean.packetEncoding) {
                                                "packet" -> {
                                                    packetEncoding = "packet"
                                                }
                                                "xudp" -> {
                                                    packetEncoding = "xudp"
                                                }
                                            }
                                        })
                                } else if (bean is VLESSBean) {
                                    protocol = "vless"
                                    settings = LazyOutboundConfigurationObject(this,
                                        VLESSOutboundConfigurationObject().apply {
                                            vnext = listOf(VLESSOutboundConfigurationObject.ServerObject()
                                                .apply {
                                                    address = bean.serverAddress
                                                    port = bean.serverPort
                                                    users = listOf(VLESSOutboundConfigurationObject.ServerObject.UserObject()
                                                        .apply {
                                                            id = uuidOrGenerate(bean.uuid)
                                                            encryption = bean.encryption
                                                            if (bean.flow.isNotEmpty()) {
                                                                flow = bean.flow
                                                            }
                                                        })
                                                })
                                            when (bean.packetEncoding) {
                                                "packet" -> {
                                                    packetEncoding = "packet"
                                                }
                                                "xudp" -> {
                                                    packetEncoding = "xudp"
                                                }
                                            }
                                        })
                                } else if (bean is TrojanBean) {
                                    protocol = "trojan"
                                    settings = LazyOutboundConfigurationObject(this,
                                        TrojanOutboundConfigurationObject().apply {
                                            servers = listOf(TrojanOutboundConfigurationObject.ServerObject()
                                                .apply {
                                                    address = bean.serverAddress
                                                    port = bean.serverPort
                                                    password = bean.password
                                                })
                                        })
                                } else if (bean is ShadowsocksBean) {
                                    protocol = "shadowsocks"
                                    settings = LazyOutboundConfigurationObject(this,
                                        ShadowsocksOutboundConfigurationObject().apply {
                                            servers = listOf(ShadowsocksOutboundConfigurationObject.ServerObject().apply {
                                                address = bean.serverAddress
                                                port = bean.serverPort
                                                password = bean.password
                                                method = bean.method
                                                if (!bean.method.startsWith("2022-blake3-") && bean.experimentReducedIvHeadEntropy) {
                                                    experimentReducedIvHeadEntropy = bean.experimentReducedIvHeadEntropy
                                                }
                                                if (bean.plugin.isNotEmpty()) {
                                                    val pluginConfiguration = PluginConfiguration(bean.plugin)
                                                    if (pluginConfiguration.selected.isNotEmpty()) {
                                                        plugin = pluginConfiguration.selected
                                                        pluginOpts = pluginConfiguration.getOptions().toString()
                                                        if (!forExport
                                                            && !(plugin == "v2ray-plugin" && DataStore.experimentalFlagsProperties.getBooleanProperty("useInternalV2RayPlugin"))
                                                            && !(plugin == "obfs-local" && DataStore.experimentalFlagsProperties.getBooleanProperty("useInternalObfsLocal"))
                                                        ) {
                                                            try {
                                                                PluginManager.init(pluginConfiguration)?.let { (path, opts, isV2) ->
                                                                    plugin = path
                                                                    val shouldProtect = if (forTest) {
                                                                        DataStore.serviceMode == Key.MODE_VPN && DataStore.tunImplementation == TunImplementation.SYSTEM && DataStore.startedProfile > 0 && SagerNet.started
                                                                    } else {
                                                                        DataStore.serviceMode == Key.MODE_VPN && DataStore.tunImplementation == TunImplementation.SYSTEM
                                                                    }
                                                                    if (shouldProtect) {
                                                                        pluginWorkingDir = SagerNet.deviceStorage.noBackupFilesDir.toString()
                                                                        if (isV2) {
                                                                            opts["__android_vpn"] = ""
                                                                        } else {
                                                                            pluginArgs = listOf("-V")
                                                                        }
                                                                    }
                                                                    pluginOpts = opts.toString()
                                                                }
                                                            } catch (e: PluginManager.PluginNotFoundException) {
                                                                if (e.plugin in arrayOf("v2ray-plugin", "obfs-local")) {
                                                                    plugin = e.plugin
                                                                    pluginOpts = pluginConfiguration.getOptions().toString()
                                                                } else {
                                                                    throw e
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                if (bean.singUoT && DataStore.experimentalFlagsProperties.getBooleanProperty( "singuot")) {
                                                    uot = bean.singUoT
                                                }
                                            })
                                        }
                                    )
                                } else if (bean is SOCKSBean) {
                                    protocol = "socks"
                                    settings = LazyOutboundConfigurationObject(this,
                                        SocksOutboundConfigurationObject().apply {
                                            servers = listOf(SocksOutboundConfigurationObject.ServerObject().apply {
                                                address = bean.serverAddress
                                                port = bean.serverPort
                                                if (bean.username.isNotEmpty() || bean.password.isNotEmpty() && bean.protocol == SOCKSBean.PROTOCOL_SOCKS5) {
                                                    users = listOf(SocksOutboundConfigurationObject.ServerObject.UserObject().apply {
                                                        user = bean.username
                                                        pass = bean.password
                                                    })
                                                }
                                                if (bean.protocol == SOCKSBean.PROTOCOL_SOCKS4 || bean.protocol == SOCKSBean.PROTOCOL_SOCKS4A && bean.username.isNotEmpty()) {
                                                    users = listOf(SocksOutboundConfigurationObject.ServerObject.UserObject().apply {
                                                        user = bean.username
                                                    })
                                                }
                                            })
                                            version = bean.protocolVersionName()
                                            if (bean.singUoT && DataStore.experimentalFlagsProperties.getBooleanProperty("singuot")) {
                                                uot = bean.singUoT
                                            }
                                        }
                                    )
                                } else if (bean is HttpBean) {
                                    protocol = "http"
                                    settings = LazyOutboundConfigurationObject(this,
                                        HTTPOutboundConfigurationObject().apply {
                                            servers = listOf(HTTPOutboundConfigurationObject.ServerObject().apply {
                                                address = bean.serverAddress
                                                port = bean.serverPort
                                                if (bean.username.isNotEmpty() || bean.password.isNotEmpty()) {
                                                    users = listOf(HTTPInboundConfigurationObject.AccountObject().apply {
                                                        user = bean.username
                                                        pass = bean.password
                                                    })
                                                }
                                            })
                                        }
                                    )
                                }
                                streamSettings = StreamSettingsObject().apply {
                                    network = bean.type
                                    if (bean.security.isNotEmpty()) {
                                        security = bean.security
                                    }
                                    when (security) {
                                        "tls" -> {
                                            tlsSettings = TLSObject().apply {
                                                if (bean.sni.isNotEmpty()) {
                                                    serverName = bean.sni
                                                }
                                                if (bean.alpn.isNotEmpty()) {
                                                    alpn = bean.alpn.listByLineOrComma()
                                                }
                                                if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                    certificates = mutableListOf()
                                                    if (bean.certificates.isNotEmpty()) {
                                                        disableSystemRoot = true
                                                        certificates.add(TLSObject.CertificateObject().apply {
                                                            usage = "verify"
                                                            certificate = bean.certificates.lines()
                                                        })
                                                    }
                                                    if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                        certificates.add(TLSObject.CertificateObject().apply {
                                                            usage = "encipherment"
                                                            certificate = bean.mtlsCertificate.lines()
                                                            key = bean.mtlsCertificatePrivateKey.lines()
                                                        })
                                                    }
                                                }
                                                if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                                    pinnedPeerCertificateSha256 = mutableListOf()
                                                    bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                        pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                                    }
                                                }
                                                if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                                    pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                                }
                                                if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                                    pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                                }
                                                if (bean.allowInsecure) {
                                                    allowInsecure = true
                                                }
                                                val overrideFingerprint = DataStore.experimentalFlagsProperties.getProperty("overrideUTLSFingerprintForTLS")
                                                if (!overrideFingerprint.isNullOrEmpty()) {
                                                    fingerprint = overrideFingerprint
                                                } else if (bean.utlsFingerprint.isNotEmpty()) {
                                                    fingerprint = bean.utlsFingerprint
                                                }
                                                if (bean.echEnabled) {
                                                    ech = TLSObject.ECHObject().apply {
                                                        enabled = bean.echEnabled
                                                        if (bean.echConfig.isNotEmpty()) {
                                                            config = bean.echConfig
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "reality" -> {
                                            realitySettings = RealityObject().apply {
                                                if (bean.sni.isNotEmpty()) {
                                                    serverName = bean.sni
                                                }
                                                if (bean.realityPublicKey.isNotEmpty()) {
                                                    publicKey = bean.realityPublicKey
                                                }
                                                if (bean.realityShortId.isNotEmpty()) {
                                                    shortId = bean.realityShortId
                                                }
                                                if (bean.realityMldsa65Verify.isNotEmpty()) {
                                                    mldsa65Verify = bean.realityMldsa65Verify
                                                }
                                                val overrideFingerprint = DataStore.experimentalFlagsProperties.getProperty("overrideUTLSFingerprintForREALITY")
                                                if (!overrideFingerprint.isNullOrEmpty()) {
                                                    fingerprint = overrideFingerprint
                                                } else if (bean.realityFingerprint.isNotEmpty()) {
                                                    fingerprint = bean.realityFingerprint
                                                }
                                                if (DataStore.realityDisableX25519Mlkem768 || bean.realityDisableX25519Mlkem768 && !forExport) {
                                                    disableX25519MLKEM768 = true
                                                }
                                            }
                                        }
                                    }

                                    when (network) {
                                        "tcp" -> {
                                            tcpSettings = TcpObject().apply {
                                                if (bean.headerType == "http") {
                                                    header = TcpObject.HeaderObject().apply {
                                                        type = "http"
                                                        if (bean.host.isNotEmpty() || bean.path.isNotEmpty()) {
                                                            request = TcpObject.HeaderObject.HTTPRequestObject()
                                                                .apply {
                                                                    headers = mutableMapOf()
                                                                    if (bean.host.isNotEmpty()) {
                                                                        headers["Host"] = TcpObject.HeaderObject.StringOrListObject()
                                                                            .apply {
                                                                                valueY = bean.host.listByLineOrComma()
                                                                            }
                                                                    }
                                                                    if (bean.path.isNotEmpty()) {
                                                                        path = bean.path.listByLineOrComma()
                                                                    }
                                                                }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "kcp" -> {
                                            kcpSettings = KcpObject().apply {
                                                mtu = 1350
                                                tti = 50
                                                uplinkCapacity = 12
                                                downlinkCapacity = 100
                                                congestion = false
                                                readBufferSize = 1
                                                writeBufferSize = 1
                                                header = KcpObject.HeaderObject().apply {
                                                    type = bean.headerType
                                                }
                                                if (bean.mKcpSeed.isNotEmpty()) {
                                                    seed = bean.mKcpSeed
                                                }
                                            }
                                        }
                                        "ws" -> {
                                            wsSettings = WebSocketObject().apply {
                                                headers = mutableMapOf()

                                                if (bean.host.isNotEmpty()) {
                                                    headers["Host"] = bean.host
                                                }

                                                path = bean.path.takeIf { it.isNotEmpty() } ?: "/"

                                                if (bean.maxEarlyData > 0) {
                                                    maxEarlyData = bean.maxEarlyData
                                                }

                                                if (bean.earlyDataHeaderName.isNotEmpty()) {
                                                    earlyDataHeaderName = bean.earlyDataHeaderName
                                                }

                                                if (bean.wsUseBrowserForwarder) {
                                                    useBrowserForwarding = true
                                                    requireWs = true
                                                }
                                            }
                                        }
                                        "http" -> {
                                            network = "http"

                                            httpSettings = HttpObject().apply {
                                                if (bean.host.isNotEmpty()) {
                                                    host = bean.host.listByLineOrComma()
                                                }
                                                if (bean.path.isNotEmpty()) {
                                                    path = bean.path
                                                }
                                            }
                                        }
                                        "quic" -> {
                                            quicSettings = QuicObject().apply {
                                                security = bean.quicSecurity
                                                key = bean.quicKey
                                                header = QuicObject.HeaderObject().apply {
                                                    type = bean.headerType
                                                }
                                            }
                                        }
                                        "grpc" -> {
                                            grpcSettings = GrpcObject().apply {
                                                serviceName = bean.grpcServiceName
                                                if (DataStore.grpcServiceNameCompat || bean.grpcServiceNameCompat) {
                                                    serviceNameCompat = true
                                                }
                                                if (bean.grpcMultiMode) {
                                                    multiMode = true
                                                }
                                            }
                                        }
                                        "meek" -> {
                                            meekSettings = MeekObject().apply {
                                                if (bean.meekUrl.isNotEmpty()) {
                                                    url = bean.meekUrl
                                                }
                                            }
                                        }
                                        "httpupgrade" -> {
                                            httpupgradeSettings = HTTPUpgradeObject().apply {
                                                if (bean.host.isNotEmpty()) {
                                                    host = bean.host
                                                }
                                                if (bean.path.isNotEmpty()) {
                                                    path = bean.path
                                                }
                                                if (bean.maxEarlyData > 0) {
                                                    maxEarlyData = bean.maxEarlyData
                                                }
                                                if (bean.earlyDataHeaderName.isNotEmpty()) {
                                                    earlyDataHeaderName = bean.earlyDataHeaderName
                                                }
                                            }
                                        }
                                        "splithttp" -> {
                                            splithttpSettings = SplitHTTPObject().apply {
                                                if (bean.host.isNotEmpty()) {
                                                    host = bean.host
                                                }
                                                if (bean.path.isNotEmpty()) {
                                                    path = bean.path
                                                }
                                                if (bean.splithttpMode != "auto") {
                                                    mode = bean.splithttpMode
                                                }
                                                if (bean.splithttpExtra.isNotEmpty()) {
                                                    try {
                                                        parseJson(bean.splithttpExtra).asJsonObject?.also { extra ->
                                                            // fuck RPRX `extra`
                                                            extra.getInt("scMaxEachPostBytes", ignoreCase = true)?.also {
                                                                scMaxEachPostBytes = it.toString()
                                                            } ?: extra.getString("scMaxEachPostBytes", ignoreCase = true)?.also {
                                                                scMaxEachPostBytes = it
                                                            }
                                                            extra.getInt("scMinPostsIntervalMs", ignoreCase = true)?.also {
                                                                scMinPostsIntervalMs = it.toString()
                                                            } ?: extra.getString("scMinPostsIntervalMs", ignoreCase = true)?.also {
                                                                scMinPostsIntervalMs = it
                                                            }
                                                            extra.getInt("xPaddingBytes", ignoreCase = true)?.also {
                                                                xPaddingBytes = it.toString()
                                                            } ?: extra.getString("xPaddingBytes", ignoreCase = true)?.also {
                                                                xPaddingBytes = it
                                                            }
                                                            extra.getBoolean("noGRPCHeader", ignoreCase = true)?.also {
                                                                noGRPCHeader = it
                                                            }
                                                            extra.getObject("headers", ignoreCase = true)?.also {
                                                                headers = mutableMapOf<String, String>()
                                                                for (key in it.keySet()) {
                                                                    it.getString(key)?.also { value ->
                                                                        headers[key] = value
                                                                    }
                                                                }
                                                            }
                                                            extra.getObject("xmux", ignoreCase = true)?.also { xmuxSettings ->
                                                                xmux = SplitHTTPObject.XmuxObject().apply {
                                                                    xmuxSettings.getInt("maxConcurrency", ignoreCase = true)?.also {
                                                                        maxConcurrency = it.toString()
                                                                    } ?: xmuxSettings.getString("maxConcurrency", ignoreCase = true)?.also {
                                                                        maxConcurrency = it
                                                                    }
                                                                    xmuxSettings.getInt("maxConnections", ignoreCase = true)?.also {
                                                                        maxConnections = it.toString()
                                                                    } ?: xmuxSettings.getString("maxConnections", ignoreCase = true)?.also {
                                                                        maxConnections = it
                                                                    }
                                                                    xmuxSettings.getInt("cMaxReuseTimes", ignoreCase = true)?.also {
                                                                        cMaxReuseTimes = it.toString()
                                                                    } ?: xmuxSettings.getString("cMaxReuseTimes", ignoreCase = true)?.also {
                                                                        cMaxReuseTimes = it
                                                                    }
                                                                    xmuxSettings.getInt("hMaxRequestTimes", ignoreCase = true)?.also {
                                                                        hMaxRequestTimes = it.toString()
                                                                    } ?: xmuxSettings.getString("hMaxRequestTimes", ignoreCase = true)?.also {
                                                                        hMaxRequestTimes = it
                                                                    }
                                                                    xmuxSettings.getInt("hMaxReusableSecs", ignoreCase = true)?.also {
                                                                        hMaxReusableSecs = it.toString()
                                                                    } ?: xmuxSettings.getString("hMaxReusableSecs", ignoreCase = true)?.also {
                                                                        hMaxReusableSecs = it
                                                                    }
                                                                }
                                                            }
                                                            extra.getBoolean("xPaddingObfsMode", ignoreCase = true)?.also {
                                                                xPaddingObfsMode = it
                                                            }
                                                            extra.getString("xPaddingKey", ignoreCase = true)?.also {
                                                                xPaddingKey = it
                                                            }
                                                            extra.getString("xPaddingHeader", ignoreCase = true)?.also {
                                                                xPaddingHeader = it
                                                            }
                                                            extra.getString("xPaddingPlacement", ignoreCase = true)?.also {
                                                                xPaddingPlacement = it
                                                            }
                                                            extra.getString("xPaddingMethod", ignoreCase = true)?.also {
                                                                xPaddingMethod = it
                                                            }
                                                            extra.getString("uplinkHTTPMethod", ignoreCase = true)?.also {
                                                                uplinkHTTPMethod = it
                                                            }
                                                            extra.getString("sessionIDPlacement", ignoreCase = true)?.also {
                                                                sessionIDPlacement = it
                                                            }
                                                            extra.getString("sessionIDKey", ignoreCase = true)?.also {
                                                                sessionIDKey = it
                                                            }
                                                            extra.getString("sessionIDTable", ignoreCase = true)?.also {
                                                                sessionIDTable = it
                                                            }
                                                            extra.getString("sessionIDLength", ignoreCase = true)?.also {
                                                                sessionIDLength = it
                                                            }
                                                            extra.getString("seqPlacement", ignoreCase = true)?.also {
                                                                seqPlacement = it
                                                            }
                                                            extra.getString("seqKey", ignoreCase = true)?.also {
                                                                seqKey = it
                                                            }
                                                            extra.getString("uplinkDataPlacement", ignoreCase = true)?.also {
                                                                uplinkDataPlacement = it
                                                            }
                                                            extra.getString("uplinkDataKey", ignoreCase = true)?.also {
                                                                uplinkDataKey = it
                                                            }
                                                            extra.getInt("uplinkChunkSize", ignoreCase = true)?.also {
                                                                uplinkChunkSize = it.toString()
                                                            } ?: extra.getString("uplinkChunkSize", ignoreCase = true)?.also {
                                                                uplinkChunkSize = it
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        error(e)
                                                    }
                                                }
                                                if (bean.shUseBrowserForwarder) {
                                                    useBrowserForwarding = true
                                                    requireSh = true
                                                }
                                            }
                                        }
                                        "hysteria2" -> {
                                            hy2Settings = Hysteria2Object().apply {
                                                // V2Ray transport is TCP only so it is safe to omit MaxDatagramFrameSize.
                                                omitMaxDatagramFrameSize = true
                                                if (bean.hy2Password.isNotEmpty()) {
                                                    password = bean.hy2Password
                                                }
                                                congestion = Hysteria2Object.CongestionObject().apply {
                                                    if (bean.hy2DownMbps > 0) {
                                                        down_mbps = bean.hy2DownMbps
                                                    }
                                                    if (bean.hy2UpMbps > 0) {
                                                        up_mbps = bean.hy2UpMbps
                                                    }
                                                }
                                            }
                                        }
                                        "mekya" -> {
                                            mekyaSettings = MekyaObject().apply {
                                                kcp = KcpObject().apply {
                                                    mtu = 1350
                                                    tti = 50
                                                    uplinkCapacity = 12
                                                    downlinkCapacity = 100
                                                    congestion = false
                                                    readBufferSize = 1
                                                    writeBufferSize = 1
                                                    header = KcpObject.HeaderObject().apply {
                                                        type = bean.mekyaKcpHeaderType
                                                    }
                                                    if (bean.mKcpSeed.isNotEmpty()) {
                                                        seed = bean.mekyaKcpSeed
                                                    }
                                                }
                                                if (bean.mekyaUrl.isNotEmpty()) {
                                                    url = bean.mekyaUrl
                                                }
                                                // magic values from https://github.com/v2fly/v2ray-core/pull/3120
                                                maxWriteDelay = 80
                                                maxRequestSize = 96000
                                                pollingIntervalInitial = 200
                                                h2PoolSize = 8
                                            }
                                        }
                                    }
                                    if (DataStore.enableFragment
                                        && (network != "kcp" && network != "quic" && network != "hysteria2")
                                        && (security == "tls" || security == "reality")
                                        && !(bean is ShadowsocksBean && bean.plugin.isNotEmpty() && PluginConfiguration(bean.plugin).selected.isNotEmpty())
                                        && !(network == "ws" && bean.wsUseBrowserForwarder)
                                        && !(network == "splithttp" && bean.shUseBrowserForwarder)
                                    ) {
                                        sockopt = StreamSettingsObject.SockoptObject().apply {
                                            tlsFragmentation = StreamSettingsObject.SockoptObject.TLSFragmentationObject().apply {
                                                when (DataStore.fragmentMethod) {
                                                    TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION -> {
                                                        tlsRecordFragmentation = true
                                                    }
                                                    TLS_FRAGMENTATION_METHOD.TCP_SEGMENTATION -> {
                                                        tcpSegmentation = true
                                                    }
                                                    TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION -> {
                                                        tlsRecordFragmentation = true
                                                        tcpSegmentation = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is ShadowsocksRBean) {
                                protocol = "shadowsocks"
                                settings = LazyOutboundConfigurationObject(this,
                                    ShadowsocksOutboundConfigurationObject().apply {
                                        servers = listOf(ShadowsocksOutboundConfigurationObject.ServerObject().apply {
                                                address = bean.serverAddress
                                                port = bean.serverPort
                                                method = bean.method
                                                password = bean.password
                                            }
                                        )
                                        plugin = "shadowsocksr"
                                        pluginArgs = listOf(
                                            "--obfs=${bean.obfs}",
                                            "--obfs-param=${bean.obfsParam}",
                                            "--protocol=${bean.protocol}",
                                            "--protocol-param=${bean.protocolParam}"
                                        )
                                    }
                                )
                            } else if (bean is WireGuardBean) {
                                protocol = "wireguard"
                                settings = LazyOutboundConfigurationObject(this,
                                    WireGuardOutboundConfigurationObject().apply {
                                        address = bean.localAddress.listByLineOrComma()
                                        secretKey = bean.privateKey
                                        mtu = bean.mtu
                                        val values = bean.reserved.listByLineOrComma()
                                        if (values.size == 3) {
                                            val reserved0 = values[0].toUByteOrNull()
                                            val reserved1 = values[1].toUByteOrNull()
                                            val reserved2 = values[2].toUByteOrNull()
                                            if (reserved0 != null && reserved1 != null && reserved2 != null) {
                                                reserved = listOf(reserved0.toInt(), reserved1.toInt(), reserved2.toInt())
                                            }
                                        } else {
                                            val array = Base64.decode(bean.reserved)
                                            if (array.size == 3) {
                                                reserved = listOf(array[0].toUByte().toInt(), array[1].toUByte().toInt(), array[2].toUByte().toInt())
                                            }
                                        }
                                        peers = listOf(WireGuardOutboundConfigurationObject.WireGuardPeerObject().apply {
                                            publicKey = bean.peerPublicKey
                                            if (bean.peerPreSharedKey.isNotEmpty()) {
                                                preSharedKey = bean.peerPreSharedKey
                                            }
                                            if (bean.keepaliveInterval > 0) {
                                                keepAlive = bean.keepaliveInterval
                                            }
                                            endpoint = joinHostPort(bean.serverAddress, bean.serverPort)
                                        })
                                    })
                                if (currentDomainStrategy == "AsIs") {
                                    currentDomainStrategy = "UseIP"
                                }
                            } else if (bean is SSHBean) {
                                protocol = "ssh"
                                settings = LazyOutboundConfigurationObject(this,
                                    SSHOutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        port = bean.serverPort
                                        user = bean.username
                                        when (bean.authType) {
                                            SSHBean.AUTH_TYPE_PUBLIC_KEY -> {
                                                privateKey = bean.privateKey
                                                if (bean.privateKeyPassphrase.isNotEmpty()) {
                                                    privateKeyPassphrase = bean.privateKeyPassphrase
                                                }
                                            }
                                            SSHBean.AUTH_TYPE_PASSWORD -> {
                                                password = bean.password
                                            }
                                        }
                                        if (bean.publicKey.isNotEmpty()) {
                                            publicKey = bean.publicKey
                                        }
                                        if (bean.keepaliveInterval > 0) {
                                            keepaliveInterval = bean.keepaliveInterval
                                        }
                                    })
                            } else if (bean is Hysteria2Bean) {
                                protocol = "hysteria2"
                                settings = LazyOutboundConfigurationObject(this,
                                    Hysteria2OutboundConfigurationObject().apply {
                                        servers = listOf(Hysteria2OutboundConfigurationObject.ServerObject().apply {
                                            address = bean.serverAddress
                                            port = bean.serverPorts.toHysteriaPort()
                                        })
                                    }
                                )
                                streamSettings = StreamSettingsObject().apply {
                                    network = "hysteria2"
                                    security = "tls"
                                    hy2Settings = Hysteria2Object().apply {
                                        use_udp_extension = true
                                        if (DataStore.hysteria2OmitMaxDatagramFrameSize || bean.omitMaxDatagramFrameSize) {
                                            omitMaxDatagramFrameSize = true
                                        }
                                        if (bean.auth.isNotEmpty()) {
                                            password = bean.auth
                                        }
                                        congestion = Hysteria2Object.CongestionObject().apply {
                                            if (bean.downloadMbps > 0) {
                                                down_mbps = bean.downloadMbps
                                            }
                                            if (bean.uploadMbps > 0) {
                                                up_mbps = bean.uploadMbps
                                            }
                                            type = bean.congestionControl
                                            if (bean.congestionControl == "bbr") {
                                                bbrProfile = bean.bbrProfile
                                            }
                                        }
                                        if (bean.obfsType.isNotEmpty()) {
                                            obfs = Hysteria2Object.OBFSObject().apply {
                                                type = bean.obfsType
                                                password = bean.obfsPassword
                                                if (bean.obfsType == "gecko") {
                                                    if (bean.geckoMinPacketSize > 0) {
                                                        minPacketSize = bean.geckoMinPacketSize
                                                    }
                                                    if (bean.geckoMaxPacketSize > 0) {
                                                        maxPacketSize = bean.geckoMaxPacketSize
                                                    }
                                                }
                                            }
                                        }
                                        if (bean.serverPorts.isNotEmpty() && bean.serverPorts.isValidHysteriaMultiPort()) {
                                            hopPorts = bean.serverPorts
                                            if (bean.hopInterval > 0) {
                                                hopInterval = bean.hopInterval
                                            } else if (bean.hopIntervalMin > 0 || bean.hopIntervalMax > 0) {
                                                hopIntervalMin = bean.hopIntervalMin
                                                hopIntervalMax = bean.hopIntervalMax
                                            }
                                        }
                                    }
                                    tlsSettings = TLSObject().apply {
                                        if (bean.sni.isNotEmpty()) {
                                            serverName = bean.sni
                                        }
                                        if (bean.allowInsecure) {
                                            allowInsecure = true
                                        }
                                        if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                            pinnedPeerCertificateSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateSha256.add(it.replace(":", "").replace("-", ""))
                                            }
                                        }
                                        if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                            pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                        }
                                        if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                            pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                        }
                                        if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                            certificates = mutableListOf()
                                            if (bean.certificates.isNotEmpty()) {
                                                disableSystemRoot = true
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "verify"
                                                    certificate = bean.certificates.lines()
                                                })
                                            }
                                            if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "encipherment"
                                                    certificate = bean.mtlsCertificate.lines()
                                                    key = bean.mtlsCertificatePrivateKey.lines()
                                                })
                                            }
                                        }
                                        if (bean.echEnabled) {
                                            ech = TLSObject.ECHObject().apply {
                                                enabled = bean.echEnabled
                                                if (bean.echConfig.isNotEmpty()) {
                                                    config = bean.echConfig
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is Tuic5Bean) {
                                protocol = "tuic"
                                settings = LazyOutboundConfigurationObject(this,
                                    V2RayConfig.TUICOutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        port = bean.serverPort
                                        uuid = bean.uuid
                                        password = bean.password
                                        congestionControl = bean.congestionControl
                                        udpRelayMode = bean.udpRelayMode
                                        if (bean.zeroRTTHandshake) zeroRTTHandshake = bean.zeroRTTHandshake
                                        if (bean.disableSNI) disableSNI = bean.disableSNI
                                        if (bean.singUDPOverStream && DataStore.experimentalFlagsProperties.getBooleanProperty("singuot")) {
                                            udpOverStream = bean.singUDPOverStream
                                        }
                                    }
                                )
                                streamSettings = StreamSettingsObject().apply {
                                    security = "tls"
                                    tlsSettings = TLSObject().apply {
                                        if (bean.sni.isNotEmpty()) {
                                            serverName = bean.sni
                                        }
                                        if (bean.alpn.isNotEmpty()) {
                                            alpn = bean.alpn.listByLineOrComma()
                                        }
                                        if (bean.allowInsecure) {
                                            allowInsecure = true
                                        }
                                        if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                            certificates = mutableListOf()
                                            if (bean.certificates.isNotEmpty()) {
                                                disableSystemRoot = true
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "verify"
                                                    certificate = bean.certificates.lines()
                                                })
                                            }
                                            if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "encipherment"
                                                    certificate = bean.mtlsCertificate.lines()
                                                    key = bean.mtlsCertificatePrivateKey.lines()
                                                })
                                            }
                                        }
                                        if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                            pinnedPeerCertificateSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                            }
                                        }
                                        if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                            pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                        }
                                        if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                            pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                        }
                                        if (bean.echEnabled) {
                                            ech = TLSObject.ECHObject().apply {
                                                enabled = bean.echEnabled
                                                if (bean.echConfig.isNotEmpty()) {
                                                    config = bean.echConfig
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is Http3Bean) {
                                protocol = "http3"
                                settings = LazyOutboundConfigurationObject(this,
                                    V2RayConfig.HTTP3OutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        port = bean.serverPort
                                        if (bean.username.isNotEmpty()) username = bean.username
                                        if (bean.password.isNotEmpty()) password = bean.password
                                    }
                                )
                                streamSettings = StreamSettingsObject().apply {
                                    security = "tls"
                                    tlsSettings = TLSObject().apply {
                                        if (bean.sni.isNotEmpty()) {
                                            serverName = bean.sni
                                        }
                                        if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                            certificates = mutableListOf()
                                            if (bean.certificates.isNotEmpty()) {
                                                disableSystemRoot = true
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "verify"
                                                    certificate = bean.certificates.lines()
                                                })
                                            }
                                            if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "encipherment"
                                                    certificate = bean.mtlsCertificate.lines()
                                                    key = bean.mtlsCertificatePrivateKey.lines()
                                                })
                                            }
                                        }
                                        if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                            pinnedPeerCertificateSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                            }
                                        }
                                        if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                            pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                        }
                                        if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                            pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                        }
                                        if (bean.allowInsecure) {
                                            allowInsecure = true
                                        }
                                        if (bean.echEnabled) {
                                            ech = TLSObject.ECHObject().apply {
                                                enabled = bean.echEnabled
                                                if (bean.echConfig.isNotEmpty()) {
                                                    config = bean.echConfig
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is AnyTLSBean) {
                                protocol = "anytls"
                                settings = LazyOutboundConfigurationObject(this,
                                    V2RayConfig.AnyTLSOutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        port = bean.serverPort
                                        if (bean.password.isNotEmpty()) password = bean.password
                                        idleSessionCheckInterval = bean.idleSessionCheckInterval
                                        idleSessionTimeout = bean.idleSessionTimeout
                                        minIdleSession = bean.minIdleSession
                                    }
                                )
                                streamSettings = StreamSettingsObject().apply {
                                    if (bean.security.isNotEmpty()) {
                                        security = bean.security
                                    }
                                    when (security) {
                                        "tls" -> {
                                            tlsSettings = TLSObject().apply {
                                                if (bean.sni.isNotEmpty()) {
                                                    serverName = bean.sni
                                                }
                                                if (bean.alpn.isNotEmpty()) {
                                                    alpn = bean.alpn.listByLineOrComma()
                                                }
                                                if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                    certificates = mutableListOf()
                                                    if (bean.certificates.isNotEmpty()) {
                                                        disableSystemRoot = true
                                                        certificates.add(TLSObject.CertificateObject().apply {
                                                            usage = "verify"
                                                            certificate = bean.certificates.lines()
                                                        })
                                                    }
                                                    if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                        certificates.add(TLSObject.CertificateObject().apply {
                                                            usage = "encipherment"
                                                            certificate = bean.mtlsCertificate.lines()
                                                            key = bean.mtlsCertificatePrivateKey.lines()
                                                        })
                                                    }
                                                }
                                                if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                                    pinnedPeerCertificateSha256 = mutableListOf()
                                                    bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                        pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                                    }
                                                }
                                                if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                                    pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                                }
                                                if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                                    pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                                }
                                                if (bean.allowInsecure) {
                                                    allowInsecure = true
                                                }
                                                val overrideFingerprint = DataStore.experimentalFlagsProperties.getProperty("overrideUTLSFingerprintForTLS")
                                                if (!overrideFingerprint.isNullOrEmpty()) {
                                                    fingerprint = overrideFingerprint
                                                } else if (bean.utlsFingerprint.isNotEmpty()) {
                                                    fingerprint = bean.utlsFingerprint
                                                }
                                                if (bean.echEnabled) {
                                                    ech = TLSObject.ECHObject().apply {
                                                        enabled = bean.echEnabled
                                                        if (bean.echConfig.isNotEmpty()) {
                                                            config = bean.echConfig
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "reality" -> {
                                            realitySettings = RealityObject().apply {
                                                if (bean.sni.isNotEmpty()) {
                                                    serverName = bean.sni
                                                }
                                                if (bean.realityPublicKey.isNotEmpty()) {
                                                    publicKey = bean.realityPublicKey
                                                }
                                                if (bean.realityShortId.isNotEmpty()) {
                                                    shortId = bean.realityShortId
                                                }
                                                val overrideFingerprint = DataStore.experimentalFlagsProperties.getProperty("overrideUTLSFingerprintForREALITY")
                                                if (!overrideFingerprint.isNullOrEmpty()) {
                                                    fingerprint = overrideFingerprint
                                                } else if (bean.realityFingerprint.isNotEmpty()) {
                                                    fingerprint = bean.realityFingerprint
                                                }
                                                if (DataStore.realityDisableX25519Mlkem768 || bean.realityDisableX25519Mlkem768 && !forExport) {
                                                    disableX25519MLKEM768 = true
                                                }
                                            }
                                        }
                                    }
                                    if (DataStore.enableFragment) {
                                        sockopt = StreamSettingsObject.SockoptObject().apply {
                                            tlsFragmentation = StreamSettingsObject.SockoptObject.TLSFragmentationObject().apply {
                                                when (DataStore.fragmentMethod) {
                                                    TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION -> {
                                                        tlsRecordFragmentation = true
                                                    }
                                                    TLS_FRAGMENTATION_METHOD.TCP_SEGMENTATION -> {
                                                        tcpSegmentation = true
                                                    }
                                                    TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION -> {
                                                        tlsRecordFragmentation = true
                                                        tcpSegmentation = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is JuicityBean) {
                                protocol = "juicity"
                                settings = LazyOutboundConfigurationObject(this, V2RayConfig.JuicityOutboundConfigurationObject().apply {
                                    address = bean.serverAddress
                                    port = bean.serverPort
                                    uuid = bean.uuid
                                    password = bean.password
                                })
                                streamSettings = StreamSettingsObject().apply {
                                    security = "tls"
                                    tlsSettings = TLSObject().apply {
                                        if (bean.sni.isNotEmpty()) {
                                            serverName = bean.sni
                                        }
                                        if (bean.allowInsecure) {
                                            allowInsecure = true
                                        }
                                        if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                            pinnedPeerCertificateSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                            }
                                        }
                                        if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                            pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                        }
                                        if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                            pinnedPeerCertificateChainSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateChainSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateChainSha256.add(
                                                    when {
                                                        it.length == 64 -> {
                                                            Base64.encode(bean.pinnedPeerCertificateChainSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                                                        }
                                                        else -> {
                                                            bean.pinnedPeerCertificateChainSha256.replace('_', '/').replace('-', '+')
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                        if (bean.certificates.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                            certificates = mutableListOf()
                                            if (bean.certificates.isNotEmpty()) {
                                                disableSystemRoot = true
                                                certificates.add(
                                                    TLSObject.CertificateObject().apply {
                                                        usage = "verify"
                                                        certificate = bean.certificates.lines()
                                                    })
                                            }
                                            if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "encipherment"
                                                    certificate = bean.mtlsCertificate.lines()
                                                    key = bean.mtlsCertificatePrivateKey.lines()
                                                })
                                            }
                                        }
                                        if (bean.echEnabled) {
                                            ech = TLSObject.ECHObject().apply {
                                                enabled = bean.echEnabled
                                                if (bean.echConfig.isNotEmpty()) {
                                                    config = bean.echConfig
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (bean is SnellBean) {
                                protocol = "snell"
                                settings = LazyOutboundConfigurationObject(this, V2RayConfig.SnellOutboundConfigurationObject().apply {
                                    address = bean.serverAddress
                                    port = bean.serverPort
                                    psk = bean.psk
                                    version = bean.version
                                    reuse = bean.reuse
                                    if (version == SnellBean.VERSION_4) {
                                        obfsMode = bean.obfsMode
                                        if (bean.obfsMode != SnellBean.OBFS_NONE && bean.obfsHost.isNotEmpty()) {
                                            obfsHost = bean.obfsHost
                                        }
                                    }
                                    if (version == SnellBean.VERSION_6) {
                                        mode = bean.mode
                                    }
                                    if (bean.userKey.isNotEmpty()) {
                                        userKey = bean.userKey
                                    }
                                })
                            } else if (bean is MieruBean) {
                                protocol = "mieru"
                                settings = LazyOutboundConfigurationObject(this,
                                    V2RayConfig.MieruOutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        if (bean.portRange.isNotEmpty()) {
                                            portRange = bean.portRange.listByLineOrComma()
                                        } else {
                                            port = bean.serverPort
                                        }
                                        username = bean.username
                                        password = bean.password
                                        when (bean.protocol) {
                                            MieruBean.PROTOCOL_TCP -> protocol = "tcp"
                                            MieruBean.PROTOCOL_UDP -> protocol = "udp"
                                        }
                                        when (bean.multiplexingLevel) {
                                            MieruBean.MULTIPLEXING_DEFAULT -> multiplexing = "default"
                                            MieruBean.MULTIPLEXING_OFF -> multiplexing = "off"
                                            MieruBean.MULTIPLEXING_LOW -> multiplexing = "low"
                                            MieruBean.MULTIPLEXING_MIDDLE -> multiplexing = "middle"
                                            MieruBean.MULTIPLEXING_HIGH -> multiplexing = "high"
                                        }
                                        when (bean.handshakeMode) {
                                            MieruBean.HANDSHAKE_DEFAULT -> handshakeMode = "default"
                                            MieruBean.HANDSHAKE_STANDARD -> handshakeMode = "standard"
                                            MieruBean.HANDSHAKE_NO_WAIT -> handshakeMode = "nowait"
                                        }
                                        if (bean.trafficPattern.isNotEmpty()) {
                                            trafficPattern = bean.trafficPattern
                                        }
                                    }
                                )
                            } else if (bean is TrustTunnelBean) {
                                protocol = "trusttunnel"
                                settings = LazyOutboundConfigurationObject(this,
                                    V2RayConfig.TrustTunnelOutboundConfigurationObject().apply {
                                        address = bean.serverAddress
                                        port = bean.serverPort
                                        if (bean.username.isNotEmpty()) username = bean.username
                                        if (bean.password.isNotEmpty()) password = bean.password
                                        when (bean.protocol) {
                                            "https" -> {}
                                            "quic" -> http3 = true
                                            else -> error("invalid")
                                        }
                                        if (bean.serverNameToVerify.isNotEmpty()) {
                                            serverNameToVerify = bean.serverNameToVerify
                                        }
                                    }
                                )
                                streamSettings = StreamSettingsObject().apply {
                                    security = "tls"
                                    tlsSettings = TLSObject().apply {
                                        if (bean.sni.isNotEmpty()) {
                                            serverName = bean.sni
                                        }
                                        if (bean.certificate.isNotEmpty() || bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                            certificates = mutableListOf()
                                            if (bean.certificate.isNotEmpty()) {
                                                disableSystemRoot = true
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "verify"
                                                    certificate = bean.certificate.lines()
                                                })
                                            }
                                            if (bean.mtlsCertificate.isNotEmpty() || bean.mtlsCertificatePrivateKey.isNotEmpty()) {
                                                certificates.add(TLSObject.CertificateObject().apply {
                                                    usage = "encipherment"
                                                    certificate = bean.mtlsCertificate.lines()
                                                    key = bean.mtlsCertificatePrivateKey.lines()
                                                })
                                            }
                                        }
                                        if (bean.pinnedPeerCertificateSha256.isNotEmpty()) {
                                            pinnedPeerCertificateSha256 = mutableListOf()
                                            bean.pinnedPeerCertificateSha256.listByLineOrComma().forEach {
                                                pinnedPeerCertificateSha256.add(it.replace(":", ""))
                                            }
                                        }
                                        if (bean.pinnedPeerCertificatePublicKeySha256.isNotEmpty()) {
                                            pinnedPeerCertificatePublicKeySha256 = bean.pinnedPeerCertificatePublicKeySha256.listByLineOrComma()
                                        }
                                        if (bean.pinnedPeerCertificateChainSha256.isNotEmpty()) {
                                            pinnedPeerCertificateChainSha256 = bean.pinnedPeerCertificateChainSha256.listByLineOrComma()
                                        }
                                        if (bean.allowInsecure) {
                                            allowInsecure = true
                                        }
                                        if (protocol == "https") {
                                            val overrideFingerprint = DataStore.experimentalFlagsProperties.getProperty("overrideUTLSFingerprintForTLS")
                                            if (!overrideFingerprint.isNullOrEmpty()) {
                                                fingerprint = overrideFingerprint
                                            } else if (bean.utlsFingerprint.isNotEmpty()) {
                                                fingerprint = bean.utlsFingerprint
                                            }
                                        }
                                        if (bean.echEnabled) {
                                            ech = TLSObject.ECHObject().apply {
                                                enabled = bean.echEnabled
                                                if (bean.echConfig.isNotEmpty()) {
                                                    config = bean.echConfig
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (bean is StandardV2RayBean && bean.mux) {
                                mux = OutboundObject.MuxObject().apply {
                                    enabled = true
                                    concurrency = bean.muxConcurrency
                                    when (bean.muxPacketEncoding) {
                                        "packet" -> {
                                            packetEncoding = "packet"
                                        }
                                        "xudp" -> {
                                            packetEncoding = "xudp"
                                        }
                                    }
                                }
                            }
                            if ((bean is ShadowsocksBean || bean is TrojanBean || bean is VMessBean || bean is VLESSBean)
                                && bean.singMux && DataStore.experimentalFlagsProperties.getBooleanProperty("singmux")) {
                                smux = OutboundObject.SmuxObject().apply {
                                    enabled = bean.singMux
                                    protocol = bean.singMuxProtocol
                                    if (bean.singMuxMaxConnections > 0) {
                                        maxConnections = bean.singMuxMaxConnections
                                    }
                                    if (bean.singMuxMinStreams > 0) {
                                        minStreams = bean.singMuxMinStreams
                                    }
                                    if (bean.singMuxMaxStreams > 0) {
                                        maxStreams = bean.singMuxMaxStreams
                                    }
                                    if (bean.singMuxPadding) {
                                        padding = bean.singMuxPadding
                                    }
                                }
                            }
                        }
                    }

                    if (currentDomainStrategy != "AsIs") {
                        currentOutbound.domainStrategy = currentDomainStrategy
                    }

                    if (!(currentOutbound.domainStrategy == null && DataStore.outboundDomainStrategyForServer == "AsIs")
                        && !(currentOutbound.domainStrategy == "AsIs" && DataStore.outboundDomainStrategyForServer == "AsIs")
                        && currentOutbound.domainStrategy != DataStore.outboundDomainStrategyForServer) {
                        currentOutbound.dialDomainStrategy = DataStore.outboundDomainStrategyForServer
                    }

                    if (bean is ConfigBean && bean.type == "v2ray_outbound") {
                        currentOutbound = gson.fromJson(bean.content, OutboundObject::class.java).apply { init() }
                    }

                    currentOutbound.tag = tagIn

                }

                if (!isBalancer && index > 0) {
                    if (!pastExternal) {
                        pastOutbound.proxySettings = OutboundObject.ProxySettingsObject().apply {
                            tag = tagIn
                            transportLayer = true
                        }
                    } else {
                        routing.rules.add(RoutingObject.RuleObject().apply {
                            type = "field"
                            inboundTag = listOf(pastInboundTag)
                            outboundTag = tagIn
                        })
                    }
                }

                if (proxyEntity.needExternal() && !isBalancer && index != profileList.lastIndex) {
                    val mappingPort = mkPort()
                    bean.finalAddress = LOCALHOST
                    bean.finalPort = mappingPort
                    bean.isChain = true

                    inbounds.add(InboundObject().apply {
                        listen = LOCALHOST
                        port = mappingPort
                        tag = "$tagOutbound-mapping-${proxyEntity.id}"
                        protocol = "dokodemo-door"
                        settings = LazyInboundConfigurationObject(this,
                            DokodemoDoorInboundConfigurationObject().apply {
                                address = bean.serverAddress
                                network = bean.network()
                                port = bean.serverPort
                            })

                        pastInboundTag = tag
                    })
                } else if (bean.canMapping() && proxyEntity.needExternal()) {
                    val mappingPort = mkPort()
                    bean.finalAddress = LOCALHOST
                    bean.finalPort = mappingPort

                    inbounds.add(InboundObject().apply {
                        listen = LOCALHOST
                        port = mappingPort
                        tag = "$tagOutbound-mapping-${proxyEntity.id}"
                        protocol = "dokodemo-door"
                        settings = LazyInboundConfigurationObject(this,
                            DokodemoDoorInboundConfigurationObject().apply {
                                address = bean.serverAddress
                                network = bean.network()
                                port = bean.serverPort
                            })
                        routing.rules.add(RoutingObject.RuleObject().apply {
                            type = "field"
                            inboundTag = listOf(tag)
                            outboundTag = TAG_DIRECT
                        })
                    })
                    hasTagDirect = true
                }

                if (!needGlobal) {
                    outbounds.add(currentOutbound)
                    chainOutbounds.add(currentOutbound)
                    pastExternal = proxyEntity.needExternal()
                    pastOutbound = currentOutbound
                }

            }

            if (isBalancer) {
                val balancerBean = balancer()!!

                // Check if we need to apply landing proxy for TYPE_GROUP balancer
                val shouldUseLandingProxy = balancerBean.type == BalancerBean.TYPE_GROUP &&
                                            balancerBean.useLandingProxy == true

                if (shouldUseLandingProxy) {
                    // Get the group and landing proxy
                    val group = SagerDatabase.groupDao.getById(balancerBean.groupId)
                    val landingProxyEntity = if (group != null && group.landingProxy > 0L) {
                        SagerDatabase.proxyDao.getById(group.landingProxy)
                    } else null

                    if (landingProxyEntity != null) {
                        // Validate landing proxy
                        when (landingProxyEntity.type) {
                            ProxyEntity.TYPE_BALANCER -> error("balancer can not be the landing proxy")
                            ProxyEntity.TYPE_CONFIG -> if (landingProxyEntity.configBean!!.type == "v2ray")
                                error("custom config can not be the landing proxy")
                        }
                        if (!landingProxyEntity.requireBean().canMapping()) {
                            error("${landingProxyEntity.displayName()} can be the front proxy only and can not be the landing proxy")
                        }

                        // Get landing proxy chain
                        val landingProxyList = when (landingProxyEntity.type) {
                            ProxyEntity.TYPE_CHAIN -> landingProxyEntity.resolveChainRecursively()
                            else -> mutableListOf(landingProxyEntity)
                        }

                        // For each proxy in profileList, we need to create a chain with landing proxy
                        // We'll rebuild chainOutbounds with chains
                        val originalProxies = profileList.toList()
                        chainOutbounds.clear()

                        for (mainProxy in originalProxies) {
                            // Create a chain: landingProxyList + mainProxy
                            // Landing proxy goes first to bypass whitelists, then mainProxy
                            val chainList = landingProxyList.toMutableList()
                            chainList.add(mainProxy)

                            // Build this chain as a sub-chain
                            val chainTag = buildChain(
                                "$tagOutbound-chain-${mainProxy.id}",
                                chainList,
                                false, // not a balancer
                                { null }
                            )

                            // Find the first outbound of this chain and add it to chainOutbounds
                            val chainFirstOutbound = outbounds.findLast { it.tag == chainTag }
                            if (chainFirstOutbound != null) {
                                chainOutbounds.add(chainFirstOutbound)
                            }
                        }
                    }
                }

                // Check if we need to apply front proxy for TYPE_GROUP balancer
                val shouldUseFrontProxy = balancerBean.type == BalancerBean.TYPE_GROUP &&
                                            balancerBean.useFrontProxy == true

                if (shouldUseFrontProxy) {
                    // Get the group and front proxy
                    val group = SagerDatabase.groupDao.getById(balancerBean.groupId)
                    val frontProxyEntity = if (group != null && group.frontProxy > 0L) {
                        SagerDatabase.proxyDao.getById(group.frontProxy)
                    } else null

                    if (frontProxyEntity != null) {
                        // Validate front proxy
                        when (frontProxyEntity.type) {
                            ProxyEntity.TYPE_BALANCER -> error("balancer can not be the front proxy")
                            ProxyEntity.TYPE_CONFIG -> if (frontProxyEntity.configBean!!.type == "v2ray")
                                error("custom config can not be the front proxy")
                        }

                        // Get front proxy chain
                        val frontProxyList = when (frontProxyEntity.type) {
                            ProxyEntity.TYPE_CHAIN -> frontProxyEntity.resolveChainRecursively()
                            else -> mutableListOf(frontProxyEntity)
                        }

                        // For each proxy in profileList, we need to create a chain with front proxy
                        // We'll rebuild chainOutbounds with chains
                        val originalProxies = profileList.toList()
                        chainOutbounds.clear()

                        for (mainProxy in originalProxies) {
                            // Create a chain: mainProxy + frontProxyList
                            // Main proxy goes first, then front proxy
                            val chainList = mutableListOf(mainProxy)
                            chainList.addAll(frontProxyList)

                            // Build this chain as a sub-chain
                            val chainTag = buildChain(
                                "$tagOutbound-chain-${mainProxy.id}",
                                chainList,
                                false, // not a balancer
                                { null }
                            )

                            // Find the first outbound of this chain and add it to chainOutbounds
                            val chainFirstOutbound = outbounds.findLast { it.tag == chainTag }
                            if (chainFirstOutbound != null) {
                                chainOutbounds.add(chainFirstOutbound)
                            }
                        }
                    }
                }

                val observatory = ObservatoryObject().apply {
                    probeURL = balancerBean.probeUrl.ifEmpty {
                        DataStore.connectionTestURL
                    }
                    if (balancerBean.probeInterval > 0) {
                        probeInterval = "${balancerBean.probeInterval}s"
                    }
                    enableConcurrency = true
                    subjectSelector = HashSet(chainOutbounds.map { it.tag })
                }
                val observatoryItem = MultiObservatoryObject.MultiObservatoryItem().apply {
                    tag = "observer-$tagOutbound"
                    settings = mutableMapOf<String, Any>()
                    settings["probeURL"] = observatory.probeURL
                    settings["probeInterval"] = observatory.probeInterval
                    settings["enableConcurrency"] = observatory.enableConcurrency
                    settings["subjectSelector"] = observatory.subjectSelector
                }
                if (multiObservatory == null) multiObservatory = MultiObservatoryObject().apply {
                    observers = mutableListOf()
                }
                multiObservatory.observers.add(observatoryItem)

                if (routing.balancers == null) routing.balancers = ArrayList()
                routing.balancers.add(RoutingObject.BalancerObject().apply {
                    tag = "balancer-$tagOutbound"
                    selector = chainOutbounds.map { it.tag }
                    if (multiObservatory == null) {
                        multiObservatory = MultiObservatoryObject().apply {
                            observers = mutableListOf()
                        }
                    }
                    strategy = StrategyObject().apply {
                        type = balancerBean.strategy.takeIf { it.isNotEmpty() } ?: "random"
                        when (type) {
                            "leastPing", "leastLoad" -> {
                                settings = StrategyObject.strategyConfig().apply {
                                    observerTag = "observer-$tagOutbound"
                                }
                            }
                            else -> {
                                settings = StrategyObject.strategyConfig().apply {
                                    observerTag = "observer-$tagOutbound"
                                    aliveOnly = true
                                }
                            }
                        }
                    }
                })
                if (tagOutbound == TAG_AGENT) {
                    if (observatoryItem.settings["probeURL"] == DataStore.connectionTestURL) {
                        rootObserver = observatoryItem
                    }
                    // if all outbounds of a balancer are dead, the first (default) outbound will be used
                    rootBalancer = RoutingObject.RuleObject().apply {
                        type = "field"
                        network = "tcp,udp"
                        balancerTag = "balancer-$tagOutbound"
                    }
                }
            }

            return chainOutbound

        }

        val mainIsBalancer = proxy.balancerBean != null

        val tagProxy = buildChain(
            TAG_AGENT, proxies, mainIsBalancer
        ) { proxy.balancerBean }

        val balancerMap = mutableMapOf<Long, String>()
        val tagMap = mutableMapOf<Long, String>()
        extraProxies.forEach { (key, entities) ->
            val (id, balancer) = key
            val (isBalancer, balancerBean) = balancer
            tagMap[id] = buildChain("$TAG_AGENT-$id", entities, isBalancer, balancerBean::value)
            if (isBalancer) {
                balancerMap[id] = "balancer-$TAG_AGENT-$id"
            }
        }

        val isVpn = DataStore.serviceMode == Key.MODE_VPN

        for (rule in extraRules) {
            val uidList = mutableListOf<Int>()
            if (rule.packages.isNotEmpty() || rule.customPackageNames.isNotEmpty()) {
                if (!isVpn) {
                    alerts.add(Alerts.ROUTE_ALERT_NOT_VPN to rule.displayName())
                    continue
                }
                PackageCache.awaitLoadSync()
                if (rule.customPackageNames.isNotEmpty()) {
                    rule.customPackageNames.forEach {
                        it.toIntOrNull()?.let {
                            uidList.add(it)
                        } ?: PackageCache[it]?.let {
                            uidList.add(it)
                        }
                    }
                } else {
                    rule.packages.forEach {
                        PackageCache[it]?.let {
                            uidList.add(it)
                        }
                    }
                }
                if (uidList.isEmpty()) {
                    alerts.add(Alerts.ROUTE_ALERT_ALL_PACKAGES_UNINSTALLED to rule.displayName())
                    continue
                }
            }
            routing.rules.add(RoutingObject.RuleObject().apply {
                type = "field"
                if (uidList.isNotEmpty()) {
                    uid = uidList
                }

                if (!forExport && !forTest && rule.ssid.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val isLocationPermissionGranted = app.checkSelfPermission(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Manifest.permission.ACCESS_FINE_LOCATION
                        } else {
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        }
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!isLocationPermissionGranted) {
                        throw Alerts.RouteAlertException(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Alerts.ROUTE_ALERT_NEED_FINE_LOCATION_ACCESS
                            } else {
                                Alerts.ROUTE_ALERT_NEED_COARSE_LOCATION_ACCESS
                            }, rule.displayName()
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        throw Alerts.RouteAlertException(
                            Alerts.ROUTE_ALERT_NEED_BACKGROUND_LOCATION_ACCESS, rule.displayName()
                        )
                    }
                    val isLocationServiceEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        SagerNet.location.isLocationEnabled
                    } else {
                        try {
                            @Suppress("DEPRECATION")
                            Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
                        } catch (e: Settings.SettingNotFoundException) {
                            e.printStackTrace()
                            false
                        }
                    }
                    if (!isLocationServiceEnabled) {
                        throw Alerts.RouteAlertException(
                            Alerts.ROUTE_ALERT_LOCATION_DISABLED, rule.displayName()
                        )
                    }
                }

                if (rule.domains.isNotEmpty()) {
                    domains = rule.domains.listByLineOrComma()
                }
                if (rule.ip.isNotEmpty()) {
                    ip = rule.ip.listByLineOrComma()
                }
                if (rule.port.isNotEmpty()) {
                    port = rule.port
                }
                if (rule.sourcePort.isNotEmpty()) {
                    sourcePort = rule.sourcePort
                }
                if (rule.network.isNotEmpty()) {
                    network = rule.network
                }
                if (rule.source.isNotEmpty()) {
                    source = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotEmpty()) {
                    protocol = rule.protocol.listByLineOrComma()
                }
                if (rule.attrs.isNotEmpty()) {
                    attrs = rule.attrs
                }
                if (rule.ssid.isNotEmpty()) {
                    // a hack for SSID containing `\n`
                    ssid = rule.ssid.split("\n").map { it.unescapeLineFeed() }
                }
                if (rule.networkType.isNotEmpty()) {
                    networkType = rule.networkType.toMutableList()
                }
                when {
                    balancerMap.containsKey(rule.outbound) -> {
                        balancerTag = balancerMap[rule.outbound]
                    }
                    mainIsBalancer && rule.outbound == 0L -> balancerTag = "balancer-$TAG_AGENT"
                    else -> {
                        outboundTag = when (val outId = rule.outbound) {
                            0L -> tagProxy
                            -1L -> TAG_BYPASS
                            -2L -> TAG_BLOCK
                            else -> if (outId == proxy.id) tagProxy else {
                                tagMap[outId] ?: error("outbound not found in rule ${rule.displayName()}")
                            }
                        }
                    }
                }
            })
        }

        if (requireWs) {
            browserForwarder = BrowserForwarderObject().apply {
                listenAddr = LOCALHOST
                listenPort = mkPort()
            }
        }

        if (requireSh) {
            browserDialer = BrowserDialerObject().apply {
                listenAddr = LOCALHOST
                listenPort = mkPort()
            }
        }

        if (hasTagDirect) {
            outbounds.add(OutboundObject().apply {
                tag = TAG_DIRECT
                protocol = "freedom"
                if (!forExport && DataStore.interruptReusedConnections && directNeedsInterruption) {
                    settings = LazyOutboundConfigurationObject(this,
                        FreedomOutboundConfigurationObject().apply {
                            interruptConnections = true
                        }
                    )
                }
            })
        }

        outbounds.add(OutboundObject().apply {
            tag = TAG_BYPASS
            protocol = "freedom"
            if (DataStore.enableFragment && DataStore.enableFragmentForDirect) {
                streamSettings = StreamSettingsObject().apply {
                    sockopt = StreamSettingsObject.SockoptObject().apply {
                        tlsFragmentation = StreamSettingsObject.SockoptObject.TLSFragmentationObject().apply {
                            when (DataStore.fragmentMethod) {
                                TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION -> {
                                    tlsRecordFragmentation = true
                                }
                                TLS_FRAGMENTATION_METHOD.TCP_SEGMENTATION -> {
                                    tcpSegmentation = true
                                }
                                TLS_FRAGMENTATION_METHOD.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION -> {
                                    tlsRecordFragmentation = true
                                    tcpSegmentation = true
                                }
                            }
                        }
                    }
                }
            }
            if (DataStore.outboundDomainStrategyForDirect != "AsIs") {
                settings = LazyOutboundConfigurationObject(this,
                    FreedomOutboundConfigurationObject().apply {
                        domainStrategy = DataStore.outboundDomainStrategyForDirect
                    }
                )
            }
        })

        outbounds.add(OutboundObject().apply {
            tag = TAG_BLOCK
            protocol = "blackhole"
        })

        if (!forTest && !forExport) {
            inbounds.add(InboundObject().apply {
                tag = TAG_DNS_IN
                val path = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc_dns.sock"
                val udsFile = File(path)
                if (udsFile.exists()) udsFile.delete()
                listen = path
                protocol = "dokodemo-door"
                settings = LazyInboundConfigurationObject(this,
                    DokodemoDoorInboundConfigurationObject().apply {
                        address = "/ipc_dns.sock" // placeholder, all queries are handled internally
                        network = "unix"
                    }
                )
            })
        }

        if (!forTest && DataStore.requireDnsInbound && DataStore.localDNSPort > 0) {
            inbounds.add(InboundObject().apply {
                tag = TAG_DNS_IN
                listen = bind
                port = DataStore.localDNSPort
                protocol = "dokodemo-door"
                settings = LazyInboundConfigurationObject(this,
                    DokodemoDoorInboundConfigurationObject().apply {
                        address = LOCALHOST // placeholder, all queries are handled internally
                        network = "tcp,udp"
                        port = 53 // placeholder, all queries are handled internally
                    }
                )
                if (shouldDumpUID) dumpUID = true
            })
        }

        outbounds.add(OutboundObject().apply {
            protocol = "dns"
            tag = TAG_DNS_OUT
            settings = LazyOutboundConfigurationObject(this,
                DNSOutboundConfigurationObject().apply {
                    userLevel = 1
                    if (DataStore.experimentalFlagsProperties.getBooleanProperty("lookupAsExchange")) {
                        lookupAsExchange = true
                    }
                })
        })

        val bypassDomain = HashSet<String>()
        val bypassDomainSkipFakeDns = HashSet<String>()
        val proxyDomain = HashSet<String>()
        val bootstrapDomain = HashSet<String>()

        (proxies + extraProxies.values.flatten()).forEach { it ->
            val bean = it.requireBean()
            bean.apply {
                if (bean is ConfigBean && bean.type == "v2ray_outbound") {
                    // too dirty to read server addresses from a custom outbound config
                    // let users provide them manually
                    bean.serverAddresses.listByLineOrComma().forEach {
                        when {
                            it.isEmpty() -> {}
                            !Libexclavecore.isIP(it) -> {
                                bypassDomainSkipFakeDns.add("full:$it")
                            }
                        }
                    }
                } else {
                    if (!Libexclavecore.isIP(serverAddress)) {
                        bypassDomainSkipFakeDns.add("full:$serverAddress")
                    }
                    when (bean) {
                        is StandardV2RayBean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is AnyTLSBean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is Http3Bean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is Hysteria2Bean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is JuicityBean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is Tuic5Bean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                        is TrustTunnelBean -> {
                            if (bean.echEnabled && bean.echConfig.isEmpty() && !Libexclavecore.isIP(bean.sni)) {
                                bypassDomainSkipFakeDns.add("full:${bean.sni}")
                            }
                        }
                    }
                }
            }
        }

        if (DataStore.enableDnsRouting) {
            val directDNSDomainList = DataStore.experimentalFlagsProperties.getProperty("directDNSDomainList")
            if (directDNSDomainList != null) {
                if (!forTest && DataStore.routeMode == RouteMode.RULE) {
                    bypassDomain.addAll(directDNSDomainList.split(","))
                }
            } else {
                for (bypassRule in extraRules.filter { it.isBypassRule() }) {
                    if (bypassRule.domains.isNotEmpty()) {
                        bypassDomain.addAll(bypassRule.domains.listByLineOrComma())
                    }
                }
            }
            val remoteDNSDomainList = DataStore.experimentalFlagsProperties.getProperty("remoteDNSDomainList")
            if (remoteDNSDomainList != null) {
                if (!forTest && DataStore.routeMode == RouteMode.RULE) {
                    proxyDomain.addAll(remoteDNSDomainList.split(","))
                }
            } else {
                for (proxyRule in extraRules.filter { it.isProxyRule() }) {
                    if (proxyRule.domains.isNotEmpty()) {
                        proxyDomain.addAll(proxyRule.domains.listByLineOrComma())
                    }
                }
            }
        }

        remoteDNS.forEach {
            try {
                if (it.lowercase() != "localhost" && it.lowercase() != "fakedns") {
                    if (it.contains("://")) {
                        val url = Libexclavecore.parseURL(it)
                        if (!Libexclavecore.isIP(url.host)) {
                            bypassDomainSkipFakeDns.add("full:${url.host}")
                        }
                    } else if (!Libexclavecore.isIP(it)) {
                        bypassDomainSkipFakeDns.add("full:$it")
                    }
                }
            } catch (_: Exception) {}
        }

        directDNS.forEach {
            try {
                if (it.lowercase() != "localhost" && it.lowercase() != "fakedns") {
                    if (it.contains("://")) {
                        val url = Libexclavecore.parseURL(it)
                        if (!Libexclavecore.isIP(url.host)) {
                            bootstrapDomain.add("full:${url.host}")
                        }
                    } else if (!Libexclavecore.isIP(it)) {
                        bootstrapDomain.add("full:$it")
                    }
                }
            } catch (_: Exception) {}
        }

        var hasDnsTagDirect = false
        if (bypassDomain.isNotEmpty() || bypassDomainSkipFakeDns.isNotEmpty() || bootstrapDomain.isNotEmpty()) {
            dns.servers.addAll(remoteDNS.map {
                DnsObject.StringOrServerObject().apply {
                    valueY = DnsObject.ServerObject().apply {
                        address = it
                        domains = proxyDomain.toList() // v2fly/v2ray-core#1558, v2fly/v2ray-core#1855
                        queryStrategy = remoteDnsQueryStrategy
                        if (DataStore.ednsClientIp.isNotEmpty()) {
                            clientIp = DataStore.ednsClientIp
                        }
                        if (useFakeDns) {
                            fakedns = mutableListOf()
                            if (queryStrategy != "UseIPv6") {
                                fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                    valueY = FakeDnsObject().apply {
                                        ipPool = "${VpnService.FAKEDNS_VLAN4_CLIENT}/${VpnService.FAKEDNS_VLAN4_CLIENT_PREFIX}"
                                        poolSize = VpnService.FAKEDNS_VLAN4_CLIENT_POOL_SIZE
                                    }
                                })
                            }
                            if (queryStrategy != "UseIPv4") {
                                fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                    valueY = FakeDnsObject().apply {
                                        ipPool = "${VpnService.FAKEDNS_VLAN6_CLIENT}/${VpnService.FAKEDNS_VLAN6_CLIENT_PREFIX}"
                                        poolSize = VpnService.FAKEDNS_VLAN6_CLIENT_POOL_SIZE
                                    }
                                })
                            }
                        }
                    }
                }
            })
            if (bootstrapDomain.isNotEmpty()) {
                dns.servers.addAll(bootstrapDNS.map {
                    DnsObject.StringOrServerObject().apply {
                        valueY = DnsObject.ServerObject().apply {
                            address = it
                            domains = bootstrapDomain.toList() // v2fly/v2ray-core#1558, v2fly/v2ray-core#1855
                            queryStrategy = directDnsQueryStrategy
                            if (!it.lowercase().contains("+local://") && it.lowercase() != "localhost") {
                                tag = TAG_DNS_DIRECT
                                hasDnsTagDirect = true
                            }
                            fallbackStrategy = "disabled"
                        }
                    }
                })
            }
            if (bypassDomainSkipFakeDns.isNotEmpty()) {
                dns.servers.addAll(directDNS.map {
                    DnsObject.StringOrServerObject().apply {
                        valueY = DnsObject.ServerObject().apply {
                            address = it
                            // skip fake DNS for server addresses and DNS server addresses
                            domains = bypassDomainSkipFakeDns.toList()
                            queryStrategy = directDnsQueryStrategy
                            if (!it.lowercase().contains("+local://") && it.lowercase() != "localhost") {
                                tag = TAG_DNS_DIRECT
                                hasDnsTagDirect = true
                            }
                            fallbackStrategy = "disabled"
                        }
                    }
                })
            }
            if (bypassDomain.isNotEmpty()) {
                dns.servers.addAll(directDNS.map {
                    DnsObject.StringOrServerObject().apply {
                        valueY = DnsObject.ServerObject().apply {
                            address = it
                            //FIXME: This relies on the behavior of a bug.
                            domains = bypassDomain.toList() // v2fly/v2ray-core#1558, v2fly/v2ray-core#1855
                            queryStrategy = directDnsQueryStrategy
                            if (!it.contains("+local://") && it != "localhost") {
                                tag = TAG_DNS_DIRECT
                                hasDnsTagDirect = true
                            }
                            if (useFakeDns) {
                                fakedns = mutableListOf()
                                if (queryStrategy != "UseIPv6") {
                                    fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                        valueY = FakeDnsObject().apply {
                                            ipPool = "${VpnService.FAKEDNS_VLAN4_CLIENT}/${VpnService.FAKEDNS_VLAN4_CLIENT_PREFIX}"
                                            poolSize = VpnService.FAKEDNS_VLAN4_CLIENT_POOL_SIZE
                                        }
                                    })
                                }
                                if (queryStrategy != "UseIPv4") {
                                    fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                        valueY = FakeDnsObject().apply {
                                            ipPool = "${VpnService.FAKEDNS_VLAN6_CLIENT}/${VpnService.FAKEDNS_VLAN6_CLIENT_PREFIX}"
                                            poolSize = VpnService.FAKEDNS_VLAN6_CLIENT_POOL_SIZE
                                        }
                                    })
                                }
                            }
                            fallbackStrategy = "disabled"
                        }
                    }
                })
            }
        } else {
            dns.servers.addAll(remoteDNS.map {
                DnsObject.StringOrServerObject().apply {
                    valueY = DnsObject.ServerObject().apply {
                        address = it
                        queryStrategy = remoteDnsQueryStrategy
                        if (DataStore.ednsClientIp.isNotEmpty()) {
                            clientIp = DataStore.ednsClientIp
                        }
                        if (useFakeDns) {
                            fakedns = mutableListOf()
                            if (queryStrategy != "UseIPv6") {
                                fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                    valueY = FakeDnsObject().apply {
                                        ipPool = "${VpnService.FAKEDNS_VLAN4_CLIENT}/${VpnService.FAKEDNS_VLAN4_CLIENT_PREFIX}"
                                        poolSize = VpnService.FAKEDNS_VLAN4_CLIENT_POOL_SIZE
                                    }
                                })
                            }
                            if (queryStrategy != "UseIPv4") {
                                fakedns.add(DnsObject.ServerObject.StringOrFakeDnsObject().apply {
                                    valueY = FakeDnsObject().apply {
                                        ipPool = "${VpnService.FAKEDNS_VLAN6_CLIENT}/${VpnService.FAKEDNS_VLAN6_CLIENT_PREFIX}"
                                        poolSize = VpnService.FAKEDNS_VLAN6_CLIENT_POOL_SIZE
                                    }
                                })
                            }
                        }
                    }
                }
            })
        }

        if (routeMode == RouteMode.DIRECT) {
            routing.rules.add(0, RoutingObject.RuleObject().apply {
                type = "field"
                port = "0-65535"
                outboundTag = TAG_BYPASS
            })
        }

        if (hasDnsTagDirect) {
            routing.rules.add(0, RoutingObject.RuleObject().apply {
                type = "field"
                inboundTag = listOf(TAG_DNS_DIRECT)
                outboundTag = TAG_BYPASS
            })
        }

        if (!forTest && trafficSniffing && DataStore.hijackDns) {
            routing.rules.add(0, RoutingObject.RuleObject().apply {
                type = "field"
                protocol = listOf("dns")
                outboundTag = TAG_DNS_OUT
            })
        }

        if (!forTest) {
            routing.rules.add(0, RoutingObject.RuleObject().apply {
                type = "field"
                inboundTag = listOf(TAG_DNS_IN)
                outboundTag = TAG_DNS_OUT
            })
        }

        if (rootBalancer != null) routing.rules.add(rootBalancer)

        if (trafficStatistics) stats = emptyMap()

        @Suppress("UNCHECKED_CAST")
        result = V2rayBuildResult(
            gson.toJson(this),
            indexMap,
            requireWs,
            if (requireWs) browserForwarder.listenPort else 0,
            requireSh,
            if (requireSh) browserDialer.listenPort else 0,
            outboundTags,
            outboundTagsCurrent,
            outboundTagsAll,
            TAG_BYPASS,
            rootObserver?.tag ?: "",
            rootObserver?.settings?.get("subjectSelector") as? Set<String> ?: HashSet(),
            shouldDumpUID,
            alerts,
            DataStore.enableFakeDns,
        )
    }

    return result

}

fun buildCustomConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): V2rayBuildResult {
    val bean = proxy.configBean!!
    val config = parseJson(bean.content, lenient = true).asJsonObject

    // TODO: add fake DNS pool CIDR to TUN route address
    var useFakeDns = false
    runCatching {
        config.getObject("fakedns", ignoreCase = true)?.also {
            useFakeDns = true
        }
    }
    runCatching {
        config.getArray("fakedns", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
            useFakeDns = true
        }
    }
    config.getObject("dns", ignoreCase = true)?.also { dns ->
        runCatching {
            dns.getBoolean("fakedns", ignoreCase = true)?.takeIf { it }?.also {
                useFakeDns = true
            }
        }
        runCatching {
            dns.getStringArray("fakedns", ignoreCase = true)?.also {
                useFakeDns = true
            }
        }
        runCatching {
            dns.getArray("fakedns", ignoreCase = true)?.also {
                useFakeDns = true
            }
        }
        var servers: List<JsonObject>? = null
        try {
            servers = dns.getArray("servers", ignoreCase = true)
        } catch (_: Exception) {}
        servers?.forEach { server ->
            runCatching {
                server.getBoolean("fakedns", ignoreCase = true)?.takeIf { it }?.also {
                    useFakeDns = true
                    return@forEach
                }
            }
            runCatching {
                server.getStringArray("fakedns", ignoreCase = true)?.also {
                    useFakeDns = true
                    return@forEach
                }
            }
            runCatching {
                server.getArray("fakedns", ignoreCase = true)?.also {
                    useFakeDns = true
                    return@forEach
                }
            }
        }
    }

    var isConfigWithSniffing = false
    var shouldDumpUID = false
    config.getObject("routing", ignoreCase = true)?.also { routeObject ->
        gson.fromJson(routeObject.toString(), RoutingObject::class.java)?.also { route ->
            if (route.rules?.any { it.uid?.isNotEmpty() == true } == true) {
                shouldDumpUID = true
            }
            if (route.rules?.any { it.protocol?.isNotEmpty() == true } == true) {
                isConfigWithSniffing = true
            }
        }
    }

    val inbounds = config.getArray("inbounds")
        ?.map { gson.fromJson(it.toString(), InboundObject::class.java) }
        ?.toMutableList() ?: ArrayList()

    if (!forTest && !forExport) {
        inbounds.add(InboundObject().apply {
            tag = "ipc-in"
            protocol = "ipc"
            val path = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock"
            val udsFile = File(path)
            if (udsFile.exists()) udsFile.delete()
            listen = path
            if (DataStore.trafficSniffing || isConfigWithSniffing || useFakeDns) {
                sniffing = InboundObject.SniffingObject().apply {
                    enabled = true
                    val protocols = mutableListOf<String>().apply {
                        if (useFakeDns) add("fakedns")
                        if (DataStore.trafficSniffing) addAll(listOf("http", "tls", "quic"))
                    }
                    if (protocols.isNotEmpty()) {
                        destOverride = protocols
                    }
                    metadataOnly = useFakeDns && !DataStore.trafficSniffing && !isConfigWithSniffing
                    routeOnly = DataStore.trafficSniffing && !DataStore.destinationOverride
                }
            }
            if (shouldDumpUID) dumpUID = true
        })
        inbounds.add(InboundObject().apply {
            tag = TAG_DNS_IN
            val path = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc_dns.sock"
            val udsFile = File(path)
            if (udsFile.exists()) udsFile.delete()
            listen = path
            protocol = "dokodemo-door"
            settings = LazyInboundConfigurationObject(this,
                DokodemoDoorInboundConfigurationObject().apply {
                    address = "/ipc_dns.sock" // placeholder, all queries are handled internally
                    network = "unix"
                }
            )
        })
    }

    val outbounds = config.getArray("outbounds")?.map {
        gson.fromJson(it.toString(), OutboundObject::class.java)
    }?.toMutableList()
    var flushOutbounds = false

    val outboundTags = ArrayList<String>()
    val firstOutbound = outbounds?.get(0)
    if (firstOutbound != null) {
        if (firstOutbound.tag == null) {
            firstOutbound.tag = TAG_AGENT
            outboundTags.add(TAG_AGENT)
            flushOutbounds = true
        } else {
            outboundTags.add(firstOutbound.tag)
        }
    }

    var directTag = ""
    val directOutbounds = outbounds?.filter { it.protocol == "freedom" }
    if (!directOutbounds.isNullOrEmpty()) {
        val directOutbound = if (directOutbounds.size == 1) {
            directOutbounds[0]
        } else {
            val directOutboundsWithTag = directOutbounds.filter { it.tag != null }
            if (directOutboundsWithTag.isNotEmpty()) {
                directOutboundsWithTag[0]
            } else {
                directOutbounds[0]
            }
        }
        if (directOutbound.tag.isNullOrEmpty()) {
            directOutbound.tag = TAG_DIRECT
            flushOutbounds = true
        }
        directTag = directOutbound.tag
    }

    inbounds.forEach { it.init() }
    val inboundArray = JsonArray(inbounds.size)
    for (inbound in inbounds) {
        inboundArray.add(parseJson(gson.toJson(inbound), lenient = true))
    }
    config.add("inbounds", inboundArray)
    if (flushOutbounds) {
        outbounds!!.forEach { it.init() }
        val outboundArray = JsonArray(outbounds.size)
        for (outbound in outbounds) {
            outboundArray.add(parseJson(gson.toJson(outbound), lenient = true))
        }
    }

    return V2rayBuildResult(
        config = GsonBuilder().setPrettyPrinting().create().toJson(config),
        index = emptyList(),
        requireWs = false,
        wsPort = 0,
        requireSh = false,
        shPort = 0,
        outboundTags = outboundTags,
        outboundTagsCurrent = outboundTags,
        outboundTagsAll =  emptyMap(),
        bypassTag =  directTag,
        observerTag = "",
        observatoryTags = emptySet(),
        dumpUID =  shouldDumpUID,
        alerts =  emptyList(),
        useFakeDNS = useFakeDns,
    )
}
