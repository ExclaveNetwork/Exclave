/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.http3

import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore

fun parseHttp3(link: String): Http3Bean {
    val url = Libsagernetcore.parseURL(link)
    if (url.path != "/" && url.path != "") error("Not http3 proxy")

    return Http3Bean().apply {
        serverAddress = url.host.ifEmpty { error("empty host") }
        serverPort = url.port.takeIf { it > 0 } ?: 443
        username = url.username
        password = url.password
        name = url.fragment
        url.queryParameter("sni")?.let {
            sni = it
        }
    }
}

fun Http3Bean.toUri(): String {
    val builder = Libsagernetcore.newURL("quic").apply {
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
    if (sni.isNotEmpty()) {
        // non-standard
        builder.addQueryParameter("sni", sni)
    }

    return builder.string
}