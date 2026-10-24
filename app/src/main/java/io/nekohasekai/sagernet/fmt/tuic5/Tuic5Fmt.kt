/******************************************************************************
 *                                                                            *
 * Copyright (C) 2023  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.tuic5

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import io.nekohasekai.sagernet.ktx.queryParameterNotBlank
import libsagernetcore.Libsagernetcore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

val supportedTuic5CongestionControl = arrayOf("cubic", "bbr", "new_reno")
val supportedTuic5RelayMode = arrayOf("native", "quic")

@OptIn(ExperimentalUuidApi::class)
fun parseTuic(server: String): AbstractBean {
    var link = Libsagernetcore.parseURL(server)
    if (link.queryParameter("version") == "4") {
        error("unsupported")
    }
    if (server.length >= 46
        && server.substring(7, 15).all {
            (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F')
        } && server[15] == '-'
        && server.substring(16, 20).all {
            (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F')
        } && server[20] == '-'
        && server.substring(21, 25).all {
            (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F')
        } && server[25] == '-'
        && server.substring(26, 30).all {
            (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F')
        } && server[30] == '-'
        && server.substring(31, 43).all {
            (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F')
        } && server.substring(43, 46) == "%3A"
    ) {
        // v2rayN broken format
        link = Libsagernetcore.parseURL(server.take(43) + ":" + server.substring(46, server.length))
    }

    try {
        Uuid.parse(link.username)
    } catch (_: Exception) {
        error("unsupported")
    }

    return Tuic5Bean().apply {
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port
        if (link.port == 0) {
            serverPort = 443
        }
        uuid = link.username
        password = link.password
        link.queryParameterNotBlank("sni")?.let {
            sni = it
        }
        link.queryParameterNotBlank("alpn")?.let {
            alpn = it.split(",").joinToString("\n")
        }
        (link.queryParameter("congestion_controller") ?:
        link.queryParameter("congestion-controller") ?:
        link.queryParameter("congestion_control") ?:
        link.queryParameter("congestion-control"))?.let {
            congestionControl = when (it) {
                in supportedTuic5CongestionControl -> it
                "new-reno" -> "new_reno"
                else -> "cubic"
            }
        }
        (link.queryParameter("udp-relay-mode") ?:
        link.queryParameter("udp_relay_mode") ?:
        link.queryParameter("udp-relay_mode") ?:
        link.queryParameter("udp_relay-mode"))?.let {
            udpRelayMode = when (it) {
                in supportedTuic5RelayMode -> it
                else -> "native"
            }
        }
        (link.queryParameter("disable_sni") ?: link.queryParameter("disable-sni"))
            ?.takeIf { it == "1" || it == "true" }?.let {
                disableSNI = true
            }
        (link.queryParameter("reduce_rtt") ?: link.queryParameter("reduce-rtt"))
            ?.takeIf { it == "1" || it == "true" }?.let {
                zeroRTTHandshake = true
            }
        (link.queryParameter("allow_insecure") ?: link.queryParameter("allow-insecure") ?:
        link.queryParameter("insecure") ?: link.queryParameter("allowInsecure"))
            ?.takeIf { it == "1" || it == "true" }?.let {
            allowInsecure = true
        }
        link.fragment?.let {
            name = it
        }
    }
}

fun Tuic5Bean.toUri(): String? {
    val builder = Libsagernetcore.newURL("tuic").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        username = uuid.ifEmpty { error("empty uuid") }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }
    builder.addQueryParameter("version", "5")
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)
    builder.addQueryParameter("congestion_control", congestionControl)
    builder.addQueryParameter("congestion_controller", congestionControl)
    if (sni.isNotEmpty()) {
        builder.addQueryParameter("sni", sni)
    }
    if (alpn.isNotEmpty()) {
        builder.addQueryParameter("alpn", alpn.listByLineOrComma().joinToString(","))
    }
    if (disableSNI) {
        builder.addQueryParameter("disable_sni", "1")
    }
    if (zeroRTTHandshake) {
        builder.addQueryParameter("reduce_rtt", "1")
    }
    // as pinned certificate is not exportable, only add `allow_insecure=1` if pinned certificate is not used
    if (allowInsecure && pinnedPeerCertificateSha256.isEmpty() &&
        pinnedPeerCertificatePublicKeySha256.isEmpty() && pinnedPeerCertificateChainSha256.isEmpty()) {
        builder.addQueryParameter("allow_insecure", "1")
    }
    return builder.string
}
