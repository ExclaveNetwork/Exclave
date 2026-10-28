package io.nekohasekai.sagernet.ui

import android.os.Build
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class AvailableAppUpdate(
    val version: String,
    val prerelease: Boolean,
    val downloadUrl: String,
)

object ReleaseUpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/denzikplay48-dot/ExclaveD/releases?per_page=100"

    suspend fun findAvailableUpdate(): AvailableAppUpdate? = withContext(Dispatchers.IO) {
        val preferences = DataStore.configurationStore
        val includeStable = preferences.getBoolean(Key.CHECK_RELEASE_UPDATES) ?: true
        val includePrerelease = preferences.getBoolean(Key.CHECK_PRERELEASE_UPDATES) ?: false
        if (!includeStable && !includePrerelease) return@withContext null

        val channelPrefix = if (BuildConfig.FLAVOR == "clone") "test-v" else "main-v"
        val appName = when (BuildConfig.FLAVOR) {
            "clone" -> "TestExclaveD"
            "lite" -> "LiteExclaveD"
            else -> "ExclaveD"
        }
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ExclaveD-${BuildConfig.FLAVOR}/${BuildConfig.VERSION_NAME}")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            val tagPattern = Regex("^${Regex.escape(channelPrefix)}(\\d+(?:\\.\\d+)*)(?:[-+].*)?$")
            val candidates = (0 until releases.length()).mapNotNull { index ->
                val release = releases.optJSONObject(index) ?: return@mapNotNull null
                if (release.optBoolean("draft", false)) return@mapNotNull null
                val prerelease = release.optBoolean("prerelease", false)
                if (prerelease && !includePrerelease || !prerelease && !includeStable) {
                    return@mapNotNull null
                }
                val version = tagPattern.matchEntire(release.optString("tag_name"))
                    ?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return@mapNotNull null

                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val asset = Build.SUPPORTED_ABIS.asSequence()
                    .mapNotNull { abi ->
                        (0 until assets.length())
                            .mapNotNull(assets::optJSONObject)
                            .firstOrNull { candidate ->
                                val name = candidate.optString("name")
                                name.startsWith("$appName-") && name.endsWith("-$abi.apk")
                            }
                    }
                    .firstOrNull() ?: return@mapNotNull null

                AvailableAppUpdate(
                    version = version,
                    prerelease = prerelease,
                    downloadUrl = asset.optString("browser_download_url").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null,
                )
            }

            candidates.maxWithOrNull { left, right ->
                compareVersions(left.version, right.version)
                    .takeIf { it != 0 }
                    ?: right.prerelease.compareTo(left.prerelease)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = Regex("\\d+").findAll(left).map { it.value.toLongOrNull() ?: 0L }.toList()
        val rightParts = Regex("\\d+").findAll(right).map { it.value.toLongOrNull() ?: 0L }.toList()
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0L })
                .compareTo(rightParts.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
