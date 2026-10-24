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

package io.nekohasekai.sagernet.group

import com.github.shadowsocks.plugin.PluginOptions
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.supportedShadowsocksMethod
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRMethod
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRObfs
import io.nekohasekai.sagernet.fmt.shadowsocksr.supportedShadowsocksRProtocol
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.tuic5.supportedTuic5CongestionControl
import io.nekohasekai.sagernet.fmt.tuic5.supportedTuic5RelayMode
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.supportedVmessMethod
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.DurationUnit

fun parseSingBoxOutbound(outbound: JsonObject): List<AbstractBean> {
    when (val type = outbound.getString("type", ignoreCase = false)) {
        "shadowsocks", "trojan", "vmess", "vless", "socks", "http" -> {
            val v2rayBean = when (type) {
                "shadowsocks" -> ShadowsocksBean()
                "trojan" -> TrojanBean()
                "vmess" -> VMessBean()
                "vless" -> VLESSBean()
                "socks" -> SOCKSBean()
                "http" -> HttpBean()
                else -> return listOf()
            }.apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    serverPort = it
                } ?: return listOf()
            }
            when (type) {
                "trojan", "vmess", "vless" -> {
                    outbound.getObject("transport")?.takeIf { !it.isEmpty }?.also { transport ->
                        when (transport.getString("type")) {
                            "ws" -> {
                                v2rayBean.type = "ws"
                                transport.getString("path")?.also {
                                    v2rayBean.path = it
                                }
                                transport.getObject("headers")?.also { headers ->
                                    headers.getStringArray("host")?.get(0)?.also {
                                        v2rayBean.host = it
                                    } ?: headers.getString("host")?.also {
                                        v2rayBean.host = it
                                    }
                                }
                                transport.getInt("max_early_data")?.also {
                                    v2rayBean.maxEarlyData = it
                                }
                                transport.getString("early_data_header_name")?.also {
                                    v2rayBean.earlyDataHeaderName = it
                                }
                            }
                            "http" -> {
                                v2rayBean.type = "tcp"
                                v2rayBean.headerType = "http"
                                // Difference from v2ray-core
                                // TLS is not enforced. If TLS is not configured, plain HTTP 1.1 is used.
                                outbound.getObject("tls")?.also {
                                    if (it.getBoolean("enabled") == true) {
                                        v2rayBean.type = "http"
                                        v2rayBean.headerType = null
                                    }
                                }
                                transport.getString("path")?.also {
                                    v2rayBean.path = it
                                }
                                transport.getStringArray("host")?.also {
                                    v2rayBean.host = it.joinToString("\n")
                                } ?: transport.getString("host")?.also {
                                    v2rayBean.host = it
                                }
                            }
                            "quic" -> {
                                v2rayBean.type = "quic"
                            }
                            "grpc" -> {
                                v2rayBean.type = "grpc"
                                transport.getString("service_name")?.also {
                                    v2rayBean.grpcServiceName = it
                                }
                            }
                            "httpupgrade" -> {
                                v2rayBean.type = "httpupgrade"
                                transport.getString("host")?.also {
                                    v2rayBean.host = it
                                }
                                transport.getString("path")?.also {
                                    v2rayBean.path = it
                                }
                            }
                            else -> return listOf()
                        }
                    }
                }
            }
            when (type) {
                "trojan", "vmess", "vless", "http" -> {
                    outbound.getObject("tls")?.also { tls ->
                        (tls.getBoolean("enabled"))?.also { enabled ->
                            if (enabled) {
                                v2rayBean.security = "tls"
                                tls.getString("server_name")?.also {
                                    v2rayBean.sni = it
                                }
                                tls.getBoolean("insecure")?.also {
                                    v2rayBean.allowInsecure = it
                                }
                                tls.getStringArray("alpn")?.also {
                                    v2rayBean.alpn = it.joinToString("\n")
                                } ?: tls.getString("alpn")?.also {
                                    v2rayBean.alpn = it
                                }
                                if (v2rayBean.alpn == null && v2rayBean.type == "quic") {
                                    // https://github.com/SagerNet/sing-box/pull/1934
                                    v2rayBean.alpn = "h3"
                                }
                                /*if (v2rayBean.alpn == null && type == "http") {
                                    // sing-box does not support HTTP/2 CONNECT
                                    v2rayBean.alpn = "http/1.1"
                                }*/
                                if (!tls.contains("certificate_path")) {
                                    var cert: String? = null
                                    tls.getStringArray("certificate")?.also { certificate ->
                                        cert = certificate.joinToString("\n").takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                        }
                                    } ?: tls.getString("certificate")?.also { certificate ->
                                        cert = certificate.takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                        }
                                    }
                                    if (cert != null) {
                                        v2rayBean.certificates = cert
                                    }
                                }
                                if (!tls.contains("client_certificate_path") && !tls.contains("client_key_path")) {
                                    var cert: String? = null
                                    tls.getStringArray("client_certificate")?.also { clientCert ->
                                        cert = clientCert.joinToString("\n").takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                        }
                                    } ?: tls.getString("client_certificate")?.also { clientCert ->
                                        cert = clientCert.takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                        }
                                    }
                                    var key: String? = null
                                    tls.getStringArray("client_key")?.also { clientKey ->
                                        key = clientKey.joinToString("\n").takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                                        }
                                    } ?: tls.getString("client_key")?.also { clientKey ->
                                        key = clientKey.takeIf {
                                            it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                                        }
                                    }
                                    if (cert != null && key != null) {
                                        v2rayBean.mtlsCertificate = cert
                                        v2rayBean.mtlsCertificatePrivateKey = key
                                    }
                                }
                                tls.getByteArrayArray("certificate_public_key_sha256")?.also {
                                    v2rayBean.pinnedPeerCertificatePublicKeySha256 = it.joinToString("\n") { Base64.encode(it) }
                                    v2rayBean.allowInsecure = true
                                } ?: tls.getByteArray("certificate_public_key_sha256")?.also {
                                    v2rayBean.pinnedPeerCertificatePublicKeySha256 = Base64.encode(it)
                                    v2rayBean.allowInsecure = true
                                }
                                tls.getObject("reality")?.also { reality ->
                                    reality.getBoolean("enabled")?.also { enabled ->
                                        if (enabled) {
                                            v2rayBean.security = "reality"
                                            reality.getString("public_key")?.also {
                                                v2rayBean.realityPublicKey = it
                                            }
                                            reality.getString("short_id")?.also {
                                                v2rayBean.realityShortId = it
                                            }
                                        }
                                    }
                                }
                                if (v2rayBean is VLESSBean || v2rayBean is TrojanBean) {
                                    // Only parse ECH for shit VLESS or Trojan free nodes
                                    tls.getObject("ech")?.also { ech ->
                                    ech.getBoolean("enabled")?.also { enabled ->
                                        if (enabled) {
                                            v2rayBean.echEnabled = true
                                            ech.getStringArray("config")?.also {
                                                v2rayBean.echConfig = parseECHConfigPem(it.joinToString("\n"))
                                            } ?: ech.getString("config")?.also {
                                                v2rayBean.echConfig = parseECHConfigPem(it)
                                            }
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
            when (type) {
                "socks" -> {
                    v2rayBean as SOCKSBean
                    outbound.getString("version")?.also {
                        v2rayBean.protocol = when (it) {
                            "4" -> SOCKSBean.PROTOCOL_SOCKS4
                            "4a" -> SOCKSBean.PROTOCOL_SOCKS4A
                            "", "5" -> SOCKSBean.PROTOCOL_SOCKS5
                            else -> return listOf()
                        }
                    }
                    outbound.getString("username")?.also {
                        v2rayBean.username = it
                    }
                    if (v2rayBean.protocol == SOCKSBean.PROTOCOL_SOCKS5) {
                        outbound.getString("password")?.also {
                            v2rayBean.password = it
                        }
                    }
                }
                "http" -> {
                    v2rayBean as HttpBean
                    outbound.getString("path")?.also {
                        if (it != "" && it != "/") {
                            // unsupported
                            return listOf()
                        }
                    }
                    outbound.getString("username")?.also {
                        v2rayBean.username = it
                    }
                    outbound.getString("password")?.also {
                        v2rayBean.password = it
                    }
                }
                "shadowsocks" -> {
                    v2rayBean as ShadowsocksBean
                    outbound.getString("method")?.also {
                        if (it !in supportedShadowsocksMethod) return listOf()
                        v2rayBean.method = it
                    }
                    outbound.getString("password")?.also {
                        v2rayBean.password = it
                    }
                    outbound.getString("plugin")?.takeIf { it.isNotEmpty() }?.also { pluginId ->
                        if (pluginId != "obfs-local" && pluginId != "v2ray-plugin") return listOf()
                        v2rayBean.plugin = PluginOptions(pluginId, outbound.getString("plugin_opts")).toString(trimId = false)
                    }
                }
                "trojan" -> {
                    v2rayBean as TrojanBean
                    outbound.getString("password")?.also {
                        v2rayBean.password = it
                    }
                }
                "vmess" -> {
                    v2rayBean as VMessBean
                    outbound.getString("uuid")?.also {
                        v2rayBean.uuid = uuidOrGenerate(it)
                    }
                    outbound.getString("security")?.also {
                        if (it !in supportedVmessMethod) return listOf()
                        v2rayBean.encryption = it
                    }
                    outbound.getInt("alter_id")?.also {
                        v2rayBean.alterId = it
                    }
                    outbound.getBoolean("global_padding")?.also {
                        v2rayBean.experimentalAuthenticatedLength = it
                    }
                    v2rayBean.packetEncoding = when (outbound.getString("packet_encoding")) {
                        "packetaddr" -> "packet"
                        "xudp" -> "xudp"
                        else -> "none"
                    }
                }
                "vless" -> {
                    v2rayBean as VLESSBean
                    outbound.getString("uuid")?.also {
                        v2rayBean.uuid = uuidOrGenerate(it)
                    }
                    v2rayBean.packetEncoding = when (outbound.getString("packet_encoding")) {
                        "packetaddr" -> "packet"
                        "xudp", null -> "xudp"
                        else -> "none"
                    }
                    outbound.getString("flow")?.also {
                        when (it) {
                            "" -> {}
                            "xtls-rprx-vision" -> {
                                v2rayBean.flow = "xtls-rprx-vision-udp443"
                                v2rayBean.packetEncoding = "xudp"
                            }
                            else -> return listOf()
                        }
                    }
                }
            }
            return listOf(v2rayBean)
        }
        "hysteria2" -> {
            outbound.getObject("realm")?.also {
                // unsupported
                return listOf()
            }
            val hysteria2Bean = Hysteria2Bean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.takeIf { it > 0 }?.also {
                    serverPorts = it.toString()
                }
                outbound.getStringArray("server_ports")?.takeIf { it.isNotEmpty() }?.also {
                    serverPorts = it.joinToString(",").replace(":", "-")
                } ?: outbound.getString("server_ports")?.also {
                    serverPorts = it.replace(":", "-")
                }
                if (!serverPorts.isValidHysteriaPort()) {
                    return listOf()
                }
                outbound.getString("hop_interval_max")?.also { interval ->
                    try {
                        val duration = Duration.parse(interval)
                        hopIntervalMax = duration.toLong(DurationUnit.SECONDS).takeIf { it > 0 }
                    } catch (_: Exception) {}
                }
                outbound.getString("hop_interval")?.also { interval ->
                    try {
                        val duration = Duration.parse(interval)
                        if (hopIntervalMax != null) {
                            hopIntervalMin = duration.toLong(DurationUnit.SECONDS).takeIf { it > 0 }
                        } else {
                            hopInterval = duration.toLong(DurationUnit.SECONDS).takeIf { it > 0 }
                        }
                    } catch (_: Exception) {}
                }
                outbound.getString("password")?.also {
                    auth = it
                }
                outbound.getObject("tls")?.also { tls ->
                    if (tls.getBoolean("enabled") != true) {
                        return listOf()
                    }
                    if (tls.getObject("reality")?.getBoolean("enabled") == true) {
                        return listOf()
                    }
                    tls.getString("server_name")?.also {
                        sni = it
                    }
                    tls.getBoolean("insecure")?.also {
                        allowInsecure = it
                    }
                    if (!tls.contains("certificate_path")) {
                        var cert: String? = null
                        tls.getStringArray("certificate")?.also { certificate ->
                            cert = certificate.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        } ?: tls.getString("certificate")?.also { certificate ->
                            cert = certificate.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        }
                        if (cert != null) {
                            certificates = cert
                        }
                    }
                    if (!tls.contains("client_certificate_path") && !tls.contains("client_key_path")) {
                        var cert: String? = null
                        tls.getStringArray("client_certificate")?.also { clientCert ->
                            cert = clientCert.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        } ?: tls.getString("client_certificate")?.also { clientCert ->
                            cert = clientCert.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        }
                        var key: String? = null
                        tls.getStringArray("client_key")?.also { clientKey ->
                            key = clientKey.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                            }
                        } ?: tls.getString("client_key")?.also { clientKey ->
                            key = clientKey.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                            }
                        }
                        if (cert != null && key != null) {
                            mtlsCertificate = cert
                            mtlsCertificatePrivateKey = key
                        }
                    }
                    tls.getByteArrayArray("certificate_public_key_sha256")?.also {
                        pinnedPeerCertificatePublicKeySha256 = it.joinToString("\n") { Base64.encode(it) }
                        allowInsecure = true
                    } ?: tls.getByteArray("certificate_public_key_sha256")?.also {
                        pinnedPeerCertificatePublicKeySha256 = Base64.encode(it)
                        allowInsecure = true
                    }
                    /*tls.getObject("ech")?.also { ech ->
                        ech.getBoolean("enabled")?.also { enabled ->
                            if (enabled) {
                                echEnabled = true
                                ech.getStringArray("config")?.also {
                                    echConfig = parseECHConfigPem(it.joinToString("\n"))
                                } ?: ech.getString("config")?.also {
                                    echConfig = parseECHConfigPem(it)
                                }
                            }
                        }
                    }*/
                } ?: return listOf()
                outbound.getObject("obfs")?.also { obfuscation ->
                    obfuscation.getString("type")?.takeIf { it.isNotEmpty() }?.also { type ->
                        if (type != "salamander") return listOf()
                        obfuscation.getString("password")?.also {
                            obfs = it
                        }
                    }
                }
            }
            return listOf(hysteria2Bean)
        }
        "tuic" -> {
            val tuic5Bean = Tuic5Bean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    serverPort = it
                } ?: return listOf()
                outbound.getString("uuid")?.also {
                    uuid = it
                }
                outbound.getString("password")?.also {
                    password = it
                }
                outbound.getString("congestion_control")?.also {
                    congestionControl = if (it in supportedTuic5CongestionControl) it else "cubic"
                }
                outbound.getString("udp_relay_mode")?.also {
                    udpRelayMode = if (it in supportedTuic5RelayMode) it else "native"
                }
                outbound.getBoolean("zero_rtt_handshake")?.also {
                    zeroRTTHandshake = it
                }
                outbound.getObject("tls")?.also { tls ->
                    if (tls.getBoolean("enabled") != true) {
                        return listOf()
                    }
                    if (tls.getObject("reality")?.getBoolean("enabled") == true) {
                        return listOf()
                    }
                    tls.getString("server_name")?.also {
                        sni = it
                    }
                    tls.getStringArray("alpn")?.also {
                        alpn = it.joinToString("\n")
                    } ?: tls.getString("alpn")?.also {
                        alpn = it
                    }
                    tls.getBoolean("insecure")?.also {
                        allowInsecure = it
                    }
                    tls.getBoolean("disable_sni")?.also {
                        disableSNI = it
                    }
                    if (!tls.contains("certificate_path")) {
                        var cert: String? = null
                        tls.getStringArray("certificate")?.also { certificate ->
                            cert = certificate.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        } ?: tls.getString("certificate")?.also { certificate ->
                            cert = certificate.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        }
                        if (cert != null) {
                            certificates = cert
                        }
                    }
                    if (!tls.contains("client_certificate_path") && !tls.contains("client_key_path")) {
                        var cert: String? = null
                        tls.getStringArray("client_certificate")?.also { clientCert ->
                            cert = clientCert.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        } ?: tls.getString("client_certificate")?.also { clientCert ->
                            cert = clientCert.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        }
                        var key: String? = null
                        tls.getStringArray("client_key")?.also { clientKey ->
                            key = clientKey.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                            }
                        } ?: tls.getString("client_key")?.also { clientKey ->
                            key = clientKey.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                            }
                        }
                        if (cert != null && key != null) {
                            mtlsCertificate = cert
                            mtlsCertificatePrivateKey = key
                        }
                    }
                    tls.getByteArrayArray("certificate_public_key_sha256")?.also {
                        pinnedPeerCertificatePublicKeySha256 = it.joinToString("\n") { Base64.encode(it) }
                        allowInsecure = true
                    } ?: tls.getByteArray("certificate_public_key_sha256")?.also {
                        pinnedPeerCertificatePublicKeySha256 = Base64.encode(it)
                        allowInsecure = true
                    }
                    /*tls.getObject("ech")?.also { ech ->
                        ech.getBoolean("enabled")?.also { enabled ->
                            if (enabled) {
                                echEnabled = true
                                ech.getStringArray("config")?.also {
                                    echConfig = parseECHConfigPem(it.joinToString("\n"))
                                } ?: ech.getString("config")?.also {
                                    echConfig = parseECHConfigPem(it)
                                }
                            }
                        }
                    }*/
                } ?: return listOf()
            }
            return listOf(tuic5Bean)
        }
        "ssh" -> {
            val sshBean = SSHBean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.takeIf { it > 0 }?.also {
                    serverPort = it
                } ?: {
                    // https://github.com/SagerNet/sing-box/blob/5f739c4e66163ce88dbf75456e698c191d7069a6/protocol/ssh/outbound.go#L68
                    serverPort = 22
                }
                outbound.getString("user")?.takeIf { it.isNotEmpty() }?.also {
                    username = it
                } ?: {
                    // https://github.com/SagerNet/sing-box/blob/5f739c4e66163ce88dbf75456e698c191d7069a6/protocol/ssh/outbound.go#L71
                    username = "root"
                }
                if (outbound.getString("password")?.isNotEmpty() == true) {
                    authType = SSHBean.AUTH_TYPE_PASSWORD
                    outbound.getString("password")?.also {
                        password = it
                    }
                }
                if (outbound.getString("private_key")?.isNotEmpty() == true) {
                    authType = SSHBean.AUTH_TYPE_PUBLIC_KEY
                    outbound.getStringArray("private_key")?.also {
                        privateKey = it.joinToString("\n")
                    } ?: outbound.getString("private_key")?.also {
                        privateKey = it
                    }
                    outbound.getString("private_key_passphrase")?.also {
                        privateKeyPassphrase = it
                    }
                }
                outbound.getStringArray("host_key")?.also {
                    publicKey = it.joinToString("\n")
                } ?: outbound.getString("host_key")?.also {
                    publicKey = it
                }
            }
            return listOf(sshBean)
        }
        "ssr" -> {
            // removed in v1.6.0
            val ssrBean = ShadowsocksRBean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    serverPort = it
                } ?: return listOf()
                outbound.getString("method")?.also {
                    if (it !in supportedShadowsocksRMethod) return listOf()
                    method = it
                }
                outbound.getString("password")?.also {
                    password = it
                }
                outbound.getString("obfs")?.also {
                    obfs = when (it) {
                        "tls1.2_ticket_fastauth" -> "tls1.2_ticket_auth"
                        in supportedShadowsocksRObfs -> it
                        else -> return listOf()
                    }
                }
                outbound.getString("obfs_param")?.also {
                    obfsParam = it
                }
                outbound.getString("protocol")?.also {
                    if (it !in supportedShadowsocksRProtocol) return listOf()
                    protocol = it
                }
                outbound.getString("protocol_param")?.also {
                    protocolParam = it
                }
            }
            return listOf(ssrBean)
        }
        "anytls" -> {
            val anytlsBean = AnyTLSBean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    serverPort = it
                } ?: return listOf()
                outbound.getString("password")?.also {
                    password = it
                }
                outbound.getObject("tls")?.also { tls ->
                    (tls.getBoolean("enabled"))?.also { enabled ->
                        if (enabled) {
                            security = "tls"
                            tls.getString("server_name")?.also {
                                sni = it
                            }
                            tls.getBoolean("insecure")?.also {
                                allowInsecure = it
                            }
                            tls.getStringArray("alpn")?.also {
                                alpn = it.joinToString("\n")
                            } ?: tls.getString("alpn")?.also {
                                alpn = it
                            }
                            if (!tls.contains("certificate_path")) {
                                var cert: String? = null
                                tls.getStringArray("certificate")?.also { certificate ->
                                    cert = certificate.joinToString("\n").takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                    }
                                } ?: tls.getString("certificate")?.also { certificate ->
                                    cert = certificate.takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                    }
                                }
                                if (cert != null) {
                                    certificates = cert
                                }
                            }
                            if (!tls.contains("client_certificate_path") && !tls.contains("client_key_path")) {
                                var cert: String? = null
                                tls.getStringArray("client_certificate")?.also { clientCert ->
                                    cert = clientCert.joinToString("\n").takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                    }
                                } ?: tls.getString("client_certificate")?.also { clientCert ->
                                    cert = clientCert.takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                                    }
                                }
                                var key: String? = null
                                tls.getStringArray("client_key")?.also { clientKey ->
                                    key = clientKey.joinToString("\n").takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                                    }
                                } ?: tls.getString("client_key")?.also { clientKey ->
                                    key = clientKey.takeIf {
                                        it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" PRIVATE KEY-----")
                                    }
                                }
                                if (cert != null && key != null) {
                                    mtlsCertificate = cert
                                    mtlsCertificatePrivateKey = key
                                }
                            }
                            tls.getByteArrayArray("certificate_public_key_sha256")?.also {
                                pinnedPeerCertificatePublicKeySha256 = it.joinToString("\n") { Base64.encode(it) }
                                allowInsecure = true
                            } ?: tls.getByteArray("certificate_public_key_sha256")?.also {
                                pinnedPeerCertificatePublicKeySha256 = Base64.encode(it)
                                allowInsecure = true
                            }
                            tls.getObject("reality")?.also { reality ->
                                reality.getBoolean("enabled")?.also { enabled ->
                                    if (enabled) {
                                        security = "reality"
                                        reality.getString("public_key")?.also {
                                            realityPublicKey = it
                                        }
                                        reality.getString("short_id")?.also {
                                            realityShortId = it
                                        }
                                    }
                                }
                            }
                            /*tls.getObject("ech")?.also { ech ->
                                ech.getBoolean("enabled")?.also { enabled ->
                                    if (enabled) {
                                        echEnabled = true
                                        ech.getStringArray("config")?.also {
                                            echConfig = parseECHConfigPem(it.joinToString("\n"))
                                        } ?: ech.getString("config")?.also {
                                            echConfig = parseECHConfigPem(it)
                                        }
                                    }
                                }
                            }*/
                        } else {
                            security = "none"
                        }
                    }
                }
            }
            return listOf(anytlsBean)
        }
        "naive" -> {
            val naiveBean = NaiveBean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("server")?.also {
                    serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    serverPort = it
                } ?: return listOf()
                outbound.getString("username")?.also {
                    username = it
                }
                outbound.getString("password")?.also {
                    password = it
                }
                outbound.getBoolean("quic")?.takeIf { it }?.also {
                    proto = "quic"
                }
                outbound.getObject("tls")?.also { tls ->
                    tls.getString("server_name")?.also {
                        sni = it
                    }
                    if (!tls.contains("certificate_path")) {
                        var cert: String? = null
                        tls.getStringArray("certificate")?.also { certificate ->
                            cert = certificate.joinToString("\n").takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        } ?: tls.getString("certificate")?.also { certificate ->
                            cert = certificate.takeIf {
                                it.contains("-----BEGIN ") && it.contains("-----END ") && it.contains(" CERTIFICATE-----")
                            }
                        }
                        if (cert != null) {
                            certificate = cert
                        }
                    }
                }
            }
            return listOf(naiveBean)
        }
        "wireguard" -> {
            if (outbound.contains("address")) {
                // wireguard endpoint format introduced in 1.11.0-alpha.19
                return listOf()
            }
            val beanList = mutableListOf<WireGuardBean>()
            val bean = WireGuardBean().apply {
                outbound.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                outbound.getString("private_key")?.also {
                    privateKey = it
                }
                outbound.getString("peer_public_key")?.also {
                    peerPublicKey = it
                }
                outbound.getString("pre_shared_key")?.also {
                    peerPreSharedKey = it
                }
                mtu = 1408
                outbound.getInt("mtu")?.takeIf { it > 0 }?.also {
                    mtu = it
                }
                outbound.getStringArray("local_address")?.also {
                    localAddress = it.joinToString("\n")
                } ?: outbound.getString("local_address")?.also {
                    localAddress = it
                } ?: return listOf()
                outbound.getIntArray("reserved")?.also {
                    if (it.size == 3) {
                        reserved = listOf(it[0].toString(), it[1].toString(), it[2].toString()).joinToString(",")
                    }
                } ?: outbound.getString("reserved")?.also {
                    try {
                        val arr = Base64.decode(it)
                        if (arr.size == 3) {
                            reserved = listOf(arr[0].toUByte().toInt().toString(), arr[1].toUByte().toInt().toString(), arr[2].toUByte().toInt().toString()).joinToString(",")
                        }
                    } catch (_: Exception) {}

                }
            }
            if (outbound.contains("server")) {
                outbound.getString("server")?.also {
                    bean.serverAddress = it
                } ?: return listOf()
                outbound.getInt("server_port")?.also {
                    bean.serverPort = it
                } ?: return listOf()
                beanList.add(bean)
            }
            outbound.getArray("peers")?.forEach { peer ->
                beanList.add(bean.applyDefaultValues().clone().apply {
                    peer.getString("server")?.also {
                        serverAddress = it
                    }
                    peer.getInt("server_port")?.also {
                        serverPort = it
                    }
                    peer.getString("public_key")?.also {
                        peerPublicKey = it
                    }
                    peer.getString("pre_shared_key")?.also {
                        peerPreSharedKey = it
                    }
                    peer.getInt("persistent_keepalive_interval")?.takeIf { it > 0 }?.also {
                        keepaliveInterval = it
                    }
                    peer.getIntArray("reserved")?.also {
                        if (it.size == 3) {
                            reserved = listOf(it[0].toString(), it[1].toString(), it[2].toString()).joinToString(",")
                        }
                    } ?: peer.getString("reserved")?.also {
                        try {
                            val arr = Base64.decode(it)
                            if (arr.size == 3) {
                                reserved = listOf(arr[0].toUByte().toInt().toString(), arr[1].toUByte().toInt().toString(), arr[2].toUByte().toInt().toString()).joinToString(",")
                            }
                        } catch (_: Exception) {}
                    }
                })
            }
            return beanList
        }
        else -> return listOf()
    }
}

fun parseSingBoxEndpoint(endpoint: JsonObject): List<AbstractBean> {
    when (endpoint.getString("type", ignoreCase = false)) {
        "wireguard" -> {
            val beanList = mutableListOf<WireGuardBean>()
            if (endpoint.contains("local_address")) {
                // legacy wireguard outbound format
                return listOf()
            }
            val bean = WireGuardBean().apply {
                endpoint.getString("tag", ignoreCase = false)?.also {
                    name = it
                }
                endpoint.getString("private_key")?.also {
                    privateKey = it
                }
                mtu = 1408
                endpoint.getInt("mtu")?.takeIf { it > 0 }?.also {
                    mtu = it
                }
                endpoint.getStringArray("address")?.also {
                    localAddress = it.joinToString("\n")
                } ?: endpoint.getString("address")?.also {
                    localAddress = it
                } ?: return listOf()
            }
            endpoint.getArray("peers")?.forEach { peer ->
                beanList.add(bean.applyDefaultValues().clone().apply {
                    peer.getString("address")?.also {
                        serverAddress = it
                    }
                    peer.getInt("port")?.also {
                        serverPort = it
                    }
                    peer.getString("public_key")?.also {
                        peerPublicKey = it
                    }
                    peer.getString("pre_shared_key")?.also {
                        peerPreSharedKey = it
                    }
                    peer.getInt("persistent_keepalive_interval")?.takeIf { it > 0 }?.also {
                        keepaliveInterval = it
                    }
                    peer.getIntArray("reserved")?.also {
                        if (it.size == 3) {
                            reserved = listOf(it[0].toString(), it[1].toString(), it[2].toString()).joinToString(",")
                        }
                    } ?: peer.getString("reserved")?.also {
                        try {
                            val arr = Base64.decode(it)
                            if (arr.size == 3) {
                                reserved = listOf(arr[0].toUByte().toInt().toString(), arr[1].toUByte().toInt().toString(), arr[2].toUByte().toInt().toString()).joinToString(",")
                            }
                        } catch (_: Exception) {}
                    }
                })
            }
            return beanList
        }
        else -> return listOf()
    }
}

private fun JsonObject.contains(key: String, ignoreCase: Boolean = true): Boolean {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return false
        value.isJsonNull -> if (!ignoreCase) return false
        else -> return true
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && !v.isJsonNull) {
            return true
        }
    }
    return false
}

private fun JsonObject.getString(key: String, ignoreCase: Boolean = true): String? {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return null
        value.isJsonNull -> if (!ignoreCase) return null
        value.isJsonPrimitive -> return if (value.asJsonPrimitive.isString) value.asString else null
        else -> return null
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
            return v.asString
        }
    }
    return null
}

private fun JsonObject.getInt(key: String, ignoreCase: Boolean = true): Int? {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return null
        value.isJsonNull -> if (!ignoreCase) return null
        value.isJsonPrimitive -> {
            return if (value.asJsonPrimitive.isNumber) {
                try {
                    value.asInt
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
            try {
                return v.asInt
            } catch (_: Exception) {}
        }
    }
    return null
}

private fun JsonObject.getBoolean(key: String, ignoreCase: Boolean = true): Boolean? {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return null
        value.isJsonNull -> if (!ignoreCase) return null
        value.isJsonPrimitive -> return if (value.asJsonPrimitive.isBoolean) value.asBoolean else null
        else -> return null
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) {
            return v.asBoolean
        }
    }
    return null
}

private fun JsonObject.getObject(key: String, ignoreCase: Boolean = true): JsonObject? {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return null
        value.isJsonNull -> if (!ignoreCase) return null
        value.isJsonObject -> return value.asJsonObject
        else -> return null
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && v.isJsonObject) {
            return v.asJsonObject
        }
    }
    return null
}

private fun JsonObject.getJsonArray(key: String, ignoreCase: Boolean = true): JsonArray? {
    val value = get(key)
    when {
        value == null -> if (!ignoreCase) return null
        value.isJsonNull -> if (!ignoreCase) return null
        value.isJsonArray -> return value.asJsonArray
        else -> return null
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true) && v.isJsonArray) {
            return v.asJsonArray
        }
    }
    return null
}

private fun JsonObject.getArray(key: String, ignoreCase: Boolean = true): List<JsonObject>? {
    val jsonArray = getJsonArray(key, ignoreCase) ?: return null
    return try {
        Gson().fromJson(jsonArray, Array<JsonObject>::class.java)?.asList()
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.getStringArray(key: String, ignoreCase: Boolean = true): List<String>? {
    val jsonArray = getJsonArray(key, ignoreCase) ?: return null
    return try {
        Gson().fromJson(jsonArray, Array<String>::class.java)?.asList()
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.getIntArray(key: String, ignoreCase: Boolean = true): List<Int>? {
    val jsonArray = getJsonArray(key, ignoreCase) ?: return null
    return try {
        Gson().fromJson(jsonArray, Array<Int>::class.java)?.asList()
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.getByteArray(key: String): ByteArray? {
    val value = get(key)
    when {
        value == null -> {}
        value.isJsonNull -> {}
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            return try {
                Base64.decode(value.asString.toByteArray())
            } catch (_: Exception) {
                null
            }
        }
        value.isJsonArray -> return try {
            Gson().fromJson(value, ByteArray::class.java)
        } catch (_: Exception) {
            null
        }
        else -> return null
    }
    for ((k, v) in entrySet()) {
        if (k.equals(key, ignoreCase = true)) {
            when {
                v.isJsonPrimitive && v.asJsonPrimitive.isString -> {
                    try {
                         return Base64.decode(v.asString.toByteArray())
                    } catch (_: Exception) {}
                }
                v.isJsonArray -> {
                    try {
                        return Gson().fromJson(v, ByteArray::class.java)
                    } catch (_: Exception) {}
                }
            }
        }
    }
    return null
}

private fun JsonObject.getByteArrayArray(key: String): Array<ByteArray>? {
    val jsonArray = getJsonArray(key, ignoreCase = true) ?: return null
    val ret = mutableListOf<ByteArray>()
    for (value in jsonArray) {
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                ret.add(
                    try {
                        Base64.decode(value.asString.toByteArray())
                    } catch (_: Exception) {
                        return null
                    }
                )
            }
            value.isJsonArray -> {
                ret.add(
                    try {
                        Gson().fromJson(value, ByteArray::class.java)
                    } catch (_: Exception) {
                        return null
                    }
                )
            }
            value.isJsonNull -> continue
            else -> return null
        }
    }
    return ret.toTypedArray()
}

// this is not strict, but enough
private fun parseECHConfigPem(pem: String): String? {
    if (pem.split("-----BEGIN ECH CONFIGS-----").size - 1 != 1) {
        return null
    }
    if (pem.split("-----END ECH CONFIGS-----").size - 1 != 1) {
        return null
    }
    return try {
         Base64.encode(Base64.decode(pem
            .substringAfter("-----BEGIN ECH CONFIGS-----")
            .substringBefore("-----END ECH CONFIGS-----")
            .replace("\r", "")
            .replace("\n", "")
        ))
    } catch (_: Exception) {
        null
    }
}
