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

package io.nekohasekai.sagernet.fmt.anytls

import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore

fun parseAnyTLS(url: String): AnyTLSBean {
    val link = Libsagernetcore.parseURL(url)
    return AnyTLSBean().apply {
        name = link.fragment
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port.takeIf { it > 0 } ?: 443
        password = link.username
        security = "tls"
        link.queryParameterNotBlank("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.takeIf { it == "1" || it == "true" }?.also {
            allowInsecure = true
        }
        link.queryParameter("allow_insecure")?.takeIf { it == "1" || it == "true" }?.also {
            allowInsecure = true
        }
        link.queryParameter("allowInsecure")?.takeIf { it == "1" || it == "true" }?.also {
            allowInsecure = true
        }
    }
}

fun AnyTLSBean.toUri(): String? {
    if (security != "tls") {
        error("anytls must use tls")
    }
    val builder = Libsagernetcore.newURL("anytls")
    builder.setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
    if (password.isNotEmpty()) {
        builder.username = password
    }
    builder.rawPath = "/"
    if (sni.isNotEmpty()) {
        builder.addQueryParameter("sni", sni)
    }
    // as pinned certificate is not exportable, only add `insecure=1` if pinned certificate is not used
    if (pinnedPeerCertificateChainSha256.isEmpty() && pinnedPeerCertificatePublicKeySha256.isEmpty() &&
        pinnedPeerCertificateSha256.isEmpty() && allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (name.isNotEmpty()) {
        builder.fragment = name
    }
    return builder.string
}
