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

package io.nekohasekai.sagernet.fmt.hysteria2

import io.nekohasekai.sagernet.ktx.isValidHysteriaMultiPort
import io.nekohasekai.sagernet.ktx.isValidHysteriaPort
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import io.nekohasekai.sagernet.ktx.queryParameterNotBlank
import libsagernetcore.Libsagernetcore

fun parseHysteria2(rawURL: String): Hysteria2Bean {
    var url = rawURL

    // fuck port hopping URL
    val hostPort = url.substringAfter("://").substringAfter("@")
        .substringBefore("#").substringBefore("?").substringBefore("/")
    var port = ""
    if (!hostPort.endsWith("]") && hostPort.lastIndexOf(":") > 0) {
        port = hostPort.substringAfterLast(":")
        if (port.isNotEmpty() && port.isValidHysteriaMultiPort()) {
            url = url.replaceFirst(":$port", ":0")
        }
    }

    val link = Libsagernetcore.parseURL(url)
    return Hysteria2Bean().apply {
        name = link.fragment
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPorts = if (port.isNotEmpty() && port.isValidHysteriaMultiPort()) {
            port
        } else if (link.port > 0) {
            link.port.toString()
        } else {
            "443"
        }
        link.queryParameter("mport")?.takeIf { it.isValidHysteriaMultiPort() }?.also {
            serverPorts = it
        }
        when {
            // Warning: Do not use colon in username or password in so-called `userpass` authentication.
            // Official Hysteria2 server can not handle it correctly.
            // need to handle so-called broken "userpass" authentication
            link.username.isEmpty() && link.password.isEmpty() -> {
                if (rawURL.substringAfter("://").substringBefore("@") == ":") {
                    auth = ":"
                }
            }
            link.username.isNotEmpty() && link.password.isEmpty() -> {
                auth = if (rawURL.substringAfter("://").substringBefore("@").endsWith(":")) {
                    link.username + ":"
                } else {
                    link.username
                }
            }
            link.username.isEmpty() && link.password.isNotEmpty() -> {
                auth = ":" + link.password
            }
            link.username.isNotEmpty() && link.password.isNotEmpty() -> {
                auth = link.username + ":" + link.password
            }
        }
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
        link.queryParameter("pinSHA256")?.also {
            // https://github.com/apernet/hysteria/blob/922128e425a700c5bc01290e7a9560f182fe451b/app/cmd/client.go#L882-L889
            pinnedPeerCertificateSha256 = it.replace(":", "").replace("-", "").lowercase()
        }
        link.queryParameter("obfs")?.also {
            if (it.isNotEmpty() && it != "salamander") {
                error("unsupported obfs")
            }
            link.queryParameter("obfs-password")?.also {
                obfs = it
            }
        }
    }
}

fun Hysteria2Bean.toUri(): String? {
    if (!serverPorts.isValidHysteriaPort()) {
        error("invalid port")
    }
    if (serverAddress.isEmpty()) {
        error("empty server address")
    }

    val builder = Libsagernetcore.newURL("hysteria2").apply {
        // fuck port hopping URL
        rawHost = if (serverAddress.contains(":")) {
            "[$serverAddress]:$serverPorts"
        } else {
            "$serverAddress:$serverPorts"
        }
        if (auth.isNotEmpty()) {
            // No need to care about so-called broken "userpass" here.
            username = auth
        }
    }

    if (sni.isNotEmpty()) {
        builder.addQueryParameter("sni", sni)
    }
    // as `pinnedPeerCertificate[Chain|PublicKey]Sha256` is not exportable,
    // only add `allow_insecure=1` if `pinnedPeerCertificate[Chain|PublicKey]Sha256` is not used
    if (allowInsecure &&
        pinnedPeerCertificateChainSha256.isEmpty() && pinnedPeerCertificatePublicKeySha256.isEmpty()) {
        builder.addQueryParameter("insecure", "1")
    }
    if (pinnedPeerCertificateSha256.isNotEmpty()) {
        builder.addQueryParameter("pinSHA256", pinnedPeerCertificateSha256.listByLineOrComma()[0].replace(":", "").lowercase())
    }
    if (obfs.isNotEmpty()) {
        // obfs password must not be empty
        builder.addQueryParameter("obfs", "salamander")
        builder.addQueryParameter("obfs-password", obfs)
    }
    if (name.isNotEmpty()) {
        builder.fragment = name
    }
    builder.rawPath = "/"

    return builder.string
}
