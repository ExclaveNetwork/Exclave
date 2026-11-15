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

import com.google.gson.JsonParser.parseReader
import com.google.gson.stream.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.Project
import java.io.File
import java.io.StringReader
import java.security.MessageDigest

fun Project.downloadAssets(update: Boolean = false) {
    val assetsDir = File(projectDir, "src/main/assets")
    val downloader = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    val geoipFile = File(assetsDir, "exclave-core/geoip.dat")
    val geoipVersionFile = File(assetsDir, "exclave-core/geoip.version.txt")
    val geoipSha256sumFile = File(assetsDir, "exclave-core/geoip.sha256sum.txt")
    var geoipVersion: String
    var geoipDownloadURL: String
    val geoipSha256sum: ByteArray
    if (update) {
        val body = downloader.newCall(
            Request.Builder().url("https://api.github.com/repos/v2fly/geoip/releases/latest").build()
        ).execute().body
        val jsonObject = parseReader(JsonReader(StringReader(body.string())))?.asJsonObject
        body.close()
        jsonObject ?: error("unable to list geoip release")
        geoipVersion = jsonObject.get("tag_name")?.asString ?: error("unable to list geoip release")
        val assets = jsonObject.getAsJsonArray("assets")
        val geoipDat = assets.find { it?.asJsonObject?.get("name")?.asString == "geoip.dat" }
            ?.asJsonObject ?: error("unable to list geoip release")
        geoipDownloadURL = geoipDat.get("browser_download_url")?.asString
            ?: error("unable to list geoip release")
        geoipSha256sum = geoipDat.get("digest")?.asString?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")?.hexToByteArray()?.takeIf { it.size == 32 }
            ?: error("invalid geoip sha256sum")
    } else {
        geoipVersion = geoipVersionFile.readText()
        geoipDownloadURL = "https://github.com/v2fly/geoip/releases/download/${geoipVersion}/geoip.dat"
        geoipSha256sum = geoipSha256sumFile.readText().hexToByteArray().takeIf { it.size == 32 }
            ?: error("invalid geoip sha256sum")
    }

    var count = 0
    while (true) {
        count++

        println("Downloading $geoipDownloadURL ...")

        val body = downloader.newCall(Request.Builder().url(geoipDownloadURL).build()).execute().body
        body.byteStream().use {
            geoipFile.outputStream().use { out -> it.copyTo(out) }
        }

        val messageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        geoipFile.inputStream().use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                messageDigest.update(buffer, 0, bytesRead)
            }
        }
        body.close()

        val digest = messageDigest.digest()
        if (!digest.contentEquals(geoipSha256sum)) {
            System.err.println("${geoipFile.name} sha256sum mismatch")
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geoipVersionFile.writeText(geoipVersion)
            geoipSha256sumFile.writeText(digest.toHexString())
        }

        break
    }

    val geositeFile = File(assetsDir, "exclave-core/geosite.dat")
    val geositeVersionFile = File(assetsDir, "exclave-core/geosite.version.txt")
    val geositeSha256sumFile = File(assetsDir, "exclave-core/geosite.sha256sum.txt")
    var geositeVersion: String
    var geositeDownloadURL: String
    val geositeSha256sum: ByteArray
    if (update) {
        val body = downloader.newCall(
            Request.Builder().url("https://api.github.com/repos/v2fly/domain-list-community/releases/latest").build()
        ).execute().body
        val jsonObject = parseReader(JsonReader(StringReader(body.string())))?.asJsonObject
        body.close()
        jsonObject ?: error("unable to list geosite release")
        geositeVersion = jsonObject.get("tag_name")?.asString
            ?: error("unable to list geosite release")
        val assets = jsonObject.getAsJsonArray("assets")
        val geositeDat = assets.find { it?.asJsonObject?.get("name")?.asString == "dlc.dat" }
            ?.asJsonObject ?: error("unable to list geosite release")
        geositeDownloadURL = geositeDat.get("browser_download_url")?.asString
            ?: error("unable to list geosite release")
        geositeSha256sum = geositeDat.get("digest")?.asString?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")?.hexToByteArray()?.takeIf { it.size == 32 }
            ?: error("invalid geosite sha256sum")
    } else {
        geositeVersion = geositeVersionFile.readText()
        geositeDownloadURL = "https://github.com/v2fly/domain-list-community/releases/download/${geositeVersion}/dlc.dat"
        geositeSha256sum = geositeSha256sumFile.readText().hexToByteArray().takeIf { it.size == 32 }
            ?: error("invalid geosite sha256sum")
    }

    count = 0
    while (true) {
        count++

        println("Downloading $geositeDownloadURL ...")

        val body = downloader.newCall(Request.Builder().url(geositeDownloadURL).build()).execute().body
        body.byteStream().use {
            geositeFile.outputStream().use { out -> it.copyTo(out) }
        }

        val messageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        geositeFile.inputStream().use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                messageDigest.update(buffer, 0, bytesRead)
            }
        }
        body.close()

        val digest = messageDigest.digest()
        if (!digest.contentEquals(geositeSha256sum)) {
            System.err.println("${geositeFile.name} sha256sum mismatch")
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geositeVersionFile.writeText(geositeVersion)
            geositeSha256sumFile.writeText(digest.toHexString())
        }

        break
    }

}