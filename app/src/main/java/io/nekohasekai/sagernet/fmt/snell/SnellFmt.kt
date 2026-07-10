/******************************************************************************
 * Snell share-link helpers for Exclave (v3–v6).
 * snell://psk@host:port?version=6&obfs=tls&obfs-host=...&mode=default&reuse=1#name
 ******************************************************************************/

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
            else -> link.queryParameter("psk") ?: error("empty psk")
        }
        link.queryParameter("version")?.toIntOrNull()?.also { version = it }
        link.queryParameter("obfs")?.also { obfs = it }
        link.queryParameter("obfs-mode")?.also { obfs = it }
        link.queryParameter("obfs-host")?.also { obfsHost = it }
        link.queryParameter("host")?.also { if (obfsHost.isNullOrEmpty()) obfsHost = it }
        link.queryParameter("mode")?.also { mode = it }
        link.queryParameter("reuse")?.also {
            reuse = it == "1" || it.equals("true", ignoreCase = true)
        }
        initializeDefaultValues()
    }
}

fun SnellBean.toUri(): String? {
    val builder = Libexclavecore.newURL("snell").apply {
        setHostPort(serverAddress.ifEmpty { error("empty server address") }, serverPort)
        username = psk.ifEmpty { error("empty psk") }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    builder.addQueryParameter("version", (version ?: 4).toString())
    if (!obfs.isNullOrEmpty() && obfs != SnellBean.OBFS_OFF && obfs != "none") {
        builder.addQueryParameter("obfs", obfs)
    }
    if (!obfsHost.isNullOrEmpty()) {
        builder.addQueryParameter("obfs-host", obfsHost)
    }
    if ((version ?: 4) >= 6 && !mode.isNullOrEmpty() && mode != SnellBean.MODE_DEFAULT) {
        builder.addQueryParameter("mode", mode)
    }
    if (reuse == true) {
        builder.addQueryParameter("reuse", "1")
    }
    return builder.string
}
