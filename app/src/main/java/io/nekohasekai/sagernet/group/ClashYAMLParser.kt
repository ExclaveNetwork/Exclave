/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import com.github.shadowsocks.plugin.PluginOptions
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.supportedShadowsocksMethod
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRMethod
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRObfs
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRProtocol
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.tuic5.supportedTuic5CongestionControl
import io.nekohasekai.sagernet.fmt.tuic5.supportedTuic5RelayMode
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.supportedVmessMethod
import io.nekohasekai.sagernet.fmt.v2ray.supportedXhttpMode
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import kotlin.io.encoding.Base64
import libsagernetcore.Libsagernetcore

fun parseClashProxies(proxies: List<Map<String, Any?>>): List<AbstractBean> {
    val beans = mutableListOf<AbstractBean>()
    proxies.forEach {
        beans.addAll(parseClashProxy(it))
    }
    return beans
}

@Suppress("UNCHECKED_CAST")
fun parseClashProxy(proxy: Map<String, Any?>): List<AbstractBean> {
    when (proxy["type"]) {
        "socks5" -> {
            return listOf(SOCKSBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                username = proxy.getString("username")
                password = proxy.getString("password")
                if (proxy.getBoolean("tls") == true) {
                    security = "tls"
                    if (proxy.getBoolean("skip-cert-verify") == true) {
                        allowInsecure = true
                    }
                    proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                        pinnedPeerCertificateSha256 = it
                        allowInsecure = true
                    }
                    val cert = proxy.getString("certificate")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                    }
                    val key = proxy.getString("private-key")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                    }
                    if (cert != null && key != null) {
                        mtlsCertificate = cert
                        mtlsCertificatePrivateKey = key
                    }
                }
                name = proxy.getString("name")
            })
        }
        "http" -> {
            return listOf(HttpBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                username = proxy.getString("username")
                password = proxy.getString("password")
                if (proxy.getBoolean("tls") == true) {
                    security = "tls"
                    // alpn = "http/1.1" // mihomo does not support HTTP/2 CONNECT
                    sni = proxy.getString("sni")
                    if (proxy.getBoolean("skip-cert-verify") == true) {
                        allowInsecure = true
                    }
                    proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                        pinnedPeerCertificateSha256 = it
                        allowInsecure = true
                    }
                    val cert = proxy.getString("certificate")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                    }
                    val key = proxy.getString("private-key")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                    }
                    if (cert != null && key != null) {
                        mtlsCertificate = cert
                        mtlsCertificatePrivateKey = key
                    }
                }
                name = proxy.getString("name")
            })
        }
        "ss" -> {
            var pluginStr = ""
            if (proxy.contains("plugin")) {
                val opts = proxy.getObject("plugin-opts")
                val pluginOpts = PluginOptions()
                when (proxy.getString("plugin")) {
                    "obfs" -> {
                        pluginOpts.id = "obfs-local"
                        when (opts?.getString("mode")) {
                            null, "http" -> pluginOpts["obfs"] = "http"
                            "tls" -> pluginOpts["obfs"] = "tls"
                            else -> return listOf()
                        }
                        when (val host = opts?.getString("host")) {
                            // https://github.com/MetaCubeX/mihomo/blob/903371719021d74cbec9cd24efb66e386c8b7595/adapter/outbound/shadowsocks.go#L307
                            null -> pluginOpts["obfs-host"] = "bing.com"
                            else -> pluginOpts["obfs-host"] = host
                        }
                    }
                    "v2ray-plugin" -> {
                        pluginOpts.id = "v2ray-plugin"
                        when (opts?.getString("mode")) {
                            null, "websocket" -> {}
                            else -> return listOf()
                        }
                        if (opts?.getBoolean("tls") == true) {
                            pluginOpts["tls"] = null
                        }
                        when (val host = opts?.getString("host")) {
                            // https://github.com/MetaCubeX/mihomo/blob/903371719021d74cbec9cd24efb66e386c8b7595/adapter/outbound/shadowsocks.go#L318
                            null -> pluginOpts["host"] = "bing.com"
                            else -> pluginOpts["host"] = host
                        }
                        opts?.getString("path")?.let {
                            pluginOpts["path"] = it
                        }
                        if (opts?.getBoolean("mux") == true) {
                            pluginOpts["mux"] = "1"
                        }
                        if (opts?.getBoolean("v2ray-http-upgrade") == true) {
                            return listOf()
                        }
                    }
                    "", null -> {}
                    else -> return listOf()
                }
                pluginStr = pluginOpts.toString(false)
            }
            return listOf(ShadowsocksBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                password = proxy.getString("password")
                method = when (val cipher = proxy.getString("cipher")?.lowercase()) {
                    "dummy" -> "none"
                    "aead_aes_128_gcm" -> "aes-128-gcm"
                    "aead_aes_192_gcm" -> "aes-192-gcm"
                    "aead_aes_256_gcm" -> "aes-256-gcm"
                    "aead_chacha20_poly1305" -> "chacha20-ietf-poly1305"
                    "aead_xchacha20_poly1305" -> "xchacha20-ietf-poly1305"
                    in supportedShadowsocksMethod -> cipher
                    else -> return listOf()
                }
                plugin = pluginStr
                name = proxy.getString("name")
            })
        }
        "vmess", "vless", "trojan" -> {
            val bean = when (proxy["type"] as String) {
                "vmess" -> VMessBean()
                "vless" -> VLESSBean()
                "trojan" -> TrojanBean()
                else -> error("impossible")
            }.apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                name = proxy.getString("name")
            }

            if (bean is TrojanBean) {
                when (val network = proxy.getString("network")) {
                    "ws", "grpc" -> bean.type = network
                    else -> bean.type = "tcp"
                }
            } else {
                when (val network = proxy.getString("network")) {
                    "h2" -> bean.type = "http"
                    "http" -> {
                        bean.type = "tcp"
                        bean.headerType = "http"
                    }
                    "ws", "grpc" -> bean.type = network
                    "xhttp" -> if (bean is VLESSBean) bean.type = "splithttp"
                    else -> bean.type = "tcp"
                }
            }

            if (bean is TrojanBean) {
                bean.security = "tls"
                bean.sni = proxy.getString("sni")
                bean.password = proxy.getString("password")
            } else {
                bean.security = if (proxy.getBoolean("tls") == true) "tls" else "none"
                if (bean.security == "tls") {
                    bean.sni = proxy.getString("servername")
                }
                proxy.getString("uuid")?.also {
                    bean.uuid = uuidOrGenerate(it)
                }
            }
            if (bean.security == "tls") {
                bean.alpn = proxy.getStringArray("alpn")?.joinToString("\n")
                bean.allowInsecure = proxy.getBoolean("skip-cert-verify") == true
                proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                    bean.pinnedPeerCertificateSha256 = it
                    bean.allowInsecure = true
                }
                val cert = proxy.getString("certificate")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                }
                val key = proxy.getString("private-key")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                }
                if (cert != null && key != null) {
                    bean.mtlsCertificate = cert
                    bean.mtlsCertificatePrivateKey = key
                }
                /*proxy.getObject("ech-opts")?.also {
                    bean.echEnabled = it.getBoolean("enable")
                    bean.echConfig = it.getString("config")
                }*/
            }

            if (bean is VMessBean) {
                bean.alterId = proxy.getInt("alterId") ?: return listOf()
                bean.encryption = when (val cipher = proxy.getString("cipher")) {
                    in supportedVmessMethod -> cipher
                    else -> return listOf()
                }
                bean.experimentalAuthenticatedLength = proxy.getBoolean("authenticated-length") == true
                var isPacket = false
                var isXUDP = false
                if (proxy.getBoolean("packet-addr") == true) {
                    isPacket = true
                    isXUDP = false
                }
                if (proxy.getBoolean("xudp") == true) {
                    isXUDP = true
                    isPacket = false
                }
                when (proxy.getString("packet-encoding")) {
                    "packetaddr", "packet" -> {
                        isPacket = true
                        isXUDP = false
                    }
                    "xudp" -> {
                        isXUDP = true
                        isPacket = false
                    }
                }
                bean.packetEncoding = when {
                    isPacket -> "packet"
                    isXUDP -> "xudp"
                    else -> "none"
                }
            }

            if (bean is VLESSBean) {
                var isPacket = false
                var isXUDP = true
                if (proxy.getBoolean("packet-addr") == true) {
                    isPacket = true
                    isXUDP = false
                }
                if (proxy.getBoolean("xudp") == true) {
                    isXUDP = true
                    isPacket = false
                }
                when (proxy.getString("packet-encoding")) {
                    "packetaddr", "packet" -> {
                        isPacket = true
                        isXUDP = false
                    }
                    "xudp" -> {
                        isXUDP = true
                        isPacket = false
                    }
                }
                bean.packetEncoding = when {
                    isPacket -> "packet"
                    @Suppress("KotlinConstantConditions")
                    isXUDP -> "xudp"
                    else -> "xudp"
                }
                (proxy.getString("flow"))?.takeIf { it.isNotEmpty() }?.also {
                    if (it.startsWith("xtls-rprx-vision")) {
                        bean.flow = "xtls-rprx-vision-udp443"
                        bean.packetEncoding = "xudp"
                    } else return listOf()
                }
                when (val encryption = proxy.getString("encryption")) {
                    "", "none", null -> bean.encryption = "none"
                    else -> {
                        val parts = encryption.split(".")
                        if (parts.size < 4 || parts[0] != "mlkem768x25519plus"
                            || !(parts[1] == "native" || parts[1] == "xorpub" || parts[1] == "random")
                            || !(parts[2] == "1rtt" || parts[2] == "0rtt")) {
                            error("unsupported vless encryption")
                        }
                        bean.encryption = encryption
                    }
                }
            }

            proxy.getObject("reality-opts")?.also {
                bean.security = "reality"
                bean.realityPublicKey = it.getString("public-key")
                bean.realityShortId = it.getString("short-id")
            }

            if (bean.type == "tcp" && bean.headerType != null && bean.headerType == "http") {
                proxy.getObject("http-opts")?.also {
                    bean.path = it.getStringArray("path")?.joinToString("\n")
                    val headers = it.getObject("headers") as? Map<String, List<String>>
                    bean.host = headers?.getStringArray("host")?.joinToString("\n")
                }
            }
            if (bean.type == "ws") {
                if (bean is TrojanBean && (bean.security == "tls" || bean.security == "reality") && !bean.sni.isNullOrEmpty()) {
                    bean.host = bean.sni
                }
                proxy.getObject("ws-opts")?.also { wsOpts ->
                    bean.path = wsOpts.getString("path")
                    bean.maxEarlyData = wsOpts.getInt("max-early-data")
                    bean.earlyDataHeaderName = wsOpts.getString("early-data-header-name")
                    bean.path = wsOpts.getString("path")
                    if (!bean.path.isNullOrEmpty()) {
                        try {
                            val u = Libsagernetcore.parseURL(bean.path)
                            u.queryParameter("ed")?.also { ed ->
                                u.deleteQueryParameter("ed")
                                bean.path = u.string
                                (ed.toIntOrNull())?.also {
                                    bean.maxEarlyData = it
                                }
                                bean.earlyDataHeaderName = "Sec-WebSocket-Protocol"
                            }
                        } catch (_: Exception) {}
                    }
                    if (wsOpts.getBoolean("v2ray-http-upgrade") == true) {
                        bean.type = "httpupgrade"
                        bean.maxEarlyData = null
                        bean.earlyDataHeaderName = null
                    }
                    val headers = wsOpts.getObject("headers") as? Map<String, String>
                    headers?.getString("host")?.also {
                        bean.host = it
                        if (bean !is TrojanBean && (bean.security == "tls" || bean.security == "reality") && bean.sni.isNullOrEmpty()) {
                            bean.sni = it
                        }
                    }
                }
            }
            if (bean.type == "http") {
                proxy.getObject("h2-opts")?.also {
                    bean.path = it.getString("path")
                    bean.host = it.getStringArray("host")?.joinToString("\n")
                }
            }
            if (bean.type == "grpc") {
                proxy.getObject("grpc-opts")?.also {
                    bean.grpcServiceName = it.getString("grpc-service-name")
                }
            }
            if (bean.type == "splithttp") {
                proxy.getObject("xhttp-opts")?.also { opts ->
                    bean.path = opts.getString("path")
                    bean.host = opts.getString("host")
                    opts.getString("mode")?.also {
                        bean.splithttpMode = when (it) {
                            in supportedXhttpMode -> it
                            "" -> "auto"
                            else -> return listOf()
                        }
                    }
                    JsonObject().apply {
                        opts.getBoolean("no-grpc-header")?.also {
                            addProperty("noGRPCHeader", it)
                        }
                        opts.getString("x-padding-bytes")?.also {
                            addProperty("xPaddingBytes", it)
                        }
                        opts.getString("sc-max-each-post-bytes")?.also {
                            addProperty("scMaxEachPostBytes", it)
                        }
                        opts.getString("sc-min-posts-interval-ms")?.also {
                            addProperty("scMinPostsIntervalMs", it)
                        }
                        opts.getObject("reuse-settings")?.also { xmux ->
                            JsonObject().apply {
                                xmux.getString("max-connections")?.also {
                                    addProperty("maxConnections", it)
                                }
                                xmux.getString("max-concurrency")?.also {
                                    addProperty("maxConcurrency", it)
                                }
                                xmux.getString("c-max-reuse-times")?.also {
                                    addProperty("cMaxReuseTimes", it)
                                }
                                xmux.getString("h-max-request-times")?.also {
                                    addProperty("hMaxRequestTimes", it)
                                }
                                xmux.getString("h-max-reusable-secs")?.also {
                                    addProperty("hMaxReusableSecs", it)
                                }
                            }.takeIf { !it.isEmpty }?.also {
                                add("xmux", it)
                            }
                        }
                        opts.getString("x-padding-bytes")?.also {
                            addProperty("xPaddingBytes", it)
                        }
                        opts.getBoolean("x-padding-obfs-mode")?.also {
                            addProperty("xPaddingObfsMode", it)
                        }
                        opts.getString("x-padding-key")?.also {
                            addProperty("xPaddingKey", it)
                        }
                        opts.getString("x-padding-header")?.also {
                            addProperty("xPaddingHeader", it)
                        }
                        opts.getString("x-padding-placement")?.also {
                            addProperty("xPaddingPlacement", it)
                        }
                        opts.getString("x-padding-method")?.also {
                            addProperty("xPaddingMethod", it)
                        }
                        opts.getString("uplink-http-method")?.also {
                            addProperty("uplinkHTTPMethod", it)
                        }
                        opts.getString("session-placement")?.also {
                            addProperty("sessionPlacement", it)
                        }
                        opts.getString("session-key")?.also {
                            addProperty("sessionKey", it)
                        }
                        opts.getString("seq-placement")?.also {
                            addProperty("seqPlacement", it)
                        }
                        opts.getString("seq-key")?.also {
                            addProperty("seqKey", it)
                        }
                        opts.getString("uplink-data-placement")?.also {
                            addProperty("uplinkDataPlacement", it)
                        }
                        opts.getString("uplink-data-key")?.also {
                            addProperty("uplinkDataKey", it)
                        }
                        opts.getString("uplink-chunk-size")?.also {
                            addProperty("uplinkChunkSize", it)
                        }
                    }.takeIf { !it.isEmpty }?.also {
                        bean.splithttpExtra = GsonBuilder().setPrettyPrinting().create().toJson(it)
                    }
                }
            }

            if (bean is TrojanBean) {
                proxy.getObject("ss-opts")?.also {
                    if (it.getBoolean("enabled") == true) {
                        return listOf()
                    }
                }
            }
            return listOf(bean)
        }
        "ssr" -> {
            return listOf(ShadowsocksRBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                method = when (val cipher = proxy.getString("cipher")?.lowercase()) {
                    "dummy" -> "none"
                    in supportedShadowsocksRMethod -> cipher
                    else -> return listOf()
                }
                password = proxy.getString("password")
                obfs = when (val it = proxy.getString("obfs")) {
                    "tls1.2_ticket_fastauth" -> "tls1.2_ticket_auth"
                    in supportedShadowsocksRObfs -> it
                    else -> return listOf()
                }
                obfsParam = proxy.getString("obfs-param")
                protocol = when (val it = proxy.getString("protocol")) {
                    in supportedShadowsocksRProtocol -> it
                    else -> return listOf()
                }
                protocolParam = proxy.getString("protocol-param")
                name = proxy.getString("name")
            })
        }
        "ssh" -> {
            return listOf(SSHBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                username = proxy.getString("username")
                proxy.getString("password")?.also {
                    password = it
                    authType = SSHBean.AUTH_TYPE_PASSWORD
                }
                proxy.getString("private-key")?.takeIf { it.contains("PRIVATE KEY") }?.also {
                    privateKey = it
                    privateKeyPassphrase = proxy.getString("private-key-passphrase")
                    authType = SSHBean.AUTH_TYPE_PUBLIC_KEY
                }
                publicKey = proxy.getStringArray("host-key")?.joinToString("\n")
                name = proxy.getString("name")
            })
        }
        "hysteria2" -> {
            return listOf(Hysteria2Bean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                val port = proxy.getInt("port")?.takeIf { it > 0 }
                val ports = proxy.getString("ports")?.toIntRanges()
                if (port == null && ports == null) return listOf()
                serverPorts = ports?.joinToString(",") {
                    if (it.third) it.first.toString() else "${it.first}-${it.second}"
                } ?: port.toString()
                auth = proxy.getString("password")
                sni = proxy.getString("sni")
                allowInsecure = proxy.getBoolean("skip-cert-verify") == true
                proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                    pinnedPeerCertificateSha256 = it
                    allowInsecure = true
                }
                // https://github.com/MetaCubeX/mihomo/commit/6786705212f67eebe25151778b86ab4d2793c7d9
                if (!proxy.contains("ca")) {
                    certificates = proxy.getString("ca-str")?.lines()?.joinToString("\n")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                    }
                }
                val cert = proxy.getString("certificate")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                }
                val key = proxy.getString("private-key")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                }
                if (cert != null && key != null) {
                    mtlsCertificate = cert
                    mtlsCertificatePrivateKey = key
                }
                /*proxy.getObject("ech-opts")?.also {
                    echEnabled = it.getBoolean("enable")
                    echConfig = it.getString("config")
                }*/
                (proxy.getString("obfs"))?.also {
                    when (it) {
                        "" -> {}
                        "salamander" -> {
                            obfs = proxy.getString("obfs-password")
                        }
                        else -> return listOf()
                    }
                }
                if (ports != null) {
                    // Ref: https://github.com/MetaCubeX/mihomo/commit/80072ebb6f9933e6a87ae94e3f6917a6c82c665e
                    val hopIntervalInt = proxy.getInt("hop-interval")?.takeIf { it > 0 }
                    val hopIntervalString = proxy.getString("hop-interval")?.toIntRange()
                    if (hopIntervalString != null) {
                        if (hopIntervalString.first == hopIntervalString.second) {
                            hopInterval = hopIntervalString.first.toLong()
                        } else {
                            // Ref: https://github.com/MetaCubeX/mihomo/blob/80072ebb6f9933e6a87ae94e3f6917a6c82c665e/adapter/outbound/hysteria2.go#L215-L223
                            var start = hopIntervalString.first
                            var end = hopIntervalString.second
                            if (start == 0) {
                                start = 30
                            } else if (start < 5) {
                                start = 5
                            }
                            if (end < start) {
                                end = start
                            }
                            hopIntervalMin = start.toLong()
                            hopIntervalMax = end.toLong()
                        }
                    } else if (hopIntervalInt != null) {
                        hopInterval = hopIntervalInt.toLong()
                    }
                }
                name = proxy.getString("name")
            })
        }
        "tuic" -> {
            if (proxy.getString("token") != null) {
                // v4, unsupported
                return listOf()
            } else {
                return listOf(Tuic5Bean().apply {
                    serverAddress = proxy.getString("ip") ?: proxy.getString("server") ?: return listOf()
                    serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                    uuid = proxy.getString("uuid")
                    password = proxy.getString("password")
                    udpRelayMode = when (val mode = proxy.getString("udp-relay-mode")) {
                        in supportedTuic5RelayMode -> mode
                        else -> "native"
                    }
                    congestionControl = when (val controller = proxy.getString("congestion-controller")) {
                        in supportedTuic5CongestionControl -> controller
                        else -> "cubic"
                    }
                    disableSNI = proxy.getBoolean("disable-sni") == true
                    zeroRTTHandshake = proxy.getBoolean("reduce-rtt") == true
                    allowInsecure = proxy.getBoolean("skip-cert-verify") == true
                    sni = proxy.getString("sni")
                        ?: (if (proxy.getString("ip") != null) proxy.getString("server") else null)
                    // https://github.com/MetaCubeX/mihomo/blob/d5243adf8911563677d3bd190b82623c93e554b7/adapter/outbound/tuic.go#L174-L178
                    alpn = if (!proxy.contains("alpn")) "h3" else proxy.getStringArray("alpn")?.joinToString("\n")
                    proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                        pinnedPeerCertificateSha256 = it
                        allowInsecure = true
                    }
                    // https://github.com/MetaCubeX/mihomo/commit/6786705212f67eebe25151778b86ab4d2793c7d9
                    if (!proxy.contains("ca")) {
                        certificates = proxy.getString("ca-str")?.lines()?.joinToString("\n")?.takeIf {
                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                        }
                    }
                    val cert = proxy.getString("certificate")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                    }
                    val key = proxy.getString("private-key")?.takeIf {
                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                    }
                    if (cert != null && key != null) {
                        mtlsCertificate = cert
                        mtlsCertificatePrivateKey = key
                    }
                    /*proxy.getObject("ech-opts")?.also {
                        echEnabled = it.getBoolean("enable")
                        echConfig = it.getString("config")
                    }*/
                    name = proxy.getString("name")
                })
            }
        }
        "mieru" -> {
            return listOf(MieruBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")
                portRange = proxy.getStringArray("port-range")?.joinToString("\n")
                if (serverPort == null && portRange == null) {
                    return listOf()
                }
                username = proxy.getString("username")
                password = proxy.getString("password")
                protocol = MieruBean.PROTOCOL_TCP
                proxy.getString("transport")?.also {
                    protocol = when (it) {
                        "TCP", "" -> MieruBean.PROTOCOL_TCP
                        "UDP" -> MieruBean.PROTOCOL_UDP
                        else -> return listOf()
                    }
                }
                proxy.getString("multiplexing")?.also {
                    multiplexingLevel = when (it) {
                        "MULTIPLEXING_OFF" -> MieruBean.MULTIPLEXING_OFF
                        "MULTIPLEXING_LOW" -> MieruBean.MULTIPLEXING_LOW
                        "MULTIPLEXING_MIDDLE" -> MieruBean.MULTIPLEXING_MIDDLE
                        "MULTIPLEXING_HIGH" -> MieruBean.MULTIPLEXING_HIGH
                        else -> MieruBean.MULTIPLEXING_DEFAULT
                    }
                }
                proxy.getString("handshake-mode")?.also {
                    handshakeMode = when (it) {
                        "HANDSHAKE_STANDARD" -> MieruBean.HANDSHAKE_STANDARD
                        "HANDSHAKE_NO_WAIT" -> MieruBean.HANDSHAKE_NO_WAIT
                        else -> MieruBean.HANDSHAKE_DEFAULT
                    }
                }
                /*proxy.getString("traffic-pattern")?.also {
                    // How to validate its validity?
                    trafficPattern = it
                }*/
                name = proxy.getString("name")
            })
        }
        "anytls" -> {
            return listOf(AnyTLSBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                password = proxy.getString("password")
                security = "tls"
                sni = proxy.getString("sni")
                alpn = proxy.getStringArray("alpn")?.joinToString("\n")
                allowInsecure = proxy.getBoolean("skip-cert-verify") == true
                proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                    pinnedPeerCertificateSha256 = it
                    allowInsecure = true
                }
                val cert = proxy.getString("certificate")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                }
                val key = proxy.getString("private-key")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                }
                if (cert != null && key != null) {
                    mtlsCertificate = cert
                    mtlsCertificatePrivateKey = key
                }
                /*proxy.getObject("ech-opts")?.also {
                    echEnabled = it.getBoolean("enable")
                    echConfig = it.getString("config")
                }*/
                name = proxy.getString("name")
            })
        }
        "trusttunnel" -> {
            return listOf(TrustTunnelBean().apply {
                serverAddress = proxy.getString("server") ?: return listOf()
                serverPort = proxy.getInt("port")?.takeIf { it > 0 } ?: return listOf()
                username = proxy.getString("username")
                password = proxy.getString("password")
                sni = proxy.getString("sni")
                allowInsecure = proxy.getBoolean("skip-cert-verify") == true
                protocol = if (proxy.getBoolean("quic") == true) "quic" else "https"
                proxy.getString("fingerprint")?.replace(":", "")?.trim()?.also {
                    pinnedPeerCertificateSha256 = it
                    allowInsecure = true
                }
                val cert = proxy.getString("certificate")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                }
                val key = proxy.getString("private-key")?.takeIf {
                    it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                }
                if (cert != null && key != null) {
                    mtlsCertificate = cert
                    mtlsCertificatePrivateKey = key
                }
                /*proxy.getObject("ech-opts")?.also {
                    echEnabled = it.getBoolean("enable")
                    echConfig = it.getString("config")
                }*/
                name = proxy.getString("name")
            })
        }
        "wireguard" -> {
            proxy.getObject("amnezia-wg-option")?.also {
                // unsupported
                return listOf()
            }
            val beanList = mutableListOf<WireGuardBean>()
            val bean = WireGuardBean().apply {
                serverAddress = proxy.getString("server")
                serverPort = proxy.getInt("port")
                privateKey = proxy.getString("private-key")
                peerPublicKey = proxy.getString("public-key")
                peerPreSharedKey = proxy.getString("pre-shared-key")
                    ?: proxy.getString("preshared-key") // "preshared-key" from Clash Premium
                mtu = (proxy.getInt("mtu"))?.takeIf { it > 0 } ?: 1408
                localAddress = listOfNotNull(proxy.getString("ip"), proxy.getString("ipv6")).joinToString("\n")
                keepaliveInterval = proxy.getInt("persistent-keepalive")
                name = proxy.getString("name")
                proxy.getIntArray("reserved")?.also {
                    if (it.size == 3) {
                        reserved = listOf(
                            it[0].toString(),
                            it[1].toString(),
                            it[2].toString()
                        ).joinToString(",")
                    }
                } ?: proxy.getString("reserved")?.also {
                    try {
                        val arr = Base64.decode(it)
                        if (arr.size == 3) {
                            reserved = listOf(
                                arr[0].toUByte().toInt().toString(),
                                arr[1].toUByte().toInt().toString(),
                                arr[2].toUByte().toInt().toString()
                            ).joinToString(",")
                        }
                    } catch (_: Exception) {}

                }
            }
            if (proxy.contains("server") && proxy.contains("port")) {
                beanList.add(bean)
            }
            proxy.getArray("peers")?.forEach { peer ->
                if (peer.contains("server") && peer.contains("port")) {
                    beanList.add(bean.applyDefaultValues().clone().apply {
                        serverAddress = peer.getString("server")
                        serverPort = peer.getInt("port")
                        peerPublicKey = peer.getString("public-key")
                        peerPreSharedKey = peer.getString("pre-shared-key")
                        peer.getIntArray("reserved")?.also {
                            if (it.size == 3) {
                                reserved = listOf(
                                    it[0].toString(),
                                    it[1].toString(),
                                    it[2].toString()
                                ).joinToString(",")
                            }
                        } ?: peer.getString("reserved")?.also {
                            try {
                                val arr = Base64.decode(it)
                                if (arr.size == 3) {
                                    reserved = listOf(
                                        arr[0].toUByte().toInt().toString(),
                                        arr[1].toUByte().toInt().toString(),
                                        arr[2].toUByte().toInt().toString()
                                    ).joinToString(",")
                                }
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
            return beanList
        }
        else -> return listOf()
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getObject(key: String): Map<String, Any?>? {
    this[key]?.also {
        return it as? Map<String, Any?>
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return it.value as? Map<String, Any?>
        }
    }
    return null
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getArray(key: String): List<Map<String, Any?>>? {
    (this[key] as? List<Map<String, Any?>>)?.also {
        return it
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return it.value as? List<Map<String, Any?>>
        }
    }
    return null
}

private fun Map<String, Any?>.getString(key: String): String? {
    if (this.contains(key)) {
        return when (val value = this[key]) {
            is String -> value
            is Int -> value.toString()
            // is Float -> value.toString()
            else -> null
        }
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return when (val value = it.value) {
                is String -> value
                is Int -> value.toString()
                // is Float -> value.toString()
                else -> null
            }
        }
    }
    return null
}

private fun Map<String, Any?>.getInt(key: String): Int? {
    if (this.contains(key)) {
        return when (val value = this[key]) {
            is Int -> value
            is String -> value.convertClashStringToInt()
            is Float -> value.toInt()
            else -> null
        }
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return when (val value = it.value) {
                is Int -> value
                is String -> value.convertClashStringToInt()
                is Float -> value.toInt()
                else -> null
            }
        }
    }
    return null
}

private fun Map<String, Any?>.getBoolean(key: String): Boolean? {
    if (this.contains(key)) {
        return when (val value = this[key]) {
            is Boolean -> value
            is Int -> value != 0
            else -> null
        }
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return when (val value = it.value) {
                is Boolean -> value
                is Int -> value != 0
                else -> null
            }
        }
    }
    return null
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getStringArray(key: String): List<String>? {
    (this[key] as? List<String>)?.also {
        return it
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return it.value as? List<String>
        }
    }
    return null
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getIntArray(key: String): List<Int>? {
    (this[key] as? List<Int>)?.also {
        return it
    }
    for (it in this) {
        if (it.key.equals(key, ignoreCase = true)) {
            return it.value as? List<Int>
        }
    }
    return null
}

private fun String.convertClashStringToInt(): Int? {
    if (this.contains(":")) return null
    if (!this.startsWith("_") && this.replace("_", "").toFloatOrNull() != null) {
        return this.replace("_", "").toFloat().toInt()
    }
    val newStr = this.lowercase().removePrefix("+")
    if (newStr.contains("+")) return null
    if (newStr.startsWith("0x")) {
        return newStr.removePrefix("0x").replace("_", "").toIntOrNull(16)
    }
    if (newStr.startsWith("0b")) {
        return newStr.removePrefix("0b").replace("_", "").toIntOrNull(2)
    }
    if (newStr.startsWith("0o")) {
        return newStr.removePrefix("0o").replace("_", "").toIntOrNull(8)
    }
    if (newStr.startsWith("0")) {
        return newStr.removePrefix("0").replace("_", "").toIntOrNull(8)
    }
    if (newStr.startsWith("_")) {
        return null
    }
    return newStr.replace("_", "").toIntOrNull()
}

// Ref: https://github.com/MetaCubeX/mihomo/blob/80072ebb6f9933e6a87ae94e3f6917a6c82c665e/common/utils/ranges.go#L16-L48
private fun String.toIntRanges(): List<Triple<Int, Int, Boolean>> {
    // example: 200 or 200/302 or 200-400 or 200/204/401-429/501-503
    var expected = this.trim()
    if (expected.isEmpty() || expected == "*") {
        return listOf()
    }
    // support: 200,302 or 200,204,401-429,501-503
    expected = expected.replace(",", "/")
    val list = expected.split("/")
    val ranges = mutableListOf<Triple<Int, Int, Boolean>>()
    for (s in list) {
        if (s.isEmpty()) {
            continue
        }
        val r = s.toIntRange() ?: return listOf()
        ranges.add(r)
    }
    return ranges
}

// Ref: https://github.com/MetaCubeX/mihomo/blob/80072ebb6f9933e6a87ae94e3f6917a6c82c665e/common/utils/range.go#L65-L87
// Triple<Int, Int, Boolean>: start, end, isSinglePort
private fun String.toIntRange(): Triple<Int, Int, Boolean>? {
    val s = this.trim()
    if (s.isEmpty()) {
        return Triple(0, 0, true)
    }
    val status = s.split("-")
    val start = status[0].trim { it == '[' || it == ' ' || it == ']' }.convertClashStringToInt()
    if (start == null) {
        return null
    }
    when (status.size) {
        1 -> { // Single port
            return Triple(start, start, true)
        }
        2 -> { // Port range
            val end = status[1].trim { it == '[' || it == ' ' || it == ']' }.convertClashStringToInt()
            if (end == null) {
                return null
            }
            return Triple(start, end, false)
        }
        else -> {
            return null
        }
    }
}
