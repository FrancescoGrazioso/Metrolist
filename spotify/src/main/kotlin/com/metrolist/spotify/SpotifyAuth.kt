package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyInternalToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles Spotify authentication using the web player's internal token endpoint.
 * Uses sp_dc / sp_key cookies (extracted from WebView login) to obtain access tokens
 * without requiring a Spotify Developer Client ID.
 *
 * Uses [java.net.HttpURLConnection] (Android's built-in HTTP stack) instead of
 * OkHttp/Ktor because Spotify's Varnish CDN fingerprints TLS clients and blocks
 * requests from non-browser HTTP libraries.
 */
object SpotifyAuth {
    private const val TOKEN_URL = "https://open.spotify.com/get_access_token"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    const val LOGIN_CALLBACK_HOST = "open.spotify.com"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * Fetches an internal web-player access token using session cookies.
     * The sp_dc cookie is long-lived (~1 year) and the returned access token
     * is valid for ~1 hour with broader permissions than the official API token.
     */
    suspend fun fetchAccessToken(
        spDc: String,
        spKey: String = "",
    ): Result<SpotifyInternalToken> = runCatching {
        val cookieHeader = buildString {
            append("sp_dc=$spDc")
            if (spKey.isNotEmpty()) {
                append("; sp_key=$spKey")
            }
        }

        val url = URL("$TOKEN_URL?reason=transport&productType=web_player")
        val body = withContext(Dispatchers.IO) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.setRequestProperty("Accept-Language", "en")
                connection.setRequestProperty("Referer", "https://open.spotify.com/")
                connection.setRequestProperty("Sec-Fetch-Dest", "empty")
                connection.setRequestProperty("Sec-Fetch-Mode", "cors")
                connection.setRequestProperty("Sec-Fetch-Site", "same-origin")
                connection.setRequestProperty("Cookie", cookieHeader)

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw Spotify.SpotifyException(
                        responseCode,
                        "Token fetch failed (cookie may be expired): $errorBody",
                    )
                }

                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        val token = json.decodeFromString<SpotifyInternalToken>(body)

        if (token.isAnonymous || token.accessToken.isBlank()) {
            throw Spotify.SpotifyException(
                401,
                "Received anonymous token — sp_dc cookie is invalid or expired",
            )
        }

        token
    }
}
