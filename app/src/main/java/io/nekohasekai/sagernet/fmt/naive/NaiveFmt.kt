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

package io.nekohasekai.sagernet.fmt.naive

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore

fun parseNaive(link: String): NaiveBean {
    // This format may be https://github.com/klzgrad/naiveproxy/issues/86.
    val url = Libsagernetcore.parseURL(link)
    return NaiveBean().apply {
        proto = when (url.scheme) {
            "naive+https" -> "https"
            "naive+quic" -> "quic"
            else -> error("impossible")
        }
        serverAddress = url.host.ifEmpty { error("empty host") }
        serverPort = url.port.takeIf { it > 0 } ?: 443
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        extraHeaders = url.queryParameter("extra-headers")?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = Libsagernetcore.newURL(if (proxyOnly) proto else "naive+$proto")
    val host = if (sni.isNotEmpty() && proxyOnly) {
        sni
    } else {
        serverAddress.ifEmpty { error("empty server address") }
    }
    val port = if (proxyOnly) {
        finalPort
    } else {
        serverPort
    }
    builder.setHostPort(host, port)
    if (username.isNotEmpty()) {
        builder.username = username
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }
    if (!proxyOnly) {
        if (extraHeaders.isNotEmpty()) {
            builder.addQueryParameter("extra-headers", extraHeaders.listByLine().joinToString("\r\n"))
        }
        if (name.isNotEmpty()) {
            builder.fragment = name
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
        if (sni.isNotEmpty()) {
            builder.addQueryParameter("sni", sni)
        }
    }
    return builder.string
}

fun NaiveBean.buildNaiveConfig(port: Int, username: String = "", password: String = ""): String {
    return GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply {
        val url = Libsagernetcore.newURL("socks").apply {
            setHostPort(LOCALHOST, port)
            if (username.isNotEmpty() && password.isNotEmpty()) {
                this.username = username
                this.password = password
            }
        }
        addProperty("listen", url.string)
        // NaïveProxy v130.0.6723.40-2 release notes:
        // Fixed a crash when the username or password contains the comma character `,`.
        // The comma is used for delimiting proxies in a proxy chain.
        // It must be percent-encoded in other URL components.
        addProperty("proxy", toUri(true).replace(",", "%2C"))
        if (extraHeaders.isNotEmpty()) {
            addProperty("extra-headers", extraHeaders.listByLine().joinToString("\r\n"))
        }
        if (sni.isNotEmpty()) {
            addProperty("host-resolver-rules","MAP $sni $finalAddress")
        } else {
            addProperty("host-resolver-rules", "MAP $serverAddress $finalAddress")
        }
        if (DataStore.logLevel != LogLevel.NONE) {
            addProperty("log", "")
        }
        if (insecureConcurrency > 0) {
            addProperty("insecure-concurrency", insecureConcurrency)
        }
        if (noPostQuantum) {
            addProperty("no-post-quantum", true)
        }
    })
}