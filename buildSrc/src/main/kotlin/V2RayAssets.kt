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

import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.Project
import org.kohsuke.github.GitHubBuilder
import java.io.File
import java.security.MessageDigest

fun Project.downloadAssets(update: Boolean) {
    val assetsDir = File(projectDir, "src/main/assets")
    val downloader = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    val github = GitHubBuilder().build()

    val geoipVersion = File(assetsDir, "v2ray/geoip.version.txt")
    val geoipRelease = if (update) {
        github.getRepository("v2fly/geoip").latestRelease
    } else {
        github.getRepository("v2fly/geoip").listReleases().find {
            it.tagName == geoipVersion.readText()
        }
    } ?: error("unable to list geoip release")

    val geoipFile = File(assetsDir, "v2ray/geoip.dat")

    if (update) {
        geoipVersion.deleteRecursively()
    }
    geoipFile.parentFile.mkdirs()

    val geoipDat = (geoipRelease.listAssets().toSet().find { it.name == "geoip.dat" }
        ?: error("geoip.dat not found in ${geoipRelease.assetsUrl}")).browserDownloadUrl

    val geoipDatSha256sum = (geoipRelease.listAssets().toSet().find { it.name == "geoip.dat.sha256sum" }
        ?: error("geoip.dat.sha256sum not found in ${geoipRelease.assetsUrl}")).browserDownloadUrl

    println("Downloading $geoipDatSha256sum ...")

    val geoipChecksum = downloader.newCall(
        Request.Builder().url(geoipDatSha256sum).build()
    ).execute().body.string().trim().substringBefore(" ")

    var count = 0

    while (true) {
        count++

        println("Downloading $geoipDat ...")

        downloader.newCall(
            Request.Builder().url(geoipDat).build()
        ).execute().body.byteStream().use {
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
        val fileSha256 = messageDigest.digest().joinToString("") { "%02x".format(it) }

        if (fileSha256 != geoipChecksum.lowercase()) {
            System.err.println(
                "Error verifying ${geoipFile.name}: \nLocal: $fileSha256\nRemote: ${geoipChecksum.lowercase()}"
            )
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geoipVersion.writeText(geoipRelease.tagName)
        }
        break
    }

    val geositeVersion = File(assetsDir, "v2ray/geosite.version.txt")
    val geositeRelease = if (update) {
        github.getRepository("v2fly/domain-list-community").latestRelease
    } else {
        github.getRepository("v2fly/domain-list-community").listReleases().find {
            it.tagName == geositeVersion.readText()
        }
    } ?: error("unable to list geosite release")

    val geositeFile = File(assetsDir, "v2ray/geosite.dat")

    if (update) {
        geositeVersion.deleteRecursively()
    }

    val geositeDat = (geositeRelease.listAssets().toSet().find { it.name == "dlc.dat" }
        ?: error("dlc.dat not found in ${geositeRelease.assetsUrl}")).browserDownloadUrl

    val geositeDatSha256sum = (geositeRelease.listAssets().toSet().find { it.name == "dlc.dat.sha256sum" }
        ?: error("dlc.dat.sha256sum not found in ${geositeRelease.assetsUrl}")).browserDownloadUrl

    println("Downloading $geositeDatSha256sum ...")

    val geositeChecksum = downloader.newCall(
        Request.Builder().url(geositeDatSha256sum).build()
    ).execute().body.string().trim().substringBefore(" ")

    count = 0

    while (true) {
        count++

        println("Downloading $geositeDat ...")

        downloader.newCall(
            Request.Builder().url(geositeDat).build()
        ).execute().body.byteStream().use {
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
        val fileSha256 = messageDigest.digest().joinToString("") { "%02x".format(it) }

        if (fileSha256 != geositeChecksum.lowercase()) {
            System.err.println(
                "Error verifying ${geositeFile.name}: \nLocal: $fileSha256\nRemote: ${geositeChecksum.lowercase()}"
            )
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geositeVersion.writeText(geositeRelease.tagName)
        }
        break
    }

}