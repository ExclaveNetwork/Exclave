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

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.anytls.parseAnyTLS
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.http3.parseHttp3
import io.nekohasekai.sagernet.fmt.hysteria2.parseHysteria2
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseBackup
import io.nekohasekai.sagernet.fmt.shadowquic.parseShadowQUIC
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.ssh.parseSSH
import io.nekohasekai.sagernet.fmt.trusttunnel.parseTrustTunnel
import io.nekohasekai.sagernet.fmt.tuic5.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuard
import kotlin.io.encoding.Base64

fun String.decodeBase64(): String {
    if (this.lines().size > 1) {
        return String(Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(this))
    }
    if (this.contains("-") || this.contains("_")) {
        return String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(this))
    }
    if (this.contains("+") || this.contains("/")) {
        return String(Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(this))
    }
    return String(Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(this))
}

fun parseShareLinks(text: String): List<AbstractBean> {
    val shadowQUICEnabled = DataStore.experimentalFlagsProperties.getBooleanProperty("shadowquic")
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("socks://", ignoreCase = true)
            || startsWith("socks4://", ignoreCase = true)
            || startsWith("socks4a://", ignoreCase = true)
            || startsWith("socks5://", ignoreCase = true)
            || startsWith("socks5h://", ignoreCase = true)
            || startsWith("socks+tls://", ignoreCase = true)) {
            runCatching {
                entities.add(parseSOCKS(this))
            }
        } else if (startsWith("http://", ignoreCase = true)
            || startsWith("https://", ignoreCase = true)) {
            runCatching {
                entities.add(parseHttp(this))
            }
        } else if (startsWith("vmess://", ignoreCase = true)
            || startsWith("vless://", ignoreCase = true)
            || startsWith("trojan://", ignoreCase = true)) {
            runCatching {
                entities.add(parseV2Ray(this))
            }
        } else if (startsWith("ss://", ignoreCase = true)) {
            runCatching {
                entities.add(parseShadowsocks(this))
            }
        } else if (startsWith("ssr://", ignoreCase = true)) {
            runCatching {
                entities.add(parseShadowsocksR(this))
            }
        } else if (startsWith("naive+https", ignoreCase = true)
            || startsWith("naive+quic", ignoreCase = true)) {
            runCatching {
                entities.add(parseNaive(this))
            }
        } else if (startsWith("hysteria2://", ignoreCase = true)
            || startsWith("hy2://", ignoreCase = true)) {
            runCatching {
                entities.add(parseHysteria2(this))
            }
        } else if (startsWith("juicity://", ignoreCase = true)) {
            runCatching {
                entities.add(parseJuicity(this))
            }
        } else if (startsWith("tuic://", ignoreCase = true)) {
            runCatching {
                entities.add(parseTuic(this))
            }
        } else if (startsWith("wireguard://", ignoreCase = true) || startsWith("wg://", ignoreCase = true)) {
            runCatching {
                entities.add(parseWireGuard(this))
            }
        } else if (startsWith("mierus://", ignoreCase = true)) {
            runCatching {
                entities.addAll(parseMieru(this))
            }
        } else if (startsWith("quic://", ignoreCase = true)) {
            runCatching {
                entities.add(parseHttp3(this))
            }
        } else if (startsWith("anytls://", ignoreCase = true)) {
            runCatching {
                entities.add(parseAnyTLS(this))
            }
        } else if (startsWith("ssh://", ignoreCase = true)) {
            runCatching {
                entities.add(parseSSH(this))
            }
        } else if (startsWith("tt://", ignoreCase = true)) {
            runCatching {
                entities.addAll(parseTrustTunnel(this))
            }
        } else if (startsWith("sq://", ignoreCase = true) || startsWith("shadowquic://", ignoreCase = true)
            && shadowQUICEnabled) {
            runCatching {
                entities.add(parseShadowQUIC(this))
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }

    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

fun parseBackupLines(text: String): List<AbstractBean> {
    val lines = text.split('\n')
    val entities = ArrayList<AbstractBean>()
    for (line in lines) {
        try {
            entities.add(parseBackup(line))
        } catch (_: Exception) {
            return listOf()
        }
    }
    return entities
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
