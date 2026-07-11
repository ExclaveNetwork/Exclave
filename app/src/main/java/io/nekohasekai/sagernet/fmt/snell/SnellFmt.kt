/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Snell support for Exclave                              *
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

package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.*
import libexclavecore.Libexclavecore

fun parseSnell(url: String): SnellBean {
    val link = Libexclavecore.parseURL(url)
    return SnellBean().apply {
        name = link.fragment
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port
        psk = when {
            link.username.isNotEmpty() -> link.username
            link.password.isNotEmpty() -> link.password
            else -> link.queryParameter("psk") ?: ""
        }
        link.queryParameter("version")?.toIntOrNull()?.also {
            if (it != 4 && it != 6) error("snell version must be 4 or 6")
            version = it
        }
        // accept only exact values; map legacy "off" only from share links that used it historically
        (link.queryParameter("obfsMode") ?: link.queryParameter("obfs"))?.also {
            obfsMode = if (it == "off") SnellBean.OBFS_NONE else it
        }
        link.queryParameter("obfs-host")?.also { obfsHost = it }
        link.queryParameter("mode")?.also { mode = it }
        link.queryParameter("user-psk")?.also { userKey = it }
        link.queryParameter("userPSK")?.also { userKey = it }
        link.queryParameter("reuse")?.also {
            reuse = it == "1" || it.equals("true", ignoreCase = true)
        }
        initializeDefaultValues()
    }
}

fun SnellBean.toUri(): String? {
    val builder = Libexclavecore.newURL("snell").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        if (!psk.isNullOrEmpty()) {
            username = psk
        }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    val v = version ?: 4
    builder.addQueryParameter("version", v.toString())
    if (!obfsMode.isNullOrEmpty() && obfsMode != SnellBean.OBFS_NONE) {
        builder.addQueryParameter("obfsMode", obfsMode)
    }
    if (!obfsHost.isNullOrEmpty()) {
        builder.addQueryParameter("obfs-host", obfsHost)
    }
    if (v >= 6 && !mode.isNullOrEmpty() && mode != SnellBean.MODE_DEFAULT) {
        builder.addQueryParameter("mode", mode)
    }
    if (!userKey.isNullOrEmpty()) {
        builder.addQueryParameter("user-psk", userKey)
    }
    if (reuse == true) {
        builder.addQueryParameter("reuse", "1")
    }
    return builder.string
}
