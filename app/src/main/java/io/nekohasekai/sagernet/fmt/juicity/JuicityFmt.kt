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

package io.nekohasekai.sagernet.fmt.juicity

import io.nekohasekai.sagernet.ktx.*
import libexclavecore.Libexclavecore
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

fun parseJuicity(url: String): JuicityBean {
    val link = Libexclavecore.parseURL(url)
    return JuicityBean().apply {
        name = link.fragment
        serverAddress = link.host
        serverPort = when {
            !link.hasPort() -> error("invalid port")
            else -> link.port
        }
        uuid = parseUUID(link.username)?.toHexDashString() ?: error("invalid uuid")
        password = link.password
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("allow_insecure")?.takeIf { it == "1" }?.also {
            allowInsecure = true
        }
        link.queryParameter("pinned_certchain_sha256")?.also {
            val hash = try {
                if (it.length == 64) {
                    it.hexToByteArray()
                } else if (it.contains("-") || it.contains("_")) {
                    Base64.UrlSafe.decode(it)
                } else {
                    Base64.decode(it)
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid pinned_certchain_sha256")
            }
            require(hash.size == 32) { "invalid pinned_certchain_sha256" }
            pinnedPeerCertificateChainSha256 = Base64.UrlSafe.encode(hash)
            // match Juicity's behavior
            // https://github.com/juicity/juicity/blob/412dbe43e091788c5464eb2d6e9c169bdf39f19c/cmd/client/run.go#L97
            allowInsecure = true
        }
    }
}

fun JuicityBean.toUri(): String? {
    val builder = Libexclavecore.newURL("juicity").apply {
        setHostPort(serverAddress, serverPort)
        require(Uuid.parseHexDashOrNull(uuid) != null) { "invalid uuid" }
        username = uuid
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }
    builder.addQueryParameter("congestion_control", "bbr")
    if (sni.isNotEmpty()) {
        builder.addQueryParameter("sni", sni)
    }
    val certChainHash = pinnedPeerCertificateChainSha256.listByLineOrComma().firstOrNull()
    if (certChainHash != null) {
        // https://github.com/juicity/juicity/blob/412dbe43e091788c5464eb2d6e9c169bdf39f19c/cmd/client/run.go#L87-L96
        // it actually supports Base64 URL-safe encoding with padding, Base64 standard encoding with padding and Hex encoding
        val hash = try {
            if (certChainHash.length == 64) {
                certChainHash.hexToByteArray()
            } else if (certChainHash.contains("-") || certChainHash.contains("_")) {
                Base64.UrlSafe.decode(certChainHash)
            } else {
                Base64.decode(certChainHash)
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid pinned_certchain_sha256")
        }
        require(hash.size == 32) { "invalid pinned_certchain_sha256" }
        builder.addQueryParameter("pinned_certchain_sha256", Base64.UrlSafe.encode(hash))
    }
    // as `pinnedPeerCertificate(PublicKey)Sha256` is not exportable,
    // only add `allow_insecure=1` if `pinnedPeerCertificate(PublicKey)Sha256` is not used
    if (certChainHash != null ||
        (allowInsecure && pinnedPeerCertificateSha256.isEmpty() &&
                pinnedPeerCertificatePublicKeySha256.isEmpty() && serverNameToVerify.listByLineOrComma().isEmpty())
        ) {
        builder.addQueryParameter("allow_insecure", "1")
    }
    return builder.string
}
