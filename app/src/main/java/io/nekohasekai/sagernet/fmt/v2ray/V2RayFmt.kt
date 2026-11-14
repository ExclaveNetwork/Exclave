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
import java.io.ByteArrayOutputStream
import kotlin.collections.filter
import kotlin.collections.isNotEmpty
import kotlin.io.encoding.Base64
import kotlin.text.isNotEmpty
import kotlin.uuid.Uuid

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

    if (url.scheme == "vmess" && !url.hasPort() && url.userInfo.isEmpty()) {
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

    if (url.scheme == "vmess" && url.hasPassword()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26
        error("known unsupported format")
    }

    bean.serverAddress = url.host
    bean.serverPort = when {
        !url.hasPort() -> error("invalid port")
        else -> url.port
    }
    bean.name = url.fragment

    if (bean is TrojanBean) {
        // https://github.com/trojan-gfw/igniter/issues/318
        bean.password = if (url.hasPassword()) {
            url.username + ":" + url.password
        } else {
            url.username
        }
    } else {
        bean.uuid = parseRayUUID(url.username) ?: parseUUID(url.username)?.toHexDashString() ?: uuid5(url.username)
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
                // TODO: validate VLESS encryption
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
                val hashes = pcs.split(",")
                    .mapNotNull { it.trim().ifEmpty { null }?.replace(":", "") }
                for (hash in hashes) {
                    try {
                        require(hash.hexToByteArray().size == 32)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("invalid pcs")
                    }
                }
                bean.pinnedPeerCertificateSha256 = hashes.joinToString("\n")
                if (!bean.pinnedPeerCertificateSha256.isNullOrEmpty()) {
                    bean.allowInsecure = true
                }
            }
            url.queryParameter("vcn")?.takeIf { it.isNotEmpty() }?.let { vcn ->
                bean.serverNameToVerify = vcn.split(",")
                    .filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
            }
            url.queryParameter("ech")?.takeIf { it.isNotEmpty() }?.let {
                bean.echEnabled = true
                // See the shit in https://github.com/XTLS/Xray-core/blob/f124daf5a37c3b968a618f92ca42396f3c001de5/transport/internet/tls/ech.go#L50-L83
                if (it.contains("://")) {
                    val parts = it.split("+", limit = 2)
                    if (parts.size == 2) {
                        bean.echQueryName = parts[0]
                    }
                } else {
                    try {
                        Base64.decode(it)
                        bean.echConfigList = it
                        bean.echQueryName = ""
                    } catch (_: Exception) {}
                }
            }
        }
        "reality" -> {
            url.queryParameter("sni")?.let {
                bean.sni = it
            }
            url.queryParameter("pbk")?.let {
                bean.realityPublicKey = it
            }
            try {
                require(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(bean.realityPublicKey).size == 32)
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid pbk")
            }
            url.queryParameter("sid")?.let {
                try {
                    require(it.hexToByteArray().size <= 8)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid sid")
                }
                bean.realityShortId = it
            }
            url.queryParameter("pqv")?.let {
                try {
                    require(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).size == 1952)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid pqv")
                }
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
        "tcp", "raw", null -> {
            bean.type = "tcp"
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
                    u.queryParameter("ed")?.takeIf { it.isNotEmpty() }?.let {
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
                    u.queryParameter("ed")?.takeIf { it.isNotEmpty() }?.let { ed ->
                        u.deleteQueryParameter("ed")
                        bean.path = u.string
                        ed.toIntOrNull()?.takeIf { it > 0 }?.let {
                            bean.maxEarlyData = it
                            bean.earlyDataHeaderName = "Sec-WebSocket-Protocol"
                        }
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
                    "tcp", "ws", "grpc", "httpupgrade", "http" -> {
                        // ban Xray TCP finalmask
                        json.getArray("tcp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                            error("unsupported")
                        }
                    }
                    "kcp" -> {
                        json.getArray("udp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also { udpMasks ->
                            if (udpMasks.size !in 1..2) error("unsupported")
                            var isMkcpLegacy = false
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
                                "mkcp-legacy" -> {
                                    isMkcpLegacy = true
                                    udpMasks.last().getObject("settings", ignoreCase = true)?.also { settings ->
                                        settings.getString("header", ignoreCase = true).orEmpty().lowercase().also {
                                            when (it) {
                                                "dtls", "srtp", "utp", "wireguard" -> bean.headerType = it
                                                "wechat" -> bean.headerType = "wechat-video"
                                                else -> error("unsupported")
                                            }
                                        }
                                    }
                                }
                                else -> error("unsupported")
                            }
                            if (udpMasks.size == 2) {
                                when (val type = udpMasks.first().getString("type", ignoreCase = true)) {
                                    null -> {}
                                    "header-wechat" -> {
                                        if (isMkcpLegacy) error("unsupported")
                                        bean.headerType = "wechat-video"
                                    }
                                    "header-dtls", "header-srtp", "header-utp", "header-wireguard" -> {
                                        if (isMkcpLegacy) error("unsupported")
                                        bean.headerType = type.removePrefix("header-")
                                    }
                                    "mkcp-legacy" -> {
                                        if (!isMkcpLegacy) error("unsupported")
                                        udpMasks.first().getObject("settings", ignoreCase = true)?.also { settings ->
                                            settings.getString("header", ignoreCase = true).orEmpty().also {
                                                if (it.isNotEmpty()) error("unsupported")
                                            }
                                            settings.getString("value", ignoreCase = true).orEmpty().also {
                                                bean.mKcpSeed = it
                                            }
                                        }
                                    }
                                    else -> error("unsupported")
                                }
                            }
                        }
                    }
                    "splithttp" -> {
                        // leave it broken, I don't care
                        // ban Xray TCP finalmask
                        json.getArray("tcp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                            error("unsupported")
                        }
                        // ban Xray UDP finalmask
                        json.getArray("udp", ignoreCase = true)?.takeIf { it.isNotEmpty() }?.also {
                            error("unsupported")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }

    if (bean.security == "reality") {
        when (bean.type) {
            "tcp", "http", "grpc", "splithttp" -> {}
            else -> error("reality does not support ${bean.type}")
        }
    }
    if (bean is VLESSBean && bean.security != "none" && bean.flow == "xtls-rprx-vision-udp443"
        && bean.type != "tcp" && bean.encryption == "none") {
        error("vision does not support ${bean.type}")
    }

    return bean
}

private fun parseV2RayN(json: JsonObject): VMessBean {
    // https://github.com/2dust/v2rayN/wiki/Description-of-VMess-share-link
    val bean = VMessBean().apply {
        serverAddress = json.getString("add") ?: error("missing server address")
        serverPort = (json.getString("port")?.toIntOrNull()
            ?: json.getInt("port"))?: error("invalid port")
        json.getString("id").orEmpty().let {
            uuid = parseRayUUID(it) ?: parseUUID(it)?.toHexDashString() ?: uuid5(it)
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
            bean.path = path?.split(",")?.joinToString("\n") // See https://github.com/ExclaveNetwork/Exclave/issues/357
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
                u.queryParameter("ed")?.takeIf { it.isNotEmpty() }?.let { ed ->
                    u.deleteQueryParameter("ed")
                    bean.path = u.string
                    ed.toIntOrNull()?.takeIf { it > 0 }?.let {
                        bean.maxEarlyData = it
                        bean.earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    }
                }
            } catch (_: Exception) {}
        }
        "httpupgrade" -> {
            bean.host = host
            bean.path = path
            try {
                // RPRX's smart-assed invention. This of course will break under some conditions.
                val u = Libexclavecore.parseURL(bean.path)
                u.queryParameter("ed")?.takeIf { it.isNotEmpty() }?.let {
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
                val hashes = pcs.split(",")
                    .mapNotNull { it.trim().ifEmpty { null }?.replace(":", "") }
                for (hash in hashes) {
                    try {
                        require(hash.hexToByteArray().size == 32)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("invalid pcs")
                    }
                }
                bean.pinnedPeerCertificateSha256 = hashes.joinToString("\n")
                if (!bean.pinnedPeerCertificateSha256.isNullOrEmpty()) {
                    bean.allowInsecure = true
                }
            }
            json.getString("vcn")?.takeIf { it.isNotEmpty() }?.let { vcn ->
                bean.serverNameToVerify = vcn.split(",")
                    .filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
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
        setHostPort(serverAddress, serverPort)
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
            builder.username = parseRayUUID(uuid) ?: parseUUID(uuid)?.toHexDashString() ?: uuid5(uuid)
            builder.addQueryParameter("encryption", encryption)
            if (alterId > 0) {
                error("unsupported vmess alterId")
            }
        }
        is VLESSBean -> {
            require(Uuid.parseHexDashOrNull(uuid) != null) { "invalid uuid" }
            builder.username = uuid
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
                    // TODO: validate VLESS encryption
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
                host.listByLineOrComma().takeIf { it.isNotEmpty() }?.also {
                    builder.addQueryParameter("host", it.joinToString(","))
                }
                // See https://github.com/ExclaveNetwork/Exclave/issues/357
                /*path.listByLineOrComma().takeIf { it.isNotEmpty() }?.also {
                    builder.addQueryParameter("path", it.joinToString(","))
                }*/
            }
        }
        "kcp" -> {
            if (headerType != "none") {
                builder.addQueryParameter("headerType", headerType)
            }
            if (mKcpSeed.isNotEmpty()) {
                builder.addQueryParameter("seed", mKcpSeed)
            }
            // fuck rprx finalmask
            builder.addQueryParameter("fm", JsonObject().apply {
                add("udp", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "mkcp-legacy")
                        if (mKcpSeed.isNotEmpty()) {
                            add("settings", JsonObject().apply {
                                addProperty("value", mKcpSeed)
                            })
                        }
                    })
                    when (headerType) {
                        "none" -> {}
                        "srtp", "utp", "dtls", "wireguard" -> {
                            add(JsonObject().apply {
                                addProperty("type", "mkcp-legacy")
                                add("settings", JsonObject().apply {
                                    addProperty("header", headerType)
                                })
                            })
                        }
                        "wechat-video" -> {
                            add(JsonObject().apply {
                                addProperty("type", "mkcp-legacy")
                                add("settings", JsonObject().apply {
                                    addProperty("header", "wechat")
                                })
                            })
                        }
                    }
                })
            }.toString())
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
            if (alpn.listByLineOrComma().isNotEmpty()) {
                builder.addQueryParameter("alpn", alpn.listByLineOrComma().joinToString(","))
            } else {
                if (type == "quic") {
                    // https://github.com/ExclaveNetwork/Exclave/issues/488
                    builder.addQueryParameter("alpn", "h3")
                }
            }
            // as pinned certificate is not exportable, only add `allowInsecure=1` if pinned certificate is not used
            if (allowInsecure && pinnedPeerCertificateSha256.listByLineOrComma().isEmpty() &&
                pinnedPeerCertificatePublicKeySha256.listByLineOrComma().isEmpty() &&
                pinnedPeerCertificateChainSha256.listByLineOrComma().isEmpty() &&
                serverNameToVerify.listByLineOrComma().isEmpty()) {
                // bad format from where?
                builder.addQueryParameter("allowInsecure", "1")
            }
            pinnedPeerCertificateSha256.listByLineOrComma().takeIf { it.isNotEmpty() }?.also { hashes ->
                hashes.forEach {
                    try {
                        require(it.hexToByteArray().size == 32)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("invalid pcs")
                    }
                }
                builder.addQueryParameter("pcs", hashes.joinToString(","))
            }
            serverNameToVerify.listByLineOrComma().takeIf { it.isNotEmpty() }?.also {
                if (it.contains("")) error("serverNameToVerify contains empty value")
                builder.addQueryParameter("vcn", it.joinToString(","))
            }
            if (this is VLESSBean && flow.isNotEmpty()) {
                builder.addQueryParameter("flow", flow.removeSuffix("-udp443"))
            }
            if (echEnabled && echConfigList.isNotEmpty()) {
                // The `example.com+https://1.1.1.1/dns-query` is shit,
                // so echQueryName is ignored.
                try {
                    // TODO: validate echConfig
                    Base64.decode(echConfigList)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid ech")
                }
                builder.addQueryParameter("ech", echConfigList)
            }
        }
        "reality" -> {
            if (sni.isNotEmpty()) {
                builder.addQueryParameter("sni", sni)
            }
            try {
                require(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(realityPublicKey).size == 32)
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid pbk")
            }
            builder.addQueryParameter("pbk", realityPublicKey)
            if (realityShortId.isNotEmpty()) {
                try {
                    require(realityShortId.hexToByteArray().size <= 8)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid sid")
                }
                builder.addQueryParameter("sid", realityShortId)
            }
            if (realityMldsa65Verify.isNotEmpty()) {
                try {
                    require(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(realityMldsa65Verify).size == 1952)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid pqv")
                }
                builder.addQueryParameter("pqv", realityMldsa65Verify)
            }
            // builder.addQueryParameter("fp", "chrome") // "chrome" is only a placeholder because "若使用 REALITY，此项不可省略。".
            if (this is VLESSBean && flow.isNotEmpty()) {
                builder.addQueryParameter("flow", flow.removeSuffix("-udp443"))
            }
        }
    }

    if (security == "reality") {
        when (type) {
            "tcp", "http", "grpc", "splithttp" -> {}
            else -> error("reality does not support $type")
        }
    }
    if (this is VLESSBean && security != "none" && flow.isNotEmpty() && type != "tcp" && encryption == "none") {
        error("vision does not support $type")
    }

    return builder.string
}

fun parseRayUUID(str: String): String? {
    if (str.isEmpty()) {
        return null
    }
    if (str.length <= 30) {
        // See https://github.com/XTLS/Xray-core/blob/cd4ce973e9f6ef3a7acf9a7030927b4143f9ea47/common/uuid/uuid.go#L71-L83
        return uuid5(str)
    }
    if (str.length < 32) {
        return null
    }
    // https://github.com/v2fly/v2ray-core/blob/3861a919016991f2a2b65e460bcec18652e35a1e/common/uuid/uuid.go#L64-L88
    // For example:
    // 2418d087648d499086e819dca1d006d3
    // -2418d087-648d-4990-86e8-19dca1d006d3
    // 2418d087-648d499086e819dca1d006d3
    // 2418d087-648d-4990-86e8-19dca1d006d3💩
    // They are all valid.
    var text = str
    val uuid = ByteArrayOutputStream()
    for (byteGroup in listOf(8, 4, 4, 4, 12)) {
        if (text[0] == '-') {
            text = text.substring(1)
        }
        if (text.length < byteGroup) {
            return null
        }
        uuid.write(text.substring(0, byteGroup).hexToByteArray())
        text = text.substring(byteGroup)
    }
    return Uuid.fromByteArray(uuid.toByteArray()).toHexDashString()
}

// https://github.com/XTLS/Xray-core/blob/52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120/infra/conf/common.go#L292-L380
fun JsonObject.getXrayRangeAsTriple(key: String): Triple<Int, Int, Boolean>? {
    this.getString(key, ignoreCase = true)?.also { value ->
        value.toIntOrNull()?.also {
            return Triple(it, it, true)
        }
        if (value.isEmpty()) {
            return Triple(0, 0, true)
        }
        val pair = if (value.startsWith("-")) {
            val parts = value.split("-", limit = 3)
            if (parts.size < 3) {
                listOf(value)
            } else {
                listOf(parts[0] + "-" + parts[1], parts[2])
            }
        } else {
            value.split("-", limit = 2)
        }
        if (pair.size == 2) {
            val from = pair[0].toIntOrNull()
            val to = pair[1].toIntOrNull()
            return if (from != null && to != null) {
                Triple(minOf(from, to), maxOf(from, to), false)
            } else null
        }
    }
    this.getInt(key, ignoreCase = true)?.also {
        return Triple(it, it, true)
    }
    return null
}

fun JsonObject.getXrayRange(key: String): String? {
    val value = this.getXrayRangeAsTriple(key) ?: return null
    return if (value.third) "${value.first}" else "${value.first}-${value.second}"
}