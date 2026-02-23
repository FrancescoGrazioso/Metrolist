package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifyRecommendations
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifySearchResult
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.spotify.models.SpotifyUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Spotify Web API client for retrieving user data (playlists, tracks, recommendations).
 * Uses an internal web-player access token obtained via sp_dc cookie authentication.
 */
object Spotify {
    @Volatile
    var accessToken: String? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                url("https://api.spotify.com/v1/")
            }
            expectSuccess = false
        }
    }

    class SpotifyException(val statusCode: Int, override val message: String) : Exception(message)

    @Volatile
    var logger: ((level: String, message: String) -> Unit)? = null

    private fun log(level: String, message: String) {
        logger?.invoke(level, message)
    }

    private suspend inline fun <reified T> authenticatedGet(
        endpoint: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val token = accessToken ?: throw SpotifyException(401, "Not authenticated").also {
            log("E", "API call $endpoint - NOT AUTHENTICATED (no token)")
        }
        log("D", "API GET $endpoint (token: ${token.take(8)}...)")
        val response = client.get(endpoint) {
            header("Authorization", "Bearer $token")
            block()
        }
        log("D", "API GET $endpoint -> ${response.status.value}")
        if (response.status == HttpStatusCode.Unauthorized) {
            throw SpotifyException(401, "Token expired or invalid")
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            log("E", "API GET $endpoint FAILED: ${response.status.value} - ${body.take(200)}")
            throw SpotifyException(response.status.value, "Spotify API error ${response.status.value}: $body")
        }
        return response.body()
    }

    // --- User Profile ---

    suspend fun me(): Result<SpotifyUser> = runCatching {
        authenticatedGet("me")
    }

    // --- Playlists ---

    suspend fun myPlaylists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylist>> = runCatching {
        authenticatedGet("me/playlists") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    suspend fun playlistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylistTrack>> = runCatching {
        authenticatedGet("playlists/$playlistId/tracks") {
            parameter("limit", limit)
            parameter("offset", offset)
            parameter("fields", "items(added_at,is_local,track(id,name,artists(id,name),album(id,name,images),duration_ms,explicit,uri,popularity)),total,limit,offset,next")
        }
    }

    suspend fun playlist(playlistId: String): Result<SpotifyPlaylist> = runCatching {
        authenticatedGet("playlists/$playlistId")
    }

    // --- Liked Songs ---

    suspend fun likedSongs(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifySavedTrack>> = runCatching {
        authenticatedGet("me/tracks") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    // --- Top Tracks ---

    suspend fun topTracks(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyTrack>> = runCatching {
        authenticatedGet("me/top/tracks") {
            parameter("time_range", timeRange)
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    // --- Top Artists ---

    suspend fun topArtists(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyArtist>> = runCatching {
        authenticatedGet("me/top/artists") {
            parameter("time_range", timeRange)
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    // --- Recommendations ---

    suspend fun recommendations(
        seedTrackIds: List<String> = emptyList(),
        seedArtistIds: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 50,
    ): Result<SpotifyRecommendations> = runCatching {
        authenticatedGet("recommendations") {
            if (seedTrackIds.isNotEmpty()) parameter("seed_tracks", seedTrackIds.joinToString(","))
            if (seedArtistIds.isNotEmpty()) parameter("seed_artists", seedArtistIds.joinToString(","))
            if (seedGenres.isNotEmpty()) parameter("seed_genres", seedGenres.joinToString(","))
            parameter("limit", limit)
        }
    }

    // --- Search ---

    /**
     * Search uses manual JSON parsing to handle null items in paging arrays.
     * The Spotify search API can return null elements for deleted/unavailable content,
     * which would cause deserialization failures with standard typed parsing.
     *
     * If the API enforces a per-request limit lower than [limit] (e.g. 10 for
     * Development Mode apps), this function transparently paginates to fulfill
     * the full requested amount.
     */
    suspend fun search(
        query: String,
        types: List<String> = listOf("track"),
        limit: Int = 20,
        offset: Int = 0,
    ): Result<SpotifySearchResult> = runCatching {
        val firstPage = executeSearch(query, types, limit, offset)

        val trackItems = firstPage.tracks?.items.orEmpty().toMutableList()
        val totalAvailable = firstPage.tracks?.total ?: 0
        var nextOffset = offset + trackItems.size

        while (trackItems.size < limit && nextOffset < totalAvailable && trackItems.size < totalAvailable) {
            val page = executeSearch(query, types, limit - trackItems.size, nextOffset)
            val newItems = page.tracks?.items.orEmpty()
            if (newItems.isEmpty()) break
            trackItems.addAll(newItems)
            nextOffset += newItems.size
        }

        SpotifySearchResult(
            tracks = firstPage.tracks?.copy(items = trackItems),
        )
    }

    private suspend fun executeSearch(
        query: String,
        types: List<String>,
        limit: Int,
        offset: Int,
    ): SpotifySearchResult {
        val token = accessToken ?: throw SpotifyException(401, "Not authenticated")
        log("D", "API GET search (token: ${token.take(8)}...)")
        val response = client.get("search") {
            header("Authorization", "Bearer $token")
            parameter("q", query)
            parameter("type", types.joinToString(","))
            parameter("limit", limit)
            parameter("offset", offset)
        }
        log("D", "API GET search -> ${response.status.value}")
        if (response.status == HttpStatusCode.Unauthorized) {
            throw SpotifyException(401, "Token expired or invalid")
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            log("E", "API GET search FAILED: ${response.status.value} - ${body.take(200)}")
            throw SpotifyException(response.status.value, "Spotify API error ${response.status.value}: $body")
        }

        val rawJson = response.bodyAsText()
        val sanitized = sanitizeSearchResponse(rawJson)
        return json.decodeFromString<SpotifySearchResult>(sanitized)
    }

    /**
     * Removes null elements from all "items" arrays in the search response JSON.
     * Spotify can return null entries for deleted/unavailable content.
     */
    private fun sanitizeSearchResponse(rawJson: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val cleaned = buildJsonObject {
            for ((key, value) in root) {
                if (value is JsonObject) {
                    put(key, sanitizePagingObject(value))
                } else {
                    put(key, value)
                }
            }
        }
        return cleaned.toString()
    }

    private fun sanitizePagingObject(paging: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in paging) {
                if (key == "items" && value is JsonArray) {
                    put(key, JsonArray(value.filterNot { it is JsonNull }))
                } else {
                    put(key, value)
                }
            }
        }
    }

    // --- Browse ---

    suspend fun newReleases(
        limit: Int = 20,
        offset: Int = 0,
    ): Result<NewReleasesResponse> = runCatching {
        authenticatedGet("browse/new-releases") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
    }

    // --- Albums ---

    suspend fun album(albumId: String): Result<SpotifyAlbum> = runCatching {
        authenticatedGet("albums/$albumId")
    }

    // --- Artists ---

    suspend fun artist(artistId: String): Result<SpotifyArtist> = runCatching {
        authenticatedGet("artists/$artistId")
    }

    suspend fun artistTopTracks(
        artistId: String,
        market: String = "US",
    ): Result<ArtistTopTracksResponse> = runCatching {
        authenticatedGet("artists/$artistId/top-tracks") {
            parameter("market", market)
        }
    }

    suspend fun relatedArtists(artistId: String): Result<RelatedArtistsResponse> = runCatching {
        authenticatedGet("artists/$artistId/related-artists")
    }

    fun isAuthenticated(): Boolean = accessToken != null
}

@kotlinx.serialization.Serializable
data class ArtistTopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class RelatedArtistsResponse(
    val artists: List<SpotifyArtist> = emptyList(),
)

@kotlinx.serialization.Serializable
data class NewReleasesResponse(
    val albums: SpotifyPaging<SpotifyAlbum>? = null,
)
