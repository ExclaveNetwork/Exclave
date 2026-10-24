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

package io.nekohasekai.sagernet.fmt.socks

import io.nekohasekai.sagernet.ktx.decodeBase64
import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore

fun parseSOCKS(link: String): SOCKSBean {
    val url = Libsagernetcore.parseURL(link)
    if (url.scheme == "socks" && url.port == 0 && url.username.isEmpty() && url.password.isEmpty()) {
        // old v2rayNG format
        // This format is broken if username and/or password contains ":".
        val plainUri = link.substring("socks://".length).substringBefore("#").decodeBase64()
        return SOCKSBean().apply {
            protocol = SOCKSBean.PROTOCOL_SOCKS5
            serverAddress = plainUri.substringAfterLast("@").substringBeforeLast(":").removePrefix("[").removeSuffix("]").ifEmpty { error("empty host") }
            serverPort = plainUri.substringAfterLast("@").substringAfterLast(":").toIntOrNull()
            username = plainUri.substringBeforeLast("@").substringBefore(":")
            password = plainUri.substringBeforeLast("@").substringAfter(":")
            name = url.fragment
        }
    }
    if (url.scheme == "socks" && url.password.isEmpty() && url.username.decodeBase64().contains(":")) {
        // new v2rayNG format
        // This format is broken if username and/or password contains ":".
        return SOCKSBean().apply {
            protocol = SOCKSBean.PROTOCOL_SOCKS5
            serverAddress = url.host.ifEmpty { error("empty host") }
            serverPort = url.port
            username = url.username.decodeBase64().substringBefore(":")
            password = url.username.decodeBase64().substringAfter(":")
            name = url.fragment
        }
    }
    return SOCKSBean().apply {
        protocol = when (url.scheme) {
            "socks4" -> SOCKSBean.PROTOCOL_SOCKS4
            "socks4a" -> SOCKSBean.PROTOCOL_SOCKS4A
            "socks5", "socks5h" /* blame cURL for this */, "socks" -> SOCKSBean.PROTOCOL_SOCKS5
            "socks+tls" -> SOCKSBean.PROTOCOL_SOCKS5 // Who TF invent this?
            else -> error("impossible")
        }
        serverAddress = url.host.ifEmpty { error("empty host") }
        serverPort = url.port.takeIf { it > 0 } ?: 1080
        username = url.username
        password = url.password
        name = url.fragment
        url.queryParameter("tls")?.takeIf { it == "true" || it == "1" }?.let {
            // non-standard
            security = "tls"
            url.queryParameter("sni")?.let {
                sni = it
            }
        }
        if (url.scheme == "socks+tls") {
            // non-standard
            security = "tls"
            url.queryParameter("sni")?.let {
                sni = it
            }
        }
    }
}

fun SOCKSBean.toUri(): String? {
    if (security != "tls" && security != "none") error("unsupported socks with tls")
    if (type != "tcp" || headerType != "none") error("unsupported socks with v2ray transport")
    if (protocol == SOCKSBean.PROTOCOL_SOCKS5 && username.isEmpty() && password.isNotEmpty()) {
        error("SOCKS5 Username/Password Authentication with empty username violates RFC 1929")
    }
    if (protocol == SOCKSBean.PROTOCOL_SOCKS5 && username.isNotEmpty() && password.isEmpty()) {
        error("SOCKS5 Username/Password Authentication with empty password violates RFC 1929")
    }
    if (protocol == SOCKSBean.PROTOCOL_SOCKS5 && username.length > 255) {
        error("username too long")
    }
    if (protocol == SOCKSBean.PROTOCOL_SOCKS5 && password.length > 255) {
        error("password too long")
    }
    if (protocol == SOCKSBean.PROTOCOL_SOCKS4 || protocol == SOCKSBean.PROTOCOL_SOCKS4A && password.isNotEmpty()) {
        error("SOCKS4 and SOCKS4A do not have password field")
    }
    val builder = Libsagernetcore.newURL("socks${protocolVersion()}").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        if (name.isNotEmpty()) fragment = name
    }
    if (username.isNotEmpty()) {
        builder.username = username
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }

    if (security == "tls") {
        // non-standard
        builder.addQueryParameter("tls", "true") // non-standard
        if (sni.isNotEmpty()) {
            builder.addQueryParameter("sni", sni) // non-standard
        }
    }

    return builder.string
}