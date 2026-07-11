package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.*
import libexclavecore.Libexclavecore

fun parseSnell(url: String): SnellBean {
    val link = Libexclavecore.parseURL(url)
    return SnellBean().apply {
        name = link.fragment
        serverAddress = link.host.ifEmpty { error("empty host") }
        serverPort = link.port
        psk = when {
            link.username.isNotEmpty() -> link.username
            link.password.isNotEmpty() -> link.password
            else -> link.queryParameter("psk") ?: ""
        }
        link.queryParameter("version")?.toIntOrNull()?.also {
            if (it != 4 && it != 6) error("snell version must be 4 or 6")
            version = it
        }
        // accept only exact values; map legacy "off" only from share links that used it historically
        (link.queryParameter("obfsMode") ?: link.queryParameter("obfs"))?.also {
            obfsMode = if (it == "off") SnellBean.OBFS_NONE else it
        }
        link.queryParameter("obfs-host")?.also { obfsHost = it }
        link.queryParameter("mode")?.also { mode = it }
        link.queryParameter("user-psk")?.also { userPSK = it }
        link.queryParameter("userPSK")?.also { userPSK = it }
        link.queryParameter("reuse")?.also {
            reuse = it == "1" || it.equals("true", ignoreCase = true)
        }
        initializeDefaultValues()
    }
}

fun SnellBean.toUri(): String? {
    val builder = Libexclavecore.newURL("snell").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        if (!psk.isNullOrEmpty()) {
            username = psk
        }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    val v = version ?: 4
    builder.addQueryParameter("version", v.toString())
    if (!obfsMode.isNullOrEmpty() && obfsMode != SnellBean.OBFS_NONE) {
        builder.addQueryParameter("obfsMode", obfsMode)
    }
    if (!obfsHost.isNullOrEmpty()) {
        builder.addQueryParameter("obfs-host", obfsHost)
    }
    if (v >= 6 && !mode.isNullOrEmpty() && mode != SnellBean.MODE_DEFAULT) {
        builder.addQueryParameter("mode", mode)
    }
    if (!userPSK.isNullOrEmpty()) {
        builder.addQueryParameter("user-psk", userPSK)
    }
    if (reuse == true) {
        builder.addQueryParameter("reuse", "1")
    }
    return builder.string
}
