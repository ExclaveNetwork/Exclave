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
import libexclavecore.Libexclavecore
import kotlin.io.encoding.Base64

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

    val link = Libexclavecore.parseURL(url)
    return Hysteria2Bean().apply {
        name = link.fragment
        serverAddress = link.host
        serverPorts = when {
            port.isNotEmpty() -> if (port.isValidHysteriaPort()) port else error("invalid port")
            !link.hasPort() -> "443"
            else -> link.port.toString()
        }
        link.queryParameter("mport")?.takeIf { it.isValidHysteriaMultiPort() }?.also {
            serverPorts = it
        }
        // Warning: Do not use colon in username or password in so-called `userpass` authentication.
        // Official Hysteria2 server can not handle it correctly.
        // need to handle so-called broken "userpass" authentication
        auth = if (link.hasPassword()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.takeIf { it == "1" }?.also {
            allowInsecure = true
        }
        link.queryParameter("pinSHA256")?.takeIf { it.isNotEmpty() }?.also {
            // https://github.com/apernet/hysteria/blob/922128e425a700c5bc01290e7a9560f182fe451b/app/cmd/client.go#L882-L889
            val pinSHA256 = it.replace(":", "").replace("-", "").lowercase()
            require(pinSHA256.hexToByteArray().size == 32) { "invalid pinSHA256" }
            pinnedPeerCertificateSha256 = pinSHA256
        }
        link.queryParameter("obfs")?.also {
            when (it) {
                "" -> {}
                "salamander", "gecko"-> {
                    obfsType = it
                    link.queryParameter("obfs-password")?.also { password ->
                        require(password.toByteArray().size >= 4) { "invalid obfs-password" }
                        obfsPassword = password
                    }
                }
                else -> error("unsupported obfs")
            }
        }
        link.queryParameter("ech")?.takeIf { it.isNotEmpty() }?.also {
            // TODO: validate echConfig
            try {
                Base64.decode(it)
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid ech")
            }
            echEnabled = true
            echConfigList = it
            echQueryName = ""
        }
    }
}

fun Hysteria2Bean.toUri(): String? {
    require(serverPorts.isValidHysteriaPort()) { "invalid port" }

    val builder = Libexclavecore.newURL("hysteria2").apply {
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
        pinnedPeerCertificateChainSha256.isEmpty() && pinnedPeerCertificatePublicKeySha256.isEmpty()
        && serverNameToVerify.listByLineOrComma().isEmpty()) {
        builder.addQueryParameter("insecure", "1")
    }
    pinnedPeerCertificateSha256.listByLineOrComma().firstOrNull()?.let {
        val pinSHA256 = it.replace(":", "").replace("-", "").lowercase()
        try {
            require(pinSHA256.hexToByteArray().size == 32)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid pinSHA256")
        }
        builder.addQueryParameter("pinSHA256", pinSHA256)
    }
    if (obfsType.isNotEmpty()) {
        builder.addQueryParameter("obfs", obfsType)
        require(obfsPassword.toByteArray().size >= 4) { "invalid obfs password" }
        builder.addQueryParameter("obfs-password", obfsPassword)
    }
    if (echEnabled && echConfigList.isNotEmpty()) {
        try {
            // TODO: validate echConfig
            Base64.decode(echConfigList)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid ech")
        }
        builder.addQueryParameter("ech", echConfigList)
    }
    if (name.isNotEmpty()) {
        builder.fragment = name
    }
    builder.rawPath = "/"

    return builder.string
}
