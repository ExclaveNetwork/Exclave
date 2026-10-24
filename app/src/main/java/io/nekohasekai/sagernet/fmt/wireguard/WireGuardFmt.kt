/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.joinHostPort
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore
import com.sshtools.jini.INI
import com.sshtools.jini.INIWriter
import io.nekohasekai.sagernet.ktx.queryParameterNotBlank
import java.io.StringWriter
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

fun parseWireGuard(server: String): WireGuardBean {
    val link = Libsagernetcore.parseURL(server)
    return WireGuardBean().apply {
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port.takeIf { it > 0 } ?: 51820
        if (link.username.isNotEmpty()) {
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L126-L148
            privateKey = link.username.replace('_', '/').replace('-', '+')
            if (privateKey.length == 43) privateKey += "="
            // v2rayNG style link
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L75
            localAddress = "10.0.0.1/32\nfd59:7153:2388:b5fd:0000:0000:0000:0001/128"
        }
        (link.queryParameter("privatekey") ?: link.queryParameter("privateKey")) ?.let {
            if (it.length == 64) {
                privateKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                privateKey = it.replace('_', '/').replace('-', '+')
                if (privateKey.length == 43) privateKey += "="
            }
        }
        (link.queryParameter("address") ?: link.queryParameter("ip")) ?.takeIf { it.isNotEmpty() }?.also {
            localAddress = it.split(",").joinToString("\n")
        }
        (link.queryParameter("publickey") ?: link.queryParameter("publicKey")) ?.let {
            if (it.length == 64) {
                peerPublicKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                peerPublicKey = it.replace('_', '/').replace('-', '+')
                if (peerPublicKey.length == 43) peerPublicKey += "="
            }
        }
        (link.queryParameterNotBlank("presharedkey") ?: link.queryParameterNotBlank("preSharedKey")) ?.let {
            if (peerPreSharedKey.length == 43) peerPreSharedKey += "="
            if (it.length == 64) {
                peerPreSharedKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                peerPreSharedKey = it.replace('_', '/').replace('-', '+')
                if (peerPreSharedKey.length == 43) peerPreSharedKey += "="
            }
        }
        link.queryParameter("mtu")?.toIntOrNull()?.takeIf { it > 0 }?.let {
            mtu = it
        }
        link.queryParameter("reserved")?.let {
            reserved = it
        }
        link.fragment?.let {
            name = it
        }
    }
}

fun parseWireGuardConfig(conf: String): List<WireGuardBean> {
    val beans = mutableListOf<WireGuardBean>()
    val ini = try {
        INI.fromString(conf)
    } catch (_: Exception) {
        return beans
    }
    val iface = ini.sectionOr("Interface").getOrNull() ?: return beans
    val wgBean = WireGuardBean().apply {
        localAddress = iface.getAllOr("Address").getOrNull()
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: return beans
        privateKey = iface.getOr("PrivateKey").getOrNull() ?: return beans
        mtu = iface.getOr("MTU").getOrNull()?.toInt()?.takeIf { it > 0 } ?: 1420
    }
    val peers = ini.allSectionsOr("Peer").getOrNull() ?: return beans
    for (peer in peers) {
        val endpoint = peer.getOr("Endpoint").getOrNull()
        if (endpoint.isNullOrEmpty() || !endpoint.contains(":")) {
            continue
        }
        beans.add(wgBean.applyDefaultValues().clone().apply {
            serverAddress = endpoint.substringBeforeLast(":").removePrefix("[").removeSuffix("]").ifEmpty { error("empty host") }
            serverPort = endpoint.substringAfterLast(":").toIntOrNull() ?: continue
            peerPublicKey = peer.getOr("PublicKey").getOrNull() ?: continue
            peerPreSharedKey = peer.getOr("PreSharedKey").getOrNull()
            keepaliveInterval = peer.getOr("PersistentKeepalive").getOrNull()?.toIntOrNull()?.takeIf { it > 0 }
        })
    }
    return beans
}

fun WireGuardBean.toConf(): String {
    val ini = INI.create()
    val iface = ini.create("Interface")
    iface.put("Address", localAddress.listByLineOrComma())
    if (mtu > 0) {
        iface.put("MTU", mtu)
    }
    iface.put("PrivateKey", privateKey.ifEmpty { error("empty private key") })
    val peer = ini.create("Peer")
    peer.put("Endpoint", joinHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort))
    peer.put("PublicKey", peerPublicKey.ifEmpty { error("empty peer public key") })
    if (peerPreSharedKey.isNotEmpty()) {
        peer.put("PreSharedKey", peerPreSharedKey)
    }
    if (keepaliveInterval > 0) {
        peer.put("PersistentKeepalive", keepaliveInterval)
    }
    val conf = StringWriter()
    INIWriter.Builder()
        .withIndent(0)
        .withStringQuoteMode(INIWriter.StringQuoteMode.NEVER)
        .build()
        .write(ini, conf)
    return conf.toString()
}
