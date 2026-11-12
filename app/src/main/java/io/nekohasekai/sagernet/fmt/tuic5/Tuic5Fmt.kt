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
import io.nekohasekai.sagernet.ktx.parseUUID
import io.nekohasekai.sagernet.ktx.queryParameter
import libexclavecore.Libexclavecore
import kotlin.uuid.Uuid

val supportedTuic5CongestionControl = arrayOf("cubic", "bbr", "new_reno")
val supportedTuic5RelayMode = arrayOf("native", "quic")

fun parseTuic(server: String): AbstractBean {
    val link = Libexclavecore.parseURL(server)
    if (link.queryParameter("version") == "4") {
        error("unsupported")
    }
    return Tuic5Bean().apply {
        serverAddress = link.host
        serverPort = when {
            !link.hasPort() -> 443
            else -> link.port
        }
        val u = when {
            // BEGIN v2rayN broken format
            !link.hasPassword() && link.username.length >= 33 && link.username[32] == ':'
                    && Uuid.parseHexOrNull(link.username.substring(0, 32)) != null -> {
                if (link.username.length >= 34) {
                    password = link.username.substring(33)
                }
                Uuid.parseHex(link.username.substring(0, 32))
            }
            !link.hasPassword() && link.username.length >= 35 && link.username[34] == ':'
                    && link.username[0] == '{' && link.username[33] == '}'
                    && Uuid.parseHexOrNull(link.username.substring(1, 33)) != null -> {
                if (link.username.length >= 36) {
                    password = link.username.substring(35)
                }
                Uuid.parseHex(link.username.substring(1, 33))
            }
            !link.hasPassword() && link.username.length >= 37 && link.username[36] == ':'
                    && Uuid.parseHexDashOrNull(link.username.substring(0, 36)) != null -> {
                if (link.username.length >= 38) {
                    password = link.username.substring(37)
                }
                Uuid.parseHexDash(link.username.substring(0, 36))
            }
            !link.hasPassword() && link.username.length >= 39 && link.username[38] == ':'
                    && link.username[0] == '{' && link.username[37] == '}'
                    && Uuid.parseHexDashOrNull(link.username.substring(1, 37)) != null -> {
                if (link.username.length >= 40) {
                    password = link.username.substring(39)
                }
                Uuid.parseHexDash(link.username.substring(1, 37))
            }
            !link.hasPassword() && link.username.length >= 42 && link.username[41] == ':'
                    && link.username.substring(0, 9) == "urn:uuid:"
                    && Uuid.parseHexOrNull(link.username.substring(9, 41)) != null -> {
                if (link.username.length >= 43) {
                    password = link.username.substring(42)
                }
                Uuid.parseHex(link.username.substring(9, 41))
            }
            !link.hasPassword() && link.username.length >= 46 && link.username[45] == ':'
                    && link.username.substring(0, 9) == "urn:uuid:"
                    && Uuid.parseHexDashOrNull(link.username.substring(9, 45)) != null -> {
                if (link.username.length >= 47) {
                    password = link.username.substring(46)
                }
                Uuid.parseHexDash(link.username.substring(9, 45))
            }
            // END v2rayN broken format
            else -> {
                password = link.password
                parseUUID(link.username)
            }
        }
        require(u != null) { "invalid uuid" }
        uuid = u.toHexDashString()
        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("alpn")?.let {
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
    val builder = Libexclavecore.newURL("tuic").apply {
        setHostPort(serverAddress, serverPort)
        require(Uuid.parseHexDashOrNull(uuid) != null) { "invalid uuid" }
        username = uuid
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
        pinnedPeerCertificatePublicKeySha256.isEmpty() && pinnedPeerCertificateChainSha256.isEmpty() &&
        serverNameToVerify.listByLineOrComma().isEmpty()) {
        builder.addQueryParameter("allow_insecure", "1")
    }
    return builder.string
}
