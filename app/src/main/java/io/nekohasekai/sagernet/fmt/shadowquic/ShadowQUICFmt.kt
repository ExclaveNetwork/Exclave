/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.shadowquic

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.joinHostPort
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

fun ShadowQUICBean.buildShadowQUICConfig(port: Int, username: String = "", password: String = "", cacheFile: (() -> File)? = null, forExport: Boolean = false): String {
    val confObject: MutableMap<String, Any> = HashMap()

    val inboundObject: MutableMap<String, Any> = HashMap()
    inboundObject["type"] = "socks"
    inboundObject["bind-addr"] = joinHostPort(LOCALHOST, port)
    if (username.isNotEmpty() && password.isNotEmpty()) {
        val userObject: MutableMap<String, Any> = HashMap()
        userObject["username"] = username
        userObject["password"] = password
        inboundObject["users"] = listOf(userObject)
    }
    confObject["inbound"] = inboundObject

    val outboundObject: MutableMap<String, Any> = HashMap()
    outboundObject["type"] = if (useSunnyQUIC) "sunnyquic" else "shadowquic"
    outboundObject["addr"] = joinHostPort(finalAddress, finalPort)
    if (this.username.isNotEmpty()) outboundObject["username"] = this.username
    if (this.password.isNotEmpty()) outboundObject["password"] = this.password
    if (sni.isNotEmpty()) outboundObject["server-name"] = sni
    if (disableALPN) {
        outboundObject["alpn"] = listOf<String>()
    } else if (alpn.isNotEmpty()) {
        outboundObject["alpn"] = alpn.listByLineOrComma()
    }
    when (congestionControl) {
        "" -> {}
        "brutal" -> {
            val brutalObject: MutableMap<String, Any> = HashMap()
            brutalObject["bandwidth"] = "${brutalUploadBandwidth}m"
            val congestionControlObject: MutableMap<String, Any> = HashMap()
            congestionControlObject["brutal"] = brutalObject
            outboundObject["congestion-control"] = congestionControlObject
        }
        else -> {
            outboundObject["congestion-control"] = congestionControl
        }
    }
    if (zeroRTT) outboundObject["zero-rtt"] = zeroRTT
    if (udpOverStream) outboundObject["over-stream"] = udpOverStream
    if (useSunnyQUIC && certificate.isNotEmpty() && cacheFile != null) {
        val certificateFile = cacheFile()
        certificateFile.writeText(certificate)
        outboundObject["cert-path"] = certificateFile.absolutePath
    }
    if (!forExport && DataStore.serviceMode == Key.MODE_VPN && DataStore.tunImplementation == TunImplementation.SYSTEM && SagerNet.started && DataStore.startedProfile > 0) {
        outboundObject["protect-path"] = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
    }
    confObject["outbound"] = outboundObject

    confObject["log-level"] = when (DataStore.logLevel) {
        LogLevel.DEBUG -> "trace"
        LogLevel.INFO -> "info"
        LogLevel.WARNING -> "warn"
        LogLevel.ERROR -> "error"
        else -> "error"
    }

    val options = DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
    options.isPrettyFlow = true
    val yaml = Yaml(options)
    return yaml.dump(confObject)
}
