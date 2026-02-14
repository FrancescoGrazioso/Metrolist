package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyToken
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles Spotify OAuth2 Authorization Code flow with PKCE (Proof Key for Code Exchange).
 * This eliminates the need for a client secret, making it safe for public clients (mobile apps).
 */
object SpotifyAuth {
    private var clientId: String = ""

    private const val REDIRECT_URI = "metrolist://spotify/callback"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    // Scopes required for reading user data (no playback control needed)
    private val SCOPES = listOf(
        "user-read-private",
        "user-read-email",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-top-read",
    )

    private var codeVerifier: String? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    fun initialize(clientId: String) {
        this.clientId = clientId
    }

    fun isInitialized(): Boolean = clientId.isNotEmpty()

    /**
     * Generates the authorization URL that should be loaded in a WebView.
     * Also generates and stores the PKCE code verifier internally.
     */
    fun getAuthorizationUrl(): String {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        return buildString {
            append(AUTH_URL)
            append("?client_id=$clientId")
            append("&response_type=code")
            append("&redirect_uri=$REDIRECT_URI")
            append("&scope=${SCOPES.joinToString(" ")}")
            append("&code_challenge_method=S256")
            append("&code_challenge=$challenge")
            append("&show_dialog=true")
        }
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     */
    suspend fun exchangeCodeForToken(code: String): Result<SpotifyToken> = runCatching {
        val verifier = codeVerifier ?: throw IllegalStateException("No code verifier available")

        val response = client.post(TOKEN_URL) {
            setBody(FormDataContent(Parameters.build {
                append("client_id", clientId)
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", REDIRECT_URI)
                append("code_verifier", verifier)
            }))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            throw Spotify.SpotifyException(
                response.status.value,
                "Token exchange failed: $errorBody"
            )
        }

        val token: SpotifyToken = response.body()
        codeVerifier = null
        token
    }

    /**
     * Refreshes the access token using a refresh token.
     */
    suspend fun refreshToken(refreshToken: String): Result<SpotifyToken> = runCatching {
        val response = client.post(TOKEN_URL) {
            setBody(FormDataContent(Parameters.build {
                append("client_id", clientId)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            throw Spotify.SpotifyException(
                response.status.value,
                "Token refresh failed: $errorBody"
            )
        }

        response.body()
    }

    fun getRedirectUri(): String = REDIRECT_URI

    /**
     * Generates a random 128-character code verifier for PKCE.
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Creates a SHA-256 code challenge from the code verifier.
     */
    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
