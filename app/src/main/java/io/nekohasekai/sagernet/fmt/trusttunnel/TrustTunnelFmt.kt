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
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import libexclavecore.Libexclavecore
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.security.cert.CertificateFactory
import kotlin.io.encoding.Base64

// https://github.com/TrustTunnel/TrustTunnel/blob/8856e7ba83ae0c9faace78aaf9a95b1b291cd3ed/DEEP_LINK.md
// https://github.com/TrustTunnel/TrustTunnel/blob/8ba8f34da84b54ff49c248ec8c940c38010b511e/DEEP_LINK.md
// https://github.com/TrustTunnel/TrustTunnel/blob/9e144fe4bcd0c2cf8f6690a97ba9b4df571a8ec0/DEEP_LINK.md
// https://github.com/TrustTunnel/TrustTunnel/blob/3470404b7fca8187772b57c6641a0c571e0c165f/DEEP_LINK.md

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
    SubscriptionURL(0x0E),
    ClientRandomAuthKey(0x0F),
}

private enum class Version(val code: Byte) {
    Version0(0x00),
    Version1(0x01),
    Version2(0x02),
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
    val size: Int,
)

fun TrustTunnelBean.toUri(): String {
    require(username.isNotEmpty()) { "empty username" }
    require(password.isNotEmpty()) { "empty password" }
    require(protocol == "https" || protocol == "quic") { "invalid protocol" }
    val byteArrayStream = ByteArrayOutputStream().apply {
        writeTLV(Tag.Addresses.code, joinHostPort(serverAddress, serverPort).toByteArray())
        val serverNames = serverNameToVerify.listByLineOrComma()
        require(serverNames.size <= 1) { "only one serverNameToVerify value is supported" }
        if (serverNames.size == 1) {
            require(serverNames[0].isNotEmpty()) { "serverNameToVerify contains empty value" }
            // serverNameToVerify will always verify even if allowInsecure is true
            writeTLV(Tag.Hostname.code, serverNames[0].toByteArray())
            require(!Libexclavecore.isIP(sni.ifEmpty { serverAddress })) { "IP address can't be CustomSNI" }
            writeTLV(Tag.CustomSNI.code, sni.ifEmpty { serverAddress }.toByteArray())
        } else {
            writeTLV(Tag.Hostname.code, sni.ifEmpty { serverAddress }.toByteArray())
        }
        writeTLV(Tag.Username.code, username.toByteArray())
        writeTLV(Tag.Password.code, password.toByteArray())
        if (pinnedPeerCertificateChainSha256.listByLineOrComma().isEmpty() &&
            pinnedPeerCertificatePublicKeySha256.listByLineOrComma().isEmpty() &&
            pinnedPeerCertificateSha256.listByLineOrComma().isEmpty() &&
            serverNames.isEmpty() && allowInsecure) {
            writeTLV(Tag.SkipVerification.code, byteArrayOf(SkipVerification.True.code))
        }
        when (protocol) {
            "https" -> writeTLV(Tag.UpstreamProtocol.code, byteArrayOf(UpstreamProtocol.HTTP2.code))
            "quic" -> writeTLV(Tag.UpstreamProtocol.code, byteArrayOf(UpstreamProtocol.HTTP3.code))
        }
        if (certificate.isNotEmpty()) {
            val derArray = ByteArrayOutputStream()
            val pemParser = PEMParser(StringReader(certificate))
            while (true) {
                try {
                    val obj = pemParser.readPemObject() ?: break
                    if (obj.type != "CERTIFICATE" || obj.headers.isNotEmpty()) {
                        continue
                    }
                    X509CertificateHolder(obj.content)
                    derArray.write(obj.content)
                } catch (_: Exception) {
                    continue
                }
            }
            if (derArray.size() > 0) {
                writeTLV(Tag.Certificate.code, derArray.toByteArray())
            }
        }
        if (name.isNotEmpty()) {
            writeTLV(Tag.Name.code, name.toByteArray())
        }
        writeTLV(Tag.Version.code, byteArrayOf(Version.Version2.code))
    }
    return "tt://?" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayStream.toByteArray())
}

fun parseTrustTunnel(url: String): List<TrustTunnelBean> {
    try {
        val data = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(
            if (url.startsWith("tt://?")) url.substring("tt://?".length) else url.substring("tt://".length)
        )
        val bean = TrustTunnelBean()
        val addresses = mutableListOf<String>()
        var hasHostName = false
        var hasAddresses = false
        var hasUsername = false
        var hasPassword = false
        var hostname = ""
        var customSNI = ""
        var version: Byte = 0
        var hasDNSUpstream = false
        var hasName = false
        var hasSubscriptionURL = false
        var offset = 0
        while (offset < data.size) {
            val tlv = data.readTLV(offset)
            val length = tlv.value.size
            val value = tlv.value
            offset += tlv.size
            when (tlv.tag) {
                Tag.Version.code -> {
                    require(length == 1) { "invalid Version" }
                    version = value[0]
                    require(version == Version.Version0.code || version == Version.Version1.code
                            || version == Version.Version2.code)
                }
                Tag.Hostname.code -> {
                    require(value.isNotEmpty()) { "empty Hostname" }
                    hostname = String(value)
                    hasHostName = true
                }
                Tag.Addresses.code -> {
                    addresses.add(String(value))
                    hasAddresses = true
                }
                Tag.CustomSNI.code -> {
                    require(length > 0) { "empty CustomSNI" }
                    customSNI = String(value)
                }
                Tag.HasIPv6.code -> {
                    require(length == 1) { "invalid HasIPv6" }
                    require(value[0] == HasIPv6.False.code || value[0] == HasIPv6.True.code) { "invalid HasIPv6" }
                }
                Tag.Username.code -> {
                    require(value.isNotEmpty()) { "empty Username" }
                    bean.username = String(value)
                    hasUsername = true
                }
                Tag.Password.code -> {
                    require(value.isNotEmpty()) { "empty Password" }
                    bean.password = String(value)
                    hasPassword = true
                }
                Tag.SkipVerification.code -> {
                    require(length == 1) { "invalid SkipVerification" }
                    require(value[0] == SkipVerification.False.code || value[0] == SkipVerification.True.code) { "invalid SkipVerification" }
                    bean.allowInsecure = value[0] == SkipVerification.True.code
                }
                Tag.Certificate.code -> {
                    val certs = CertificateFactory.getInstance("X.509").generateCertificates(value.inputStream())
                    val stringWriter = StringWriter()
                    val pemWriter = PemWriter(stringWriter)
                    for (cert in certs) {
                        pemWriter.writeObject(PemObject("CERTIFICATE", cert.encoded))
                    }
                    pemWriter.close()
                    bean.certificate = stringWriter.toString()
                    require(!bean.certificate.isNullOrEmpty()) { "invalid Certificate" }
                }
                Tag.UpstreamProtocol.code -> {
                    require(length == 1) { "invalid UpstreamProtocol" }
                    require(value[0] == UpstreamProtocol.HTTP2.code || value[0] == UpstreamProtocol.HTTP3.code) { "invalid UpstreamProtocol" }
                }
                Tag.AntiDPI.code -> {
                    require(length == 1) { "invalid AntiDPI" }
                    require(value[0] == AntiDPI.False.code || value[0] == AntiDPI.True.code) { "invalid AntiDPI" }
                }
                Tag.ClientRandomPrefix.code -> {
                    error("client random prefix unsupported")
                }
                Tag.Name.code -> {
                    bean.name = String(value)
                    hasName = true
                }
                Tag.DNSUptreams.code -> {
                    hasDNSUpstream = true
                    // ignored
                }
                Tag.SubscriptionURL.code -> {
                    hasSubscriptionURL = true
                    // ignored, what the fuck is this
                }
                Tag.ClientRandomAuthKey.code -> {
                    error("client random auth key unsupported")
                }
                else -> {
                    // "A parser MUST ignore unknown tags to allow forward-compatible extensions."
                }
            }
        }
        if (version < 1 && hasDNSUpstream) {
            error("version 0 can not have DNS upstream")
        }
        if (version < 1 && hasName) {
            error("version 0 can not have name")
        }
        if (version < 2 && hasSubscriptionURL) {
            error("version 0 or 1 can not have subscription url")
        }
        require(hasHostName) { "missing hostname" }
        require(hasAddresses) { "missing addresses" }
        require(hasUsername) { "missing username" }
        require(hasPassword) { "missing password" }
        if (customSNI.isNotEmpty()) {
            bean.sni = customSNI
                // Do not verify if SkipVerification is true.
            if (bean.allowInsecure != true) {
                bean.serverNameToVerify = hostname
            }
        } else {
            bean.sni = hostname
        }
        val beans = mutableListOf<TrustTunnelBean>()
        addresses.forEach {
            if (Libexclavecore.isIP(it)) {
                beans.add(bean.applyDefaultValues().clone().apply {
                    serverAddress = it
                    serverPort = 443
                })
                return@forEach
            }
            val hostPort = Libexclavecore.splitHostPort(it)
            beans.add(bean.applyDefaultValues().clone().apply {
                serverAddress = hostPort.host
                serverPort = hostPort.port
            })
        }
        return beans
    } catch (e: Exception) {
        throw e
    }
}

private fun ByteArrayOutputStream.writeTLV(tag: Long, value: ByteArray) {
    writeUVarInt(tag)
    writeUVarInt(value.size.toLong())
    write(value)
}

private fun ByteArrayOutputStream.writeUVarInt(i: Long) {
    require(i < 4611686018427387904)
    when {
        i < 64 -> {
            write(byteArrayOf((i and 0x3F).toByte()))
        }
        i < 16384 -> {
            val encoded = i or (0x1L shl 14)
            write(byteArrayOf(((encoded shr 8) and 0xFF).toByte()))
            write(byteArrayOf(((encoded and 0xFF).toByte())))
        }
        i < 1073741824 -> {
            val encoded = i or (0x2L shl 30)
            write(byteArrayOf(((encoded shr 24) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 16) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 8) and 0xFF).toByte()))
            write(byteArrayOf((encoded and 0xFF).toByte()))
        }
        else -> {
            val encoded = i or (0x3L shl 62)
            write(byteArrayOf(((encoded shr 56) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 48) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 40) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 32) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 24) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 16) and 0xFF).toByte()))
            write(byteArrayOf(((encoded shr 8) and 0xFF).toByte()))
            write(byteArrayOf((encoded and 0xFF).toByte()))
        }
    }
}

private fun ByteArray.readTLV(offset: Int = 0): TLV {
    // QUIC varints may use a non-minimal encoding, so the consumed size must be
    // derived from the 2-bit prefix instead of the decoded value.
    val (tag, tagSize) = readUVarInt(offset)
    val (length, lengthSize) = readUVarInt(offset + tagSize)
    require(length <= Int.MAX_VALUE - offset - tagSize - lengthSize) { "invalid TLV length" }
    val valueOffset = offset + tagSize + lengthSize
    val value = copyOfRange(valueOffset, valueOffset + length.toInt())
    return TLV(
        tag = tag,
        value = value,
        size = tagSize + lengthSize + length.toInt(),
    )
}

/**
 * Reads a QUIC variable-length integer, returning the value and the number of bytes consumed.
 */
private fun ByteArray.readUVarInt(offset: Int = 0): Pair<Long, Int> {
    val first = this[offset].toInt() and 0xFF
    val size = 1 shl (first shr 6)
    var value = (first and 0x3F).toLong()
    for (i in 1 until size) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return value to size
}
