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

import io.nekohasekai.sagernet.BuildConfig
import libsagernetcore.Libsagernetcore
import libsagernetcore.URL
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import kotlin.random.Random

fun URL.queryParameter(key: String) = getQueryParameter(key).takeIf { it.isNotEmpty() }

fun URL.queryParameterNotBlank(key: String) = getQueryParameter(key).takeIf { it.isNotBlank() }

var URL.pathSegments: List<String>
    get() = path.split("/").filter { it.isNotEmpty() }
    set(value) {
        path = value.joinToString("/")
    }

fun URL.addPathSegments(vararg segments: String) {
    pathSegments = pathSegments.toMutableList().apply {
        addAll(segments)
    }
}

fun String.wrapIDN(): String {
    if (Libsagernetcore.isIP(this)) {
        return this
    }
    return IDN.toUnicode(this, IDN.ALLOW_UNASSIGNED)
}

fun String.unwrapIDN(): String {
    if (Libsagernetcore.isIP(this) || this.all { it.code < 128 }) {
        return this
    }
    return try {
        IDN.toASCII(this, IDN.ALLOW_UNASSIGNED)
    } catch (_: IllegalArgumentException) {
        this
    }
}

fun joinHostPort(host: String, port: Int): String {
    if (Libsagernetcore.isIPv6(host)) {
        return "[$host]:$port"
    }
    return "$host:$port"
}

fun String.unwrapHost(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapHost()
    }
    return this
}

fun isHTTPorHTTPSURL(url: String): Boolean {
    try {
        val u = Libsagernetcore.parseURL(url)
        return (u.scheme == "http" || u.scheme == "https")
    } catch (_: Exception) {
        return false
    }
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

fun String.listByLine(): List<String> {
    return this.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}

fun String.listByLineOrComma(): List<String> {
    return this.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
}

fun String.isValidHysteriaPort(disallowFromGreaterThanTo: Boolean = false): Boolean {
    if (this.toIntOrNull() != null) {
        return this.toInt() in 0..65535
    }
    val portRanges = this.split(",")
    for (portRange in portRanges) {
        if (portRange.toIntOrNull() != null) {
            if (portRange.toInt() !in 0..65535) {
                return false
            }
        } else {
            val parts = portRange.split("-")
            if (parts.size != 2) {
                return false
            }
            val from = parts[0].toIntOrNull()
            val to = parts[1].toIntOrNull()
            if (from == null || to == null || from < 0 || from > 65535 || to < 0 || to > 65535) {
                return false
            }
            if (disallowFromGreaterThanTo && from > to) {
                return false
            }
        }
    }
    return true
}

fun String.isValidHysteriaMultiPort(disallowFromGreaterThanTo: Boolean = false): Boolean {
    return this.toIntOrNull() == null && this.isValidHysteriaPort(disallowFromGreaterThanTo)
}

fun String.toHysteriaPort(disallowFromGreaterThanTo: Boolean = false): Int {
    if (this.toIntOrNull() != null) {
        if (this.toInt() in 0..65535) {
            return this.toInt()
        }
        error("invalid port range")
    }
    val portRanges = this.split(",")
    val fromList: MutableList<Int> = mutableListOf()
    val toList: MutableList<Int> = mutableListOf()
    var len = 0
    for (portRange in portRanges) {
        if (portRange.toIntOrNull() != null) {
            if (portRange.toInt() !in 0..65535) {
                error("invalid port range")
            }
            fromList.add(portRange.toInt())
            toList.add(portRange.toInt())
            len++
        } else {
            val parts = portRange.split("-")
            if (parts.size != 2) {
                error("invalid port range")
            }
            var from = parts[0].toIntOrNull()
            var to = parts[1].toIntOrNull()
            if (from == null || to == null || from < 0 || from > 65535 || to < 0 || to > 65535) {
                error("invalid port range")
            }
            if (from > to) {
                if (disallowFromGreaterThanTo) {
                    error("invalid port range")
                }
                from = to.also { to = from }
            }
            fromList.add(from)
            toList.add(to)
            len += to - from + 1
        }
    }
    val portIndex = Random.nextInt(0, len)
    var oldLen = 0
    var newLen: Int
    for (i in fromList.indices) {
        newLen = oldLen + toList[i] - fromList[i] + 1
        if (portIndex < newLen) {
            return portIndex - oldLen + fromList[i]
        }
        oldLen = newLen
    }
    error("invalid port range")
}

const val USER_AGENT = "Exclave/${BuildConfig.VERSION_NAME}"

// Taken from https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/-/blob/main/client/torrc with unsupported servers removed.
val PUBLIC_STUN_SERVERS = arrayOf(
    "stun.epygi.com:3478",
    "stun.uls.co.za:3478",
    "stun.voipgate.com:3478",
    "stun.mixvoip.com:3478"
)

fun String.unescapeLineFeed(): String {
    val chars = this.toCharArray()
    var from = 0
    var to = 0
    val length = chars.size
    while (from < length) {
        val ch = chars[from]
        if (ch == '\\' && from + 1 < length) {
            when (chars[from+1]) {
                'n' -> {
                    chars[to] = '\n'
                    to += 1
                    from += 2
                    continue
                }
                '\\' -> {
                    chars[to] = '\\'
                    to += 1
                    from += 2
                    continue
                }
            }
        }
        chars[to] = ch
        to += 1
        from += 1
    }
    return String(chars, 0, to)
}

fun Properties.getBooleanProperty(key: String): Boolean {
    return getProperty(key) == "true"
}