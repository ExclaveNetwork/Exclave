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

package io.nekohasekai.sagernet.fmt.shadowsocksr
import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore
import java.util.Locale
import kotlin.io.encoding.Base64

val supportedShadowsocksRMethod = arrayOf(
    "rc4-md5",
    "aes-128-ctr","aes-192-ctr","aes-256-ctr",
    "aes-128-cfb","aes-192-cfb","aes-256-cfb",
    "bf-cfb",
    "camellia-128-cfb","camellia-192-cfb","camellia-256-cfb",
    "salsa20","chacha20","chacha20-ietf","xchacha20",
    "none", "table"
)

val supportedShadowsocksRProtocol = arrayOf(
    "origin", "auth_sha1_v4", "auth_aes128_sha1", "auth_aes128_md5", "auth_chain_a", "auth_chain_b"
)

val supportedShadowsocksRObfs = arrayOf(
    "plain", "http_simple", "http_post", "tls1.2_ticket_auth", "random_head"
)

fun parseShadowsocksR(url: String): ShadowsocksRBean {
    // https://github.com/shadowsocksrr/shadowsocks-rss/wiki/SSR-QRcode-scheme
    val params = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(url.substring("ssr://".length))).split(":")
    if (params.size < 6) error("invalid url")

    val bean = ShadowsocksRBean().apply {
        serverAddress = params.subList(0, params.size - 5).joinToString(":").ifEmpty { error("empty host") } // serverAddress contains `:` if it is IPv6
        serverPort = params[params.size - 5].toIntOrNull() ?: error("invalid port")
        protocol = params[params.size - 4].takeIf { it in supportedShadowsocksRProtocol } ?: error("unsupported protocol")
        method = params[params.size - 3].takeIf { it in supportedShadowsocksRMethod } ?: error("unsupported method")
        obfs = when (val it = params[params.size - 2]) {
            "tls1.2_ticket_fastauth" -> "tls1.2_ticket_auth"
            else -> it.takeIf { it in supportedShadowsocksRObfs } ?: error("unsupported obfs")
        }
        password = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(params[params.size - 1].substringBefore("/")))
    }

    val httpUrl = Libsagernetcore.parseURL("https://localhost" + params[params.size - 1].substringAfter("/", ""))

    httpUrl.queryParameter("obfsparam")?.let {
        bean.obfsParam = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it))
    }

    httpUrl.queryParameter("protoparam")?.let {
        bean.protocolParam = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it))
    }

    httpUrl.queryParameter("remarks")?.let {
        bean.name = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it))
    }

    return bean
}

fun ShadowsocksRBean.toUri(): String {
    return "ssr://" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
        "%s:%d:%s:%s:%s:%s/?obfsparam=%s&protoparam=%s&remarks=%s".format(
            Locale.ENGLISH,
            serverAddress.ifEmpty { error("empty server address") },
            serverPort,
            protocol,
            method,
            obfs,
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("%s".format(Locale.ENGLISH, password).toByteArray()),
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("%s".format(Locale.ENGLISH, obfsParam).toByteArray()),
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("%s".format(Locale.ENGLISH, protocolParam).toByteArray()),
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("%s".format(Locale.ENGLISH, name ?: "").toByteArray())
        ).toByteArray()
    )
}
