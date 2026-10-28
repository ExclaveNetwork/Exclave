package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale

object ExitIpGeoChecker {
    private const val GEOIP_URL = "https://api.ip.sb/geoip"
    private const val TIMEOUT_MILLIS = 4_000

    suspend fun countryCode(proxy: Proxy? = null): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val openedConnection = (if (proxy == null) {
                URL(GEOIP_URL).openConnection()
            } else {
                URL(GEOIP_URL).openConnection(proxy)
            }) as? HttpURLConnection ?: return@withContext null
            connection = openedConnection
            openedConnection.instanceFollowRedirects = true
            openedConnection.connectTimeout = TIMEOUT_MILLIS
            openedConnection.readTimeout = TIMEOUT_MILLIS
            openedConnection.setRequestProperty("User-Agent", "ExclaveD/exit-ip-check")
            if (openedConnection.responseCode !in 200..299) return@withContext null

            val body = openedConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = JSONObject(body)
            if (response.optString("ip").isBlank()) return@withContext null
            val code = response.optString("country_code").trim().uppercase(Locale.ROOT)
            if (code in Locale.getISOCountries()) {
                code
            } else if (response.optString("country").trim().equals("Russia", ignoreCase = true) ||
                response.optString("country").trim().equals("Russian Federation", ignoreCase = true)
            ) {
                "RU"
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
