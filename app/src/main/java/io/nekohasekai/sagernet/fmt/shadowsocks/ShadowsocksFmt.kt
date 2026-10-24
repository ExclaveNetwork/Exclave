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

package io.nekohasekai.sagernet.fmt.shadowsocks

import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginOptions
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore
import kotlin.io.encoding.Base64

val supportedShadowsocksMethod = arrayOf(
    "aes-128-gcm","aes-192-gcm","aes-256-gcm",
    "chacha20-ietf-poly1305","xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305",
    "rc4-md5",
    "aes-128-ctr","aes-192-ctr","aes-256-ctr",
    "aes-128-cfb","aes-192-cfb","aes-256-cfb",
    "bf-cfb",
    "camellia-128-cfb","camellia-192-cfb","camellia-256-cfb",
    "salsa20","chacha20","chacha20-ietf","xchacha20",
    "none", "table"
)

val supportedShadowsocks2022Method = arrayOf(
    "2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305",
)

fun ShadowsocksBean.fixInvalidParams() {
    if (!plugin.isNullOrEmpty()) {
        plugin = PluginConfiguration(plugin).apply {
            // A typo in https://github.com/shadowsocks/shadowsocks-org/blob/6b1c064db4129de99c516294960e731934841c94/docs/doc/sip002.md?plain=1#L15
            // "simple-obfs" should be "obfs-local"
            if (selected == "simple-obfs") {
                pluginsOptions["obfs-local"] = getOptions().apply { id = "obfs-local" }
                pluginsOptions.remove(selected)
                selected = "obfs-local"
            }
        }.toString()
    }
}

fun parseShadowsocks(url: String): ShadowsocksBean {
    val link = Libsagernetcore.parseURL(url)
    if (link.port == 0 && link.username.isEmpty() && link.password.isEmpty()) {
        // pre-SIP002, https://shadowsocks.org/doc/configs.html#uri-and-qr-code
        // example: ss://YmYtY2ZiOnRlc3QvIUAjOkAxOTIuMTY4LjEwMC4xOjg4ODg#example-server
        val plainUri = url.substring("ss://".length).substringBefore("#").decodeBase64()

        return ShadowsocksBean().apply {
            serverAddress = plainUri.substringAfterLast("@").substringBeforeLast(":")
                .removePrefix("[").removeSuffix("]").ifEmpty { error("empty host") }
            serverPort = plainUri.substringAfterLast("@").substringAfterLast(":")
                .toIntOrNull() ?: error("invalid port")
            method = when (val m = plainUri.substringBeforeLast("@").substringBefore(":").lowercase()) {
                in supportedShadowsocksMethod -> m
                "plain", "dummy" -> "none"
                "chacha20-poly1305" -> "chacha20-ietf-poly1305"
                "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
                else -> error("unsupported method")
            }
            password = plainUri.substringBeforeLast("@").substringAfter(":")
            name = link.fragment
        }
    }
    if (link.password.isNotEmpty() ||
        url.substring("ss://".length).substringBefore("#")
            .substringBefore("@").endsWith(":")) {
        // SIP002, plain user info
        // example: ss://2022-blake3-aes-256-gcm:YctPZ6U7xPPcU%2Bgp3u%2B0tx%2FtRizJN9K8y%2BuKlW2qjlI%3D@192.168.100.1:8888#Example3
        // example: ss://none:@192.168.100.1:8888#example
        return ShadowsocksBean().apply {
            serverAddress = link.host.ifEmpty { error("empty host") }
            serverPort = link.port
            method = when (val m = link.username?.lowercase()) {
                in supportedShadowsocksMethod -> m
                "plain", "dummy" -> "none"
                "chacha20-poly1305" -> "chacha20-ietf-poly1305"
                "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
                else -> error("unsupported method")
            }
            password = link.password
            plugin = link.queryParameter("plugin")
            name = link.fragment
            fixInvalidParams()
        }
    }
    return ShadowsocksBean().apply {
        // SIP002, user info encoded with Base64URL
        // example: ss://YWVzLTEyOC1nY206dGVzdA@127.0.0.1:8888#Example1
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port
        method = when (val m = link.username?.decodeBase64()?.substringBefore(":")?.lowercase()) {
            in supportedShadowsocksMethod -> m
            "plain", "dummy" -> "none"
            "chacha20-poly1305" -> "chacha20-ietf-poly1305"
            "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
            else -> error("unsupported method")
        }
        password = link.username.decodeBase64().substringAfter(":")
        plugin = link.queryParameter("plugin")
        name = link.fragment
        fixInvalidParams()
    }
}

fun ShadowsocksBean.toUri(): String? {
    if (security != "none") error("unsupported ss with tls")
    if (type != "tcp" || headerType != "none") error("unsupported ss with v2ray transport")

    val builder = Libsagernetcore.newURL("ss")
    builder.setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
    if (method in supportedShadowsocks2022Method) {
        builder.username = method
        if (password.isNotEmpty()) {
            builder.password = password
        } else {
            error("empty password")
        }
    } else {
        builder.username = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("$method:$password".toByteArray())
    }

    if (plugin.isNotEmpty() && PluginConfiguration(plugin).selected.isNotEmpty()) {
        builder.rawPath = "/"
        builder.addQueryParameter("plugin",
            PluginOptions(
                PluginConfiguration(plugin).selected,
                PluginConfiguration(plugin).getOptions().toString()
            ).toString(trimId = false)
        )
    }

    if (name.isNotEmpty()) {
        builder.fragment = name
    }

    return builder.string

}

fun parseShadowsocksConfig(config: JsonObject): ShadowsocksBean? {
    return ShadowsocksBean().apply {
        serverAddress = config.getString("server")?.ifEmpty { error("empty host") } ?: return null
        serverPort = config.getInt("server_port") ?: return null
        password = config.getString("password")

        var m = config.getString("method")?.lowercase()
        if (!m.isNullOrEmpty() && m.contains("_") && !m.contains("-")) {
            m = m.replace("_", "-")
        }
        method = when (m) {
            in supportedShadowsocksMethod -> m
            "plain", "dummy" -> "none"
            "aead-chacha20-poly1305", "aead-chacha20-ietf-poly1305", "chacha20-poly1305" -> "chacha20-ietf-poly1305"
            "aead-xchacha20-poly1305", "aead-xchacha20-ietf-poly1305", "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
            "aead-aes-128-gcm" -> "aes-128-gcm"
            "aead-aes-192-gcm" -> "aes-192-gcm"
            "aead-aes-256-gcm" -> "aes-256-gcm"
            "", null -> error("unsupported method") // different impl has different default value
            else -> error("unsupported method")
        }
        val pluginId = when (val id = config.getString("plugin")) {
            "simple-obfs" -> "obfs-local"
            else -> id
        }
        if (!pluginId.isNullOrEmpty()) {
            plugin = PluginOptions(pluginId, config.getString("plugin_opts")).toString(trimId = false)
        }
        name = config.getString("remarks")
    }
}
