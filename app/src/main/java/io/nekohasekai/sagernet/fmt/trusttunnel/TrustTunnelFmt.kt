/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.trusttunnel

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.joinHostPort
import libsagernetcore.Libsagernetcore
import kotlin.io.encoding.Base64

// https://github.com/TrustTunnel/TrustTunnel/blob/8856e7ba83ae0c9faace78aaf9a95b1b291cd3ed/DEEP_LINK.md
// https://github.com/TrustTunnel/TrustTunnel/blob/8ba8f34da84b54ff49c248ec8c940c38010b511e/DEEP_LINK.md
// https://github.com/TrustTunnel/TrustTunnel/blob/9e144fe4bcd0c2cf8f6690a97ba9b4df571a8ec0/DEEP_LINK.md

private enum class Tag(val code: Long) {
    Version(0x00),
    Hostname(0x01),
    Addresses(0x02),
    CustomSNI(0x03),
    HasIPv6(0x04),
    Username(0x05),
    Password(0x06),
    SkipVerification(0x07),
    Certificate(0x08),
    UpstreamProtocol(0x09),
    AntiDPI(0x0A),
    ClientRandomPrefix(0x0B),
    Name(0x0C),
    DNSUptreams(0x0D),
}

private enum class Version(val code: Byte) {
    Version0(0x00),
    Version1(0x01),
}

private enum class HasIPv6(val code: Byte) {
    False(0x00),
    True(0x01),
}

private enum class SkipVerification(val code: Byte) {
    False(0x00),
    True(0x01),
}

private enum class UpstreamProtocol(val code: Byte) {
    HTTP2(0x01),
    HTTP3(0x02),
}

private enum class AntiDPI(val code: Byte) {
    False(0x00),
    True(0x01),
}

@Suppress("ArrayInDataClass")
private data class TLV(
    val tag: Long,
    val value: ByteArray,
)

fun TrustTunnelBean.toUri(): String {
    require(serverAddress.isNotEmpty())
    require(username.isNotEmpty())
    require(password.isNotEmpty())
    require(serverPort in 0..65535)
    require(protocol == "https" || protocol == "quic")
    val byteArrayBuilder = ArrayList<Byte>().apply {
        writeTLV(Tag.Addresses.code, joinHostPort(serverAddress, serverPort).toByteArray())
        if (serverNameToVerify.isNotEmpty()) {
            writeTLV(Tag.Hostname.code, serverNameToVerify.toByteArray())
            writeTLV(Tag.CustomSNI.code, sni.ifEmpty { serverAddress }.toByteArray())
        } else {
            writeTLV(Tag.Hostname.code, sni.ifEmpty { serverAddress }.toByteArray())
        }
        writeTLV(Tag.Username.code, username.toByteArray())
        writeTLV(Tag.Password.code, password.toByteArray())
        if (allowInsecure) {
            writeTLV(Tag.SkipVerification.code, byteArrayOf(SkipVerification.True.code))
        }
        when (protocol) {
            "https" -> writeTLV(Tag.UpstreamProtocol.code, byteArrayOf(UpstreamProtocol.HTTP2.code))
            "quic" -> writeTLV(Tag.UpstreamProtocol.code, byteArrayOf(UpstreamProtocol.HTTP3.code))
        }
        if (certificate.isNotEmpty()) {
            val der = Libsagernetcore.pemToDer(certificate)
            require(der.isNotEmpty())
            writeTLV(Tag.Certificate.code, der)
        }
        if (name.isNotEmpty()) {
            writeTLV(Tag.Name.code, name.toByteArray())
        }
        writeTLV(Tag.Version.code, byteArrayOf(Version.Version1.code))
    }
    return "tt://?" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayBuilder.toByteArray())
}

fun parseTrustTunnel(url: String): List<TrustTunnelBean> {
    try {
        val data = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(
            if (url.startsWith("tt://?")) url.substring("tt://?".length) else url.substring("tt://".length)
        )
        val bean = TrustTunnelBean()
        val addresses = mutableListOf<String>()
        var hostname = ""
        var customSNI = ""
        var offset = 0
        while (offset < data.size) {
            val tlv = data.readTLV(offset)
            val tag = tlv.tag
            val length = tlv.value.size
            val value = tlv.value
            offset += when {
                tag < 64 -> 1
                tag < 16384 -> 2
                tag < 1073741824 -> 4
                else -> 8
            } + when {
                length < 64 -> 1
                length < 16384 -> 2
                length < 1073741824 -> 4
                else -> 8
            } + length
            when (tlv.tag) {
                Tag.Version.code -> {
                    // ignored
                }
                Tag.Hostname.code -> {
                    hostname = String(value)
                }
                Tag.Addresses.code -> {
                    addresses.add(String(value))
                }
                Tag.CustomSNI.code -> {
                    customSNI = String(value)
                }
                Tag.HasIPv6.code -> {
                    // ignored
                }
                Tag.Username.code -> {
                    bean.username = String(value)
                }
                Tag.Password.code -> {
                    bean.password = String(value)
                }
                Tag.SkipVerification.code -> {
                    if (value.isNotEmpty()) {
                        bean.allowInsecure = value[0] != SkipVerification.False.code
                    }
                }
                Tag.Certificate.code -> {
                    if (value.isNotEmpty()) {
                        val pem = Libsagernetcore.derToPem(value)
                        require(pem.isNotEmpty())
                        bean.certificate = pem
                    }
                }
                Tag.UpstreamProtocol.code -> {
                    if (value.isNotEmpty()) {
                        when (value[0]) {
                            UpstreamProtocol.HTTP2.code -> bean.protocol = "https"
                            UpstreamProtocol.HTTP3.code -> bean.protocol = "quic"
                            else -> error("invalid")
                        }
                    }
                }
                Tag.AntiDPI.code -> {
                    // ignored
                }
                Tag.ClientRandomPrefix.code -> {
                    // ignored
                }
                Tag.Name.code -> {
                    bean.name = String(value)
                }
                Tag.DNSUptreams.code -> {
                    // ignored
                }
                else -> {
                    // "A parser MUST ignore unknown tags to allow forward-compatible extensions."
                }
            }
        }
        if (customSNI.isNotEmpty()) {
            bean.sni = customSNI
            bean.serverNameToVerify = hostname
        } else {
            bean.sni = hostname
        }
        val beans = mutableListOf<TrustTunnelBean>()
        addresses.forEach {
            if (Libsagernetcore.isIP(it)) {
                beans.add(bean.applyDefaultValues().clone().apply {
                    serverAddress = it
                    serverPort = 443
                })
                return@forEach
            }
            require(it.contains(":"))
            val port = it.substringAfterLast(":").toIntOrNull()
            require(port != null && port in 0..65535)
            var host = it.substringBeforeLast(":")
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substringAfter("[").substringBeforeLast("]")
                require(Libsagernetcore.isIPv6(host))
            }
            beans.add(bean.applyDefaultValues().clone().apply {
                serverAddress = host
                serverPort = port
            })
        }
        return beans
    } catch (e: Exception) {
        throw e
    }
}

private fun ArrayList<Byte>.writeTLV(tag: Long, value: ByteArray) {
    writeUVarInt(tag)
    writeUVarInt(value.size.toLong())
    addAll(value.toList())
}

private fun ArrayList<Byte>.writeUVarInt(i: Long) {
    require(i < 4611686018427387904)
    when {
        i < 64 -> {
            add((i and 0x3F).toByte())
        }
        i < 16384 -> {
            val encoded = i or (0x1L shl 14)
            add(((encoded shr 8) and 0xFF).toByte())
            add((encoded and 0xFF).toByte())
        }
        i < 1073741824 -> {
            val encoded = i or (0x2L shl 30)
            add(((encoded shr 24) and 0xFF).toByte())
            add(((encoded shr 16) and 0xFF).toByte())
            add(((encoded shr 8) and 0xFF).toByte())
            add((encoded and 0xFF).toByte())
        }
        else -> {
            val encoded = i or (0x3L shl 62)
            add(((encoded shr 56) and 0xFF).toByte())
            add(((encoded shr 48) and 0xFF).toByte())
            add(((encoded shr 40) and 0xFF).toByte())
            add(((encoded shr 32) and 0xFF).toByte())
            add(((encoded shr 24) and 0xFF).toByte())
            add(((encoded shr 16) and 0xFF).toByte())
            add(((encoded shr 8) and 0xFF).toByte())
            add((encoded and 0xFF).toByte())
        }
    }
}

private fun ByteArray.readTLV(offset: Int = 0): TLV {
    try {
        var newOffset = offset
        val tag = readUVarInt(offset)
        newOffset += when {
            tag < 64 -> 1
            tag < 16384 -> 2
            tag < 1073741824 -> 4
            else -> 8
        }
        val length = readUVarInt(newOffset)
        require(length < Int.MAX_VALUE)
        newOffset += when {
            length < 64 -> 1
            length < 16384 -> 2
            length < 1073741824 -> 4
            else -> 8
        }
        val value = copyOfRange(newOffset, newOffset+length.toInt())
        return TLV(
            tag = tag,
            value = value,
        )
    } catch (e: Exception) {
        throw e
    }
}

private fun ByteArray.readUVarInt(offset: Int = 0): Long {
    try {
        val first = this[offset].toInt() and 0xFF
        val prefix = first shr 6
        require(prefix in 0x00..0x03)
        when (prefix) {
            0x00 -> {
                return (first and 0x3F).toLong()
            }
            0x01 -> {
                val b1 = this[offset + 1].toInt() and 0xFF
                return ((first and 0x3F) shl 8 or b1).toLong()
            }
            0x02 -> {
                val b1 = this[offset + 1].toInt() and 0xFF
                val b2 = this[offset + 2].toInt() and 0xFF
                val b3 = this[offset + 3].toInt() and 0xFF
                return ((first and 0x3F).toLong() shl 24) or
                        (b1.toLong() shl 16) or
                        (b2.toLong() shl 8) or
                        b3.toLong()
            }
            else -> {
                var value = (first and 0x3F).toLong() shl 56
                for (i in 1..7) {
                    value = value or ((this[offset + i].toInt() and 0xFF).toLong() shl (56 - 8 * i))
                }
                return value
            }
        }
    } catch (e: Exception) {
        throw e
    }
}
