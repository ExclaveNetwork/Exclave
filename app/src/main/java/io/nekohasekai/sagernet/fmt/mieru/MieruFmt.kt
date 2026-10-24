/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore

fun parseMieru(link: String): List<MieruBean> {
    val beans = mutableListOf<MieruBean>()
    val url = Libsagernetcore.parseURL(link)
    if (url.username.isNullOrEmpty() || url.password.isNullOrEmpty()) {
        error("empty username or password")
    }
    val portCount = url.countQueryParameter("port")
    if (portCount != url.countQueryParameter("protocol")) {
        error("port count and protocol count mismatch")
    }
    if (portCount == 0L) {
        error("missing port and protocol")
    }
    val tcpPorts = mutableListOf<String>()
    val udpPorts = mutableListOf<String>()
    for (i in 0..<portCount) {
        when (val protocol = url.getQueryParameterAt("protocol", i)) {
            "UDP" -> udpPorts.add(url.getQueryParameterAt("port", i))
            "TCP" -> tcpPorts.add(url.getQueryParameterAt("port", i))
            else -> error("unknown protocol: $protocol")
        }
    }
    val profileName = url.queryParameter("profile")
    val multiplexing = url.queryParameter("multiplexing")?.let {
        when (it) {
            "MULTIPLEXING_OFF" -> MieruBean.MULTIPLEXING_OFF
            "MULTIPLEXING_LOW" -> MieruBean.MULTIPLEXING_LOW
            "MULTIPLEXING_MIDDLE" -> MieruBean.MULTIPLEXING_MIDDLE
            "MULTIPLEXING_HIGH" -> MieruBean.MULTIPLEXING_HIGH
            else -> MieruBean.MULTIPLEXING_DEFAULT
        }
    }
    val handshakemode = url.queryParameter("handshake-mode")?.let {
        when (it) {
            "HANDSHAKE_STANDARD" -> MieruBean.HANDSHAKE_STANDARD
            "HANDSHAKE_NO_WAIT" -> MieruBean.HANDSHAKE_NO_WAIT
            else -> MieruBean.HANDSHAKE_DEFAULT
        }
    }
    val trafficpattern = url.queryParameter("traffic-pattern")
    if (tcpPorts.isNotEmpty()) {
        beans.add(MieruBean().apply {
            serverAddress = url.host.ifEmpty { error("empty host") }
            serverPort = if (tcpPorts.size == 1 && tcpPorts[0].toIntOrNull() != null) {
                tcpPorts[0].toInt()
            } else 0
            portRange = if (tcpPorts.size != 1 || tcpPorts[0].toIntOrNull() == null) {
                tcpPorts.joinToString("\n")
            } else ""
            username = url.username
            password = url.password
            name = profileName
            multiplexingLevel = multiplexing
            handshakeMode = handshakemode
            trafficPattern = trafficpattern
            protocol = MieruBean.PROTOCOL_TCP
        })
    }
    if (udpPorts.isNotEmpty()) {
        beans.add(MieruBean().apply {
            serverAddress = url.host.ifEmpty { error("empty host") }
            serverPort = if (udpPorts.size == 1 && udpPorts[0].toIntOrNull() != null) {
                udpPorts[0].toInt()
            } else 0
            portRange = if (udpPorts.size != 1 || udpPorts[0].toIntOrNull() == null) {
                udpPorts.joinToString("\n")
            } else ""
            username = url.username
            password = url.password
            name = profileName
            multiplexingLevel = multiplexing
            handshakeMode = handshakemode
            trafficPattern = trafficpattern
            protocol = MieruBean.PROTOCOL_UDP
            // mtu = url.queryParameter("mtu")?.toIntOrNull() ?: 1400
        })
    }
    return beans
}

fun MieruBean.toUri(): String? {
    val builder = Libsagernetcore.newURL("mierus").apply {
        host = serverAddress.ifEmpty { error("empty server address") }
    }
    if (username.isNotEmpty()) {
        builder.username = username
    } else {
        error("empty username")
    }
    if (password.isNotEmpty()) {
        builder.password = password
    } else {
        error("empty password")
    }
    if (name.isNotEmpty()) {
        builder.addQueryParameter("profile", name)
    }
    if (portRange.isNotEmpty()) {
        for (range in portRange.listByLineOrComma()) {
            builder.addQueryParameter("port", range)
            builder.addQueryParameter("protocol", when (protocol) {
                MieruBean.PROTOCOL_TCP -> "TCP"
                MieruBean.PROTOCOL_UDP -> "UDP"
                else -> error("impossible")
            })
        }
    } else {
        builder.addQueryParameter("port", serverPort.toString())
        builder.addQueryParameter("protocol", when (protocol) {
            MieruBean.PROTOCOL_TCP -> "TCP"
            MieruBean.PROTOCOL_UDP -> "UDP"
            else -> error("impossible")
        })
    }
    /*if (protocol == PROTOCOL_UDP && mtu > 0) {
        builder.addQueryParameter("mtu", mtu.toString())
    }*/
    when (multiplexingLevel) {
        MieruBean.MULTIPLEXING_OFF -> {
            builder.addQueryParameter("multiplexing", "MULTIPLEXING_OFF")
        }
        MieruBean.MULTIPLEXING_LOW -> {
            builder.addQueryParameter("multiplexing", "MULTIPLEXING_LOW")
        }
        MieruBean.MULTIPLEXING_MIDDLE -> {
            builder.addQueryParameter("multiplexing", "MULTIPLEXING_MIDDLE")
        }
        MieruBean.MULTIPLEXING_HIGH -> {
            builder.addQueryParameter("multiplexing", "MULTIPLEXING_HIGH")
        }
    }
    when (handshakeMode) {
        MieruBean.HANDSHAKE_STANDARD -> {
            builder.addQueryParameter("handshake-mode", "HANDSHAKE_STANDARD")
        }
        MieruBean.HANDSHAKE_NO_WAIT -> {
            builder.addQueryParameter("handshake-mode", "HANDSHAKE_NO_WAIT")
        }
    }
    if (trafficPattern.isNotEmpty()) {
        builder.addQueryParameter("traffic-pattern", trafficPattern)
    }
    return builder.string
}
