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

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.anytls.parseAnyTLS
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.http3.parseHttp3
import io.nekohasekai.sagernet.fmt.hysteria2.parseHysteria2
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseBackupLink
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.ssh.parseSSH
import io.nekohasekai.sagernet.fmt.trusttunnel.parseTrustTunnel
import io.nekohasekai.sagernet.fmt.tuic5.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuard
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.io.encoding.Base64
import kotlin.io.use

private val importMarkdownLinkRegex = Regex("""\[([^\]\r\n]+)]\(([^)\s]+)\)""")
private val importShareConfigRegex = Regex(
    """(?i)(?:exclave://|socks(?:4a?|5h?)?://|vmess://|vless://|trojan://|ss://|ssr://|naive\+(?:https|quic)\S*|hysteria2://|hy2://|juicity://|tuic://|wireguard://|wg://|mierus://|quic://|anytls://|ssh://|tt://)\S+"""
)
private val importHttpLinkRegex = Regex("""(?i)https?://[^\s\])>"']+""")

private fun String.startsWithImportScheme(): Boolean {
    return startsWith("exclave://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("socks://", ignoreCase = true) ||
            startsWith("socks4://", ignoreCase = true) ||
            startsWith("socks4a://", ignoreCase = true) ||
            startsWith("socks5://", ignoreCase = true) ||
            startsWith("socks5h://", ignoreCase = true) ||
            startsWith("vmess://", ignoreCase = true) ||
            startsWith("vless://", ignoreCase = true) ||
            startsWith("trojan://", ignoreCase = true) ||
            startsWith("ss://", ignoreCase = true) ||
            startsWith("ssr://", ignoreCase = true) ||
            startsWith("naive+https", ignoreCase = true) ||
            startsWith("naive+quic", ignoreCase = true) ||
            startsWith("hysteria2://", ignoreCase = true) ||
            startsWith("hy2://", ignoreCase = true) ||
            startsWith("juicity://", ignoreCase = true) ||
            startsWith("tuic://", ignoreCase = true) ||
            startsWith("wireguard://", ignoreCase = true) ||
            startsWith("wg://", ignoreCase = true) ||
            startsWith("mierus://", ignoreCase = true) ||
            startsWith("quic://", ignoreCase = true) ||
            startsWith("anytls://", ignoreCase = true) ||
            startsWith("ssh://", ignoreCase = true) ||
            startsWith("tt://", ignoreCase = true)
}

private fun markdownImportReplacement(label: String, url: String): String {
    val cleanLabel = label.trim()
    val cleanUrl = url.trim()
    if (cleanLabel.startsWithImportScheme()) return cleanLabel

    val urlHost = cleanUrl
        .substringAfter("://", "")
        .substringBefore('/')
        .substringBefore(':')
        .trim()
    if (urlHost.isNotEmpty() && cleanLabel.equals(urlHost, ignoreCase = true)) {
        return cleanLabel
    }

    return cleanUrl
}

fun normalizeImportText(text: String): String {
    return importMarkdownLinkRegex.replace(text) {
        markdownImportReplacement(it.groupValues[1], it.groupValues[2])
    }
}

fun extractSubscriptionLinksForImport(text: String): List<String> {
    val normalized = normalizeImportText(text)
    val withoutConfigs = importShareConfigRegex.replace(normalized) {
        " ".repeat(it.value.length)
    }
    return importHttpLinkRegex.findAll(withoutConfigs)
        .map { it.value.trim().trimEnd('.', ',', ';', ':') }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

fun isJsonImportDocument(text: String): Boolean {
    val normalized = normalizeImportText(text).trim()
    if (!normalized.startsWith("{") && !normalized.startsWith("[")) return false
    return runCatching {
        parseJson(normalized, lenient = true)
        true
    }.getOrElse {
        false
    }
}

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
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("exclave://", ignoreCase = true)) {
            runCatching {
                entities.add(parseBackupLink(this))
            }
        } else if (startsWith("socks://", ignoreCase = true)
            || startsWith("socks4://", ignoreCase = true)
            || startsWith("socks4a://", ignoreCase = true)
            || startsWith("socks5://", ignoreCase = true)
            || startsWith("socks5h://", ignoreCase = true)) {
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

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}

fun ByteArray.zlibCompress(level: Int): ByteArray {
    // Compress the bytes
    // 1 to 4 bytes/char for UTF-8
    val output = ByteArray(size * 4)
    val compressor = Deflater(level).apply {
        setInput(this@zlibCompress)
        finish()
    }
    val compressedDataLength: Int = compressor.deflate(output)
    compressor.end()
    return output.copyOfRange(0, compressedDataLength)
}

fun ByteArray.zlibDecompress(): ByteArray {
    val inflater = Inflater()
    val outputStream = ByteArrayOutputStream()

    return outputStream.use {
        val buffer = ByteArray(1024)

        inflater.setInput(this)

        var count = -1
        while (count != 0) {
            count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }

        inflater.end()
        outputStream.toByteArray()
    }
}
