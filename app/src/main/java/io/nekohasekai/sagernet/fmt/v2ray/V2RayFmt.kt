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

package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import libexclavecore.Libexclavecore
import java.util.Base64

val supportedVmessMethod = arrayOf(
    "auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"
)

val supportedVlessFlow = arrayOf(
    "xtls-rprx-vision", "xtls-rprx-vision-udp443"
)

val legacyVlessFlow = arrayOf(
    "xtls-rprx-origin", "xtls-rprx-origin-udp443",
    "xtls-rprx-direct", "xtls-rprx-direct-udp443",
    "xtls-rprx-splice", "xtls-rprx-splice-udp443"
)

val supportedQuicSecurity = arrayOf(
    "none", "aes-128-gcm", "chacha20-poly1305"
)

val supportedKcpQuicHeaderType = arrayOf(
    "none", "srtp", "utp", "wechat-video", "dtls", "wireguard"
)

val supportedXhttpMode = arrayOf(
    "auto", "packet-up", "stream-up", "stream-one"
)

val nonRawTransportName = arrayOf(
    "kcp", "mkcp", "ws", "websocket", "h2", "http", "quic",
    "grpc", "gun", "meek", "httpupgrade", "splithttp", "xhttp",
    "hysteria2", "hy2", "mekya"
)

fun parseV2Ray(link: String): StandardV2RayBean {
    // https://github.com/XTLS/Xray-core/issues/91
    // https://github.com/XTLS/Xray-core/discussions/716
    val url = Libexclavecore.parseURL(link)
    val bean = when (url.scheme) {
        "vmess" -> VMessBean()
        "vless" -> VLESSBean()
        "trojan" -> TrojanBean()
        else -> error("impossible")
    }

    if (url.scheme == "vmess" && url.port == 0 && url.username.isEmpty() && url.password.isEmpty()) {
        val decoded = link.substring("vmess://".length).substringBefore("#").decodeBase64()
        try {
            return parseV2RayN(parseJson(decoded).asJsonObject)
        } catch (_: Exception) {}

        if (decoded.filterNot { it.isWhitespace() }.contains("=vmess,")) {
            // vmess://{BASE64_ENCODED}
            // name = vmess, example.com, 8388, aes-128-gcm, 00000000-0000-0000-0000-000000000000, param=value
            // quan?
            error("known unsupported format")
        }
        if (decoded.contains("@")) {
            // vmess://{BASE64_ENCODED}?param=value&remarks=name
            // aes-128-gcm:00000000-0000-0000-0000-000000000000@example.com:8388
            // rocket?
            error("known unsupported format")
        }
        error("unknown format")
    }

    if (url.scheme == "vmess" && url.password.isNotEmpty()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26
        error("known unsupported format")
    }

    bean.serverAddress = url.host.ifEmpty { error("empty host") }
    bean.serverPort = url.port
    bean.name = url.fragment

    if (bean is TrojanBean) {
        // https://github.com/trojan-gfw/igniter/issues/318
        when {
            url.username.isEmpty() && url.password.isEmpty() -> {
                if (link.substring("trojan://".length).substringBefore("@") == ":") {
                    bean.password = ":"
                }
            }
            url.username.isNotEmpty() && url.password.isEmpty() -> {
                bean.password = if (link.substring("trojan://".length).substringBefore("@").endsWith(":")) {
                    url.username + ":"
                } else {
                    url.username
                }
            }
            url.username.isEmpty() && url.password.isNotEmpty() -> {
                bean.password = ":" + url.password
            }
            url.username.isNotEmpty() && url.password.isNotEmpty() -> {
                bean.password = url.username + ":" + url.password
            }
        }
    } else {
        bean.uuid = uuidOrGenerate(url.username)
    }

    if (bean is VMessBean) {
        url.queryParameter("encryption")?.let {
            if (it !in supportedVmessMethod) error("unsupported vmess encryption")
            bean.encryption = it
        }
    }
    if (bean is VLESSBean) {
        when (val encryption = url.queryParameter("encryption")) {
            "none", null -> bean.encryption = "none"
            "" -> error("unsupported vless encryption")
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

    when (val security = url.queryParameter("security")) {
        null -> bean.security =  if (bean is TrojanBean) "tls" else "none"
        "none", "tls", "reality" -> bean.security = security
        "xtls" -> bean.security = "tls"
        else -> {
            // Do not throw error. Some links are stupid.
            bean.security =  if (bean is TrojanBean) "tls" else "none"
        }
    }

    when (bean.security) {
        "none" -> {
            if (bean is VLESSBean) {
                url.queryParameter("flow")?.let {
                    when (it) {
                        in supportedVlessFlow -> {
                            bean.flow = "xtls-rprx-vision-udp443"
                            bean.packetEncoding = "xudp"
                        }
                        in legacyVlessFlow, "", "none" -> null
                        else -> error("unsupported vless flow")
                    }
                }
            }
        }
        "tls" -> {
            url.queryParameter("sni")?.let {
                bean.sni = it
            }
            url.queryParameter("alpn")?.let {
                bean.alpn = it.split(",").joinToString("\n")
            }
            if (bean is VLESSBean) {
                url.queryParameter("flow")?.let {
                    when (it) {
                        in supportedVlessFlow -> {
                            bean.flow = "xtls-rprx-vision-udp443"
                            bean.packetEncoding = "xudp"
                        }
                        in legacyVlessFlow, "", "none" -> null
                        else -> error("unsupported vless flow")
                    }
                }
            }
            // bad format from where?
            url.queryParameter("allowInsecure")?.let {
                if (it == "1" || it == "true") {
                    bean.allowInsecure = true // non-standard
                }
            }
            url.queryParameter("insecure")?.let {
                if (it == "1" || it == "true") {
                    bean.allowInsecure = true // non-standard
                }
            }
            url.queryParameter("allow_insecure")?.let {
                if (it == "1" || it == "true") {
                    bean.allowInsecure = true // non-standard
                }
            }
            url.queryParameter("pcs")?.takeIf { it.isNotEmpty() }?.let { pcs ->
                bean.pinnedPeerCertificateSha256 =
                    pcs.split(if (pcs.contains("~")) "~" else ",")
                        .mapNotNull { it.trim().ifEmpty { null }?.replace(":", "") }
                        .joinToString("\n")
                if (!bean.pinnedPeerCertificateSha256.isNullOrEmpty()) {
                    bean.allowInsecure = true
                }
            }
            if (url.scheme == "vless" || url.scheme == "trojan") {
                // Only parse ECH for shit VLESS or Trojan free nodes
                url.queryParameter("ech")?.let {
                    bean.echEnabled = true
                    try {
                        Base64.getDecoder().decode(it)
                        bean.echConfig = it
                    } catch (_: Exception) {}
                }
            }
        }
        "reality" -> {
            url.queryParameter("sni")?.let {
                bean.sni = it
            }
            url.queryParameter("pbk")?.ifEmpty { error("empty reality public key") }?.let {
                bean.realityPublicKey = it
            }
            url.queryParameter("sid")?.let {
                bean.realityShortId = it
            }
            url.queryParameter("pqv")?.let {
                bean.realityMldsa65Verify = it
            }
            if (bean is VLESSBean) {
                url.queryParameter("flow")?.let {
                    when (it) {
                        in supportedVlessFlow -> {
                            bean.flow = "xtls-rprx-vision-udp443"
                            bean.packetEncoding = "xudp"
                        }
                        "", "none" -> null
                        else -> error("unsupported vless flow")
                    }
                }
            }
        }
    }

    bean.type = url.queryParameter("type")
    when (bean.type) {
        "tcp", null -> {
            url.queryParameter("headerType")?.let { headerType ->
                // invented by v2rayN(G)
                when (headerType) {
                    "none" -> {}
                    "http" -> {
                        bean.headerType = headerType
                        url.queryParameter("host")?.let {
                            bean.host = it.split(",").joinToString("\n")
                        }
                    }
                    else -> error("unsupported headerType")
                }
            }
        }
        "kcp" -> {
            url.queryParameter("seed")?.let {
                bean.mKcpSeed = it
            }
            url.queryParameter("headerType")?.let {
                if (it !in supportedKcpQuicHeaderType) error("unsupported headerType")
                bean.headerType = it
            }
        }
        "http" -> {
            url.queryParameter("host")?.let {
                // The proposal says "省略时复用 remote-host", but this is not correct except for the breaking change below.
                // will not follow the breaking change in https://github.com/XTLS/Xray-core/commit/0a252ac15d34e7c23a1d3807a89bfca51cbb559b
                // "若有多个域名，可使用英文逗号隔开，但中间及前后不可有空格。"
                bean.host = it.split(",").joinToString("\n")
            }
            url.queryParameter("path")?.let {
                bean.path = it
            }
        }
        "xhttp", "splithttp" -> {
            bean.type = "splithttp"
            url.queryParameter("extra")?.let { extra ->
                try {
                    val json = parseJson(extra).asJsonObject
                    if (!json.isEmpty) {
                        // fuck RPRX `extra`
                        bean.splithttpExtra = GsonBuilder().setPrettyPrinting().create().toJson(json)
                    }
                } catch (_: Exception) {}
            }
            url.queryParameter("host")?.let {
                bean.host = it
            }
            url.queryParameter("path")?.let {
                bean.path = it
            }
            url.queryParameter("mode")?.let {
                bean.splithttpMode = when (it) {
                    in supportedXhttpMode -> it
                    "" -> "auto"
                    else -> error("unsupported xhttp mode")
                }
            }
        }
        "httpupgrade" -> {
            // Fuck Xray httpupgrade ALPN
            // https://github.com/XTLS/Xray-core/blob/1bdb488c9ec09ea51e6899697d5b7437f3cf6eb2/transport/internet/tls/tls.go#L94-L131
            bean.alpn = null
            url.queryParameter("host")?.let {
                // will not follow the breaking change in
                // https://github.com/XTLS/Xray-core/commit/a2b773135a860f63e990874c551b099dfc888471
                bean.host = it
            }
            url.queryParameter("path")?.let { path ->
                bean.path = path
                try {
                    // RPRX's smart-assed invention. This of course will break under some conditions.
                    val u = Libexclavecore.parseURL(path)
                    u.queryParameter("ed")?.let {
                        u.deleteQueryParameter("ed")
                        bean.path = u.string
                    }
                } catch (_: Exception) {}
            }
            url.queryParameter("eh")?.let {
                bean.earlyDataHeaderName = it // non-standard, invented by SagerNet and adopted by some other software
            }
            url.queryParameter("ed")?.toIntOrNull()?.let {
                bean.maxEarlyData = it // non-standard, invented by SagerNet and adopted by some other software
            }
        }
        "ws" -> {
            // Fuck Xray ws ALPN
            // https://github.com/XTLS/Xray-core/blob/1bdb488c9ec09ea51e6899697d5b7437f3cf6eb2/transport/internet/tls/tls.go#L94-L131
            bean.alpn = null
            url.queryParameter("host")?.let {
                // will not follow the breaking change in
                // https://github.com/XTLS/Xray-core/commit/a2b773135a860f63e990874c551b099dfc888471
                bean.host = it
            }
            url.queryParameter("path")?.let { path ->
                bean.path = path
                try {
                    // RPRX's smart-assed invention. This of course will break under some conditions.
                    val u = Libexclavecore.parseURL(path)
                    u.queryParameter("ed")?.let { ed ->
                        u.deleteQueryParameter("ed")
                        bean.path = u.string
                        bean.maxEarlyData = ed.toIntOrNull()
                        bean.earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    }
                } catch (_: Exception) {}
            }
            url.queryParameter("eh")?.let {
                bean.earlyDataHeaderName = it // non-standard, invented by SagerNet and adopted by some other software
            }
            url.queryParameter("ed")?.toIntOrNull()?.let {
                bean.maxEarlyData = it // non-standard, invented by SagerNet and adopted by some other software
            }
        }
        "quic" -> {
            url.queryParameter("headerType")?.let {
                if (it !in supportedKcpQuicHeaderType) error("unsupported headerType")
                bean.headerType = it
            }
            url.queryParameter("quicSecurity")?.let { quicSecurity ->
                if (quicSecurity !in supportedQuicSecurity) error("unsupported quicSecurity")
                bean.quicSecurity = quicSecurity
                url.queryParameter("key")?.let {
                    bean.quicKey = it
                }
            }
        }
        "grpc" -> {
            url.queryParameter("serviceName")?.let {
                // Xray hijacks the share link standard, uses escaped `serviceName` and some other non-standard `serviceName`s and breaks the compatibility with other implementations.
                // Fixing the compatibility with Xray will break the compatibility with V2Ray and others.
                // So do not fix the compatibility with Xray.
                bean.grpcServiceName = it
            }
            url.queryParameter("mode")?.takeIf { it == "multi" }?.let {
                // Xray private
                bean.grpcMultiMode = true
            }
        }
        "meek" -> {
            // https://github.com/v2fly/v2ray-core/discussions/2638
            url.queryParameter("url")?.let {
                bean.meekUrl = it
            }
        }
        "mekya" -> {
            // not a standard
            url.queryParameter("headerType")?.let {
                if (it !in supportedKcpQuicHeaderType) error("unsupported headerType")
                bean.mekyaKcpHeaderType = it
            }
            url.queryParameter("seed")?.let {
                bean.mekyaKcpSeed = it
            }
            url.queryParameter("url")?.let {
                bean.mekyaUrl = it
            }
        }
        "hysteria2", "hysteria" -> error("unsupported")
        else -> bean.type = "tcp"
    }

    url.queryParameter("fm")?.let { finalmask ->
        // fuck RPRX
        try {
            val json = parseJson(finalmask).asJsonObject
            if (!json.isEmpty) {
                when (bean.type) {
                    "tcp", "ws", "grpc", "httpupgrade" -> {
                        // ban Xray TCP finalmask
                        json.getArray("tcp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                            error("unsupported")
                        }
                    }
                    "kcp" -> {
                        json.getArray("udp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also { udpMasks ->
                            if (udpMasks.size !in 1..2) error("unsupported")
                            when (udpMasks.last().getString("type", ignoreCase = true)) {
                                "mkcp-original" -> {}
                                "mkcp-aes128gcm" -> {
                                    udpMasks.last().getObject("settings", ignoreCase = true)?.also { settings ->
                                        settings.getString("password", ignoreCase = true).orEmpty().also {
                                            if (it.isEmpty()) error("unsupported")
                                            bean.mKcpSeed = it
                                        }
                                    }
                                }
                                else -> error("unsupported")
                            }
                            if (udpMasks.size == 2) {
                                when (udpMasks.first().getString("type", ignoreCase = true)) {
                                    null -> {}
                                    "header-dtls" -> bean.headerType = "dtls"
                                    "header-srtp" -> bean.headerType = "srtp"
                                    "header-utp" -> bean.headerType = "utp"
                                    "header-wechat" -> bean.headerType = "wechat-video"
                                    "header-wireguard" -> bean.headerType = "wireguard"
                                    else -> error("unsupported")
                                }
                            }
                        }
                    }
                    "splithttp" -> {
                        if (bean.alpn != "h3") {
                            // ban Xray TCP finalmask
                            json.getArray("tcp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                                error("unsupported")
                            }
                        } else {
                            // ban Xray UDP finalmask
                            json.getArray("udp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                                error("unsupported")
                            }
                            // ban Xray QUIC port hopping
                            json.getObject("quicParams")?.also { quicParams ->
                                quicParams.getObject("udphop")?.also { udphop ->
                                    udphop.getInt("ports")?.also {
                                        error("unsupported")
                                    } ?: udphop.getString("ports")?.takeIf { it.isNotEmpty() }?.also {
                                        it.split(",").joinToString(",") { it.trim() }
                                            .takeIf { it.isValidHysteriaPort(disallowFromGreaterThanTo = true) }
                                            ?.also { error("unsupported") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    return bean
}

private fun parseV2RayN(json: JsonObject): VMessBean {
    // https://github.com/2dust/v2rayN/wiki/Description-of-VMess-share-link
    val bean = VMessBean().apply {
        serverAddress = json.getString("add")?.ifEmpty { error("empty host") } ?: error("missing server address")
        serverPort = (json.getString("port")?.toIntOrNull()
            ?: json.getInt("port"))?: error("invalid port")
        uuid = json.getString("id")?.let {
            uuidOrGenerate(it)
        }
        alterId = json.getString("aid")?.toIntOrNull() ?: json.getInt("aid")
        json.getString("scy")?.takeIf { it.isNotEmpty() }?.let {
            if (it !in supportedVmessMethod) error("unsupported vmess encryption")
            encryption = it
        }
        name = json.getString("ps")?.takeIf { it.isNotEmpty() }
    }

    val net = json.getString("net")
    bean.type = when (net) {
        "h2" -> "http"
        "xhttp" -> "splithttp"
        "tcp", "kcp", "ws", "http", "quic", "grpc", "httpupgrade", "splithttp" -> net
        else -> "tcp"
    }
    val type = json.getString("type")?.takeIf { it.isNotEmpty() }
    val host = json.getString("host")?.takeIf { it.isNotEmpty() }
    val path = json.getString("path")?.takeIf { it.isNotEmpty() }

    when (bean.type) {
        "tcp" -> {
            bean.host = host?.split(",")?.joinToString("\n") // "http(tcp)->host中间逗号(,)隔开"
            bean.path = path?.split(",")?.joinToString("\n") // see v2rayN(G) source code
            type?.let {
                if (it != "http" && it != "none") error("unsupported headerType")
                bean.headerType = it
            }
        }
        "kcp" -> {
            bean.mKcpSeed = path
            type?.let {
                if (it !in supportedKcpQuicHeaderType) error("unsupported headerType")
                bean.headerType = it
            }
        }
        "ws" -> {
            bean.host = host
            bean.path = path
            try {
                // RPRX's smart-assed invention. This of course will break under some conditions.
                val u = Libexclavecore.parseURL(bean.path)
                u.queryParameter("ed")?.let { ed ->
                    u.deleteQueryParameter("ed")
                    bean.path = u.string
                    bean.maxEarlyData = ed.toIntOrNull()
                    bean.earlyDataHeaderName = "Sec-WebSocket-Protocol"
                }
            } catch (_: Exception) {}
        }
        "httpupgrade" -> {
            bean.host = host
            bean.path = path
            try {
                // RPRX's smart-assed invention. This of course will break under some conditions.
                val u = Libexclavecore.parseURL(bean.path)
                u.queryParameter("ed")?.let {
                    u.deleteQueryParameter("ed")
                    bean.path = u.string
                }
            } catch (_: Exception) {}
        }
        "http" -> {
            bean.host = host?.split(",")?.joinToString("\n") // "http(tcp)->host中间逗号(,)隔开"
            bean.path = path
        }
        "quic" -> {
            bean.quicSecurity = host
            bean.quicKey = path
            type?.let {
                if (it !in supportedKcpQuicHeaderType) error("unsupported headerType")
                bean.headerType = it
            }
        }
        "grpc" -> {
            // Xray hijacks the share link standard, uses escaped `serviceName` and some other non-standard `serviceName`s and breaks the compatibility with other implementations.
            // Fixing the compatibility with Xray will break the compatibility with V2Ray and others.
            // So do not fix the compatibility with Xray.
            bean.grpcServiceName = bean.path
            type?.let {
                if (it == "multi") {
                    bean.grpcMultiMode = true // Xray private
                }
            }
        }
        "splithttp" -> {
            bean.host = host
            bean.path = path
            type?.let {
                when (it) {
                    "" -> bean.splithttpMode = "auto"
                    in supportedXhttpMode -> bean.splithttpMode = it
                    else -> error("unsupported xhttp mode")
                }
            }
        }
    }

    when (val security = json.getString("tls")) {
        "tls" -> {
            bean.security = security
            bean.name = json.getString("ps")?.takeIf { it.isNotEmpty() }
            // See https://github.com/2dust/v2rayNG/blob/5db2df77a01144b8f3d40116f8c183153f181d05/V2rayNG/app/src/main/java/com/v2ray/ang/handler/V2rayConfigManager.kt#L1077-L1242
            bean.sni = json.getString("sni")?.takeIf { it.isNotEmpty() } ?: host?.split(",")?.get(0)
            bean.alpn = json.getString("alpn")?.takeIf { it.isNotEmpty() }?.split(",")?.joinToString("\n")
            json.getString("insecure")?.takeIf { it == "1" }?.let {
                bean.allowInsecure = true
            }
            json.getInt("insecure")?.takeIf { it == 1 }?.let {
                bean.allowInsecure = true
            }
            json.getString("pcs")?.takeIf { it.isNotEmpty() }?.let { pcs ->
                bean.pinnedPeerCertificateSha256 =
                    pcs.split(if (pcs.contains("~")) "~" else ",")
                        .mapNotNull { it.trim().ifEmpty { null }?.replace(":", "") }
                        .joinToString("\n")
                if (!bean.pinnedPeerCertificateSha256.isNullOrEmpty()) {
                    bean.allowInsecure = true
                }
            }
        }
        "reality" -> {
            error("v2rayN(G) style link lacks REALITY public key support and does not work at all.")
        }
        else -> bean.security = "none"
    }

    // https://github.com/2dust/v2rayN/blob/737d563ebb66d44504c3a9f51b7dcbb382991dfd/v2rayN/v2rayN/Handler/ConfigHandler.cs#L701-L743
    if (!json.contains("v")
        || (json.getString("v") != null && json.getString("v")!!.toIntOrNull() != null && json.getString("v")!!.toIntOrNull()!! < 2)
        || (json.getInt("v") != null && json.getInt("v")!! < 2)) {
        when (net) {
            "ws", "h2" -> {
                host?.replace(" ", "")?.split(";")?.let {
                    if (it.isNotEmpty()) {
                        bean.path = it[0]
                        bean.host = ""
                    }
                    if (it.size > 1) {
                        bean.path = it[0]
                        bean.host = it[1]
                    }
                }
            }
        }
    }

    return bean

}

fun StandardV2RayBean.toUri(): String? {
    val builder = Libexclavecore.newURL(
        when (this) {
            is VMessBean -> "vmess"
            is VLESSBean -> "vless"
            is TrojanBean -> "trojan"
            else -> error("impossible")
        }
    ).apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        if (name.isNotEmpty()) {
            fragment = name
        }
    }

    when (this) {
        is TrojanBean -> {
            if (password.isNotEmpty()) {
                builder.username = password
            }
        }
        is VMessBean -> {
            builder.username = uuidOrGenerate(uuid)
            builder.addQueryParameter("encryption", encryption)
        }
        is VLESSBean -> {
            builder.username = uuidOrGenerate(uuid)
            when (encryption) {
                "none" -> builder.addQueryParameter("encryption", "none")
                "" -> error("unsupported vless encryption")
                else -> {
                    val parts = encryption.split(".")
                    if (parts.size < 4 || parts[0] != "mlkem768x25519plus"
                        || !(parts[1] == "native" || parts[1] == "xorpub" || parts[1] == "random")
                        || !(parts[2] == "1rtt" || parts[2] == "0rtt")) {
                        error("unsupported vless encryption")
                    }
                    builder.addQueryParameter("encryption", encryption)
                }
            }
        }
    }

    when (type) {
        "tcp" -> {
            // do not add `type=tcp` for Trojan if possible
            if (this !is TrojanBean || headerType == "http") {
                builder.addQueryParameter("type", "tcp")
            }
        }
        "splithttp" -> {
            builder.addQueryParameter("type", "xhttp")
        }
        "kcp", "ws", "http", "httpupgrade", "quic", "grpc", "meek", "meyka" -> {
            builder.addQueryParameter("type", type)
        }
        else -> error("unsupported transport")
    }

    when (type) {
        "tcp" -> {
            if (headerType == "http") {
                // invented by v2rayNG
                builder.addQueryParameter("headerType", headerType)
                if (host.isNotEmpty()) {
                    builder.addQueryParameter("host", host.listByLineOrComma().joinToString(","))
                }
            }
        }
        "kcp" -> {
            if (headerType != "none") {
                builder.addQueryParameter("headerType", headerType)
            }
            if (mKcpSeed.isEmpty()) {
                builder.addQueryParameter("fm", JsonObject().apply {
                    // fuck rprx finalmask
                    add("udp", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "mkcp-original")
                        })
                    })
                }.toString())
            } else {
                builder.addQueryParameter("seed", mKcpSeed)
                builder.addQueryParameter("fm", JsonObject().apply {
                    // fuck rprx finalmask
                    add("udp", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "mkcp-aes128gcm")
                            add("settings", JsonObject().apply {
                                addProperty("password", mKcpSeed)
                            })
                        })
                    })
                }.toString())
            }
        }
        "ws" -> {
            if (host.isNotEmpty()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotEmpty()) {
                builder.addQueryParameter("path", path)
            }
            if (earlyDataHeaderName.isNotEmpty()) {
                // non-standard, invented by SagerNet and adopted by some other software
                builder.addQueryParameter("eh", earlyDataHeaderName)
            }
            if (maxEarlyData > 0) {
                // non-standard, invented by SagerNet and adopted by some other software
                builder.addQueryParameter("ed", maxEarlyData.toString())
            }
        }
        "http" -> {
            if (host.isNotEmpty()) {
                builder.addQueryParameter("host", host.listByLineOrComma().joinToString(","))
            }
            if (path.isNotEmpty()) {
                builder.addQueryParameter("path", path)
            }
        }
        "httpupgrade" -> {
            if (host.isNotEmpty()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotEmpty()) {
                builder.addQueryParameter("path", path)
            }
            if (earlyDataHeaderName.isNotEmpty()) {
                // non-standard, invented by SagerNet and adopted by some other software
                builder.addQueryParameter("eh", earlyDataHeaderName)
            }
            if (maxEarlyData > 0) {
                // non-standard, invented by SagerNet and adopted by some other software
                builder.addQueryParameter("ed", maxEarlyData.toString())
            }
        }
        "splithttp" -> {
            if (host.isNotEmpty()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotEmpty()) {
                builder.addQueryParameter("path", path)
            }
            builder.addQueryParameter("mode", splithttpMode)
            if (splithttpExtra.isNotEmpty()) {
                parseJson(splithttpExtra).asJsonObject?.takeIf { !it.isEmpty }?.let {
                    // fuck RPRX `extra`
                    builder.addQueryParameter("extra", it.toString())
                }
            }
        }
        "quic" -> {
            if (headerType != "none") {
                builder.addQueryParameter("headerType", headerType)
            }
            if (quicSecurity.isNotEmpty() && quicSecurity != "none") {
                builder.addQueryParameter("quicSecurity", quicSecurity)
                builder.addQueryParameter("key", quicKey)
            }
        }
        "grpc" -> {
            if (grpcServiceName.isNotEmpty()) {
                builder.addQueryParameter("serviceName", grpcServiceName)
            }
            if (grpcMultiMode) {
                builder.addQueryParameter("mode", "multi") // Xray private
            }
        }
        "meek" -> {
            // https://github.com/v2fly/v2ray-core/discussions/2638
            if (meekUrl.isNotEmpty()) {
                builder.addQueryParameter("url", meekUrl)
            }
        }
        "mekya" -> {
            // not a standard
            if (headerType != "none") {
                builder.addQueryParameter("headerType", mekyaKcpHeaderType)
            }
            if (mekyaKcpSeed.isNotEmpty()) {
                builder.addQueryParameter("seed", mekyaKcpSeed)
            }
            if (mekyaUrl.isNotEmpty()) {
                builder.addQueryParameter("url", mekyaUrl)
            }
        }
    }

    when (security) {
        "tls" -> {
            // do not add `security=tls` for Trojan if possible
            if (this !is TrojanBean) {
                builder.addQueryParameter("security", security)
            }
        }
        else -> {
            builder.addQueryParameter("security", security)
        }
    }

    when (security) {
        "none" -> {
            if (this is VLESSBean && flow.isNotEmpty()) {
                builder.addQueryParameter("flow", flow.removeSuffix("-udp443"))
            }
        }
        "tls" -> {
            if (sni.isNotEmpty()) {
                if (this !is TrojanBean || sni != serverAddress) {
                    // do not add `sni` for Trojan if possible
                    builder.addQueryParameter("sni", sni)
                }
            }
            if (alpn.isNotEmpty()) {
                builder.addQueryParameter("alpn", alpn.listByLineOrComma().joinToString(","))
            }
            // as pinned certificate is not exportable, only add `allowInsecure=1` if pinned certificate is not used
            if (allowInsecure && pinnedPeerCertificateSha256.isEmpty() &&
                pinnedPeerCertificatePublicKeySha256.isEmpty() && pinnedPeerCertificateChainSha256.isEmpty()) {
                // bad format from where?
                builder.addQueryParameter("allowInsecure", "1")
            }
            if (pinnedPeerCertificateSha256.isNotEmpty()) {
                builder.addQueryParameter("pcs", pinnedPeerCertificateSha256.listByLineOrComma().joinToString("~"))
            }
            if (this is VLESSBean && flow.isNotEmpty()) {
                builder.addQueryParameter("flow", flow.removeSuffix("-udp443"))
            }
        }
        "reality" -> {
            if (sni.isNotEmpty()) {
                builder.addQueryParameter("sni", sni)
            }
            builder.addQueryParameter("pbk", realityPublicKey.ifEmpty { error("empty reality public key") })
            if (realityShortId.isNotEmpty()) {
                builder.addQueryParameter("sid", realityShortId)
            }
            if (realityMldsa65Verify.isNotEmpty()) {
                builder.addQueryParameter("pqv", realityMldsa65Verify)
            }
            builder.addQueryParameter("fp", "chrome") // "若使用 REALITY，此项不可省略。"
            if (this is VLESSBean && flow.isNotEmpty()) {
                builder.addQueryParameter("flow", flow.removeSuffix("-udp443"))
            }
        }
    }

    return builder.string
}