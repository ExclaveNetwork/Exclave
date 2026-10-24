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

package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getArray
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.parseJson
import libsagernetcore.Libsagernetcore
import java.io.File

object RouteAssetUpdater {

    val internalFiles = arrayOf("geoip.dat", "geosite.dat")

    suspend fun updateInternalAssetsOnceIfNeeded() {
        if (DataStore.routeAssetsInitialUpdateDone) return

        copyBundledInternalAssetsIfMissing()

        val files = internalFiles.map { File(app.externalAssets, it) }
        if (files.all { it.isFile && it.length() > 0L }) {
            DataStore.routeAssetsInitialUpdateDone = true
            return
        }

        runCatching {
            files.forEach { file ->
                val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")
                val localVersion = if (file.isFile && versionFile.isFile) {
                    versionFile.readText().trim()
                } else {
                    ""
                }
                updateAsset(file, versionFile, localVersion)
            }
            if (files.all { it.isFile && it.length() > 0L }) {
                DataStore.routeAssetsInitialUpdateDone = true
            }
        }.onFailure {
            Logs.w(it)
        }
    }

    fun copyBundledInternalAssetsIfMissing() {
        internalFiles.forEach { fileName ->
            copyBundledAssetIfMissing(fileName)
            copyBundledAssetIfMissing("${fileName.substringBeforeLast('.')}.version.txt")
        }
    }

    private fun copyBundledAssetIfMissing(fileName: String) {
        val outFile = File(app.externalAssets, fileName)
        if (outFile.isFile && outFile.length() > 0L) return

        runCatching {
            app.assets.open("v2ray/$fileName").use { input ->
                val cacheFile = File(outFile.parentFile, "$fileName.tmp")
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                if (outFile.exists()) outFile.delete()
                if (!cacheFile.renameTo(outFile)) {
                    cacheFile.copyTo(outFile, overwrite = true)
                    cacheFile.delete()
                }
            }
        }.onFailure {
            Logs.w(it)
        }
    }

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String): Boolean {
        val repo: String
        var fileName = file.name
        when (DataStore.rulesProvider) {
            3 -> return updateGeoAsset(file, versionFile)
            0 -> {
                if (file.name == internalFiles[0]) {
                    repo = "v2fly/geoip"
                } else {
                    repo = "v2fly/domain-list-community"
                    fileName = "dlc.dat"
                }
            }
            1 -> repo = "Loyalsoldier/v2ray-rules-dat"
            2 -> repo = "Chocolate4U/Iran-v2ray-rules"
            4 -> repo = "runetfreedom/russia-v2ray-rules-dat"
            else -> error("invalid asset provider")
        }

        val client = Libsagernetcore.newHttpClient().apply {
            keepAlive()
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = parseJson(response.contentString).asJsonObject
            val tagName = release.getString("tag_name")
                ?: error("tag_name not found in release ${release["url"]}")

            if (tagName == localVersion) return false

            val releaseAssets = release.getArray("assets")
            val assetToDownload = releaseAssets?.find { it.getString("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getString("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            cacheFile.renameTo(file)
            versionFile.writeText(tagName)
            return true
        } finally {
            client.close()
        }
    }

    suspend fun updateGeoAsset(file: File, versionFile: File): Boolean {
        try {
            updateCustomAsset(
                file,
                if (file.name == internalFiles[0]) DataStore.rulesGeoipUrl else DataStore.rulesGeositeUrl
            )
            return true
        } finally {
            if (versionFile.isFile) {
                versionFile.delete()
            }
        }
    }

    suspend fun updateCustomAsset(file: File, url: String): Boolean {
        val client = Libsagernetcore.newHttpClient().apply {
            keepAlive()
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }
        try {
            val response = client.newRequest().apply {
                setURL(url)
            }.execute()
            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            cacheFile.renameTo(file)
            return true
        } finally {
            client.close()
        }
    }
}
