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

package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore

fun parseHttp(link: String): HttpBean {
    val url = Libsagernetcore.parseURL(link)
    if (url.path != "/" && url.path != "") error("Not http proxy")

    return HttpBean().apply {
        serverAddress = url.host.ifEmpty { error("empty host") }
        serverPort = url.port.takeIf { it > 0 } ?: if (url.scheme == "https") 443 else 80
        username = url.username
        password = url.password
        name = url.fragment
        if (url.scheme == "https") {
            // non-standard
            security = "tls"
            url.queryParameter("sni")?.let {
                sni = it
            }
        }
    }
}

fun HttpBean.toUri(): String? {
    if (security != "tls" && security != "none") error("unsupported http with reality")
    if (type != "tcp" || headerType != "none") error("unsupported http with v2ray transport")

    val builder = Libsagernetcore.newURL(if (security == "tls") "https" else "http").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    if (username.isNotEmpty()) {
        builder.username = username
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }

    if (security == "tls" && sni.isNotEmpty()) {
        // non-standard
        builder.addQueryParameter("sni", sni)
    }

    return builder.string
}