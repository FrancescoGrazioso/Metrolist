/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Hybrid cache for Spotify user profile data (top tracks/artists).
 *
 * Resolves the 429 rate-limiting problem on the REST me/top/tracks and me/top/artists
 * endpoints by using a 3-tier fallback strategy:
 *   1. GQL endpoints (likedSongs, playlists) — never rate-limited
 *   2. REST endpoints (topTracks, topArtists) — best data but rate-limited
 *   3. Local DB (event/playCount tables) — always available after first use
 *
 * Results are cached in-memory with a configurable TTL and persisted to DataStore
 * so the app never shows an empty home screen.
 */
object SpotifyProfileCache {

    private const val TAG = "SpotifyProfileCache"
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000 // 6 hours
    private const val REST_COOLDOWN_MS = 5L * 60 * 1000 // 5 min cooldown after 429

    private val CACHE_KEY_TRACKS = stringPreferencesKey("spotify_profile_tracks_json")
    private val CACHE_KEY_ARTISTS = stringPreferencesKey("spotify_profile_artists_json")
    private val CACHE_KEY_TIMESTAMP = longPreferencesKey("spotify_profile_cache_ts")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshMutex = Mutex()

    @Volatile private var cachedTracks: List<SpotifyTrack> = emptyList()
    @Volatile private var cachedArtists: List<SpotifyArtist> = emptyList()
    @Volatile private var lastRefreshMs: Long = 0L
    @Volatile private var lastRestFailMs: Long = 0L

    @Serializable
    private data class CachedTrackList(val tracks: List<SpotifyTrack>)

    @Serializable
    private data class CachedArtistList(val artists: List<SpotifyArtist>)

    /**
     * Returns top tracks from the best available source.
     * Never throws — returns an empty list only if absolutely no data is available.
     */
    suspend fun getTopTracks(
        context: Context,
        database: MusicDatabase? = null,
        limit: Int = 50,
    ): List<SpotifyTrack> {
        ensureLoaded(context, database)
        return cachedTracks.take(limit)
    }

    /**
     * Returns top artists derived from the best available source.
     */
    suspend fun getTopArtists(
        context: Context,
        database: MusicDatabase? = null,
        limit: Int = 50,
    ): List<SpotifyArtist> {
        ensureLoaded(context, database)
        return cachedArtists.take(limit)
    }

    /**
     * Forces a cache refresh regardless of TTL.
     */
    suspend fun forceRefresh(context: Context, database: MusicDatabase? = null) {
        lastRefreshMs = 0L
        ensureLoaded(context, database)
    }

    fun invalidate() {
        lastRefreshMs = 0L
        cachedTracks = emptyList()
        cachedArtists = emptyList()
    }

    private suspend fun ensureLoaded(context: Context, database: MusicDatabase?) {
        if (System.currentTimeMillis() - lastRefreshMs < CACHE_TTL_MS &&
            cachedTracks.isNotEmpty()
        ) return

        refreshMutex.withLock {
            // Double-check after acquiring lock
            if (System.currentTimeMillis() - lastRefreshMs < CACHE_TTL_MS &&
                cachedTracks.isNotEmpty()
            ) return

            // Fast path: restore from DataStore first so UI has data immediately
            if (cachedTracks.isEmpty()) {
                restoreFromDataStore(context)
            }

            // If DataStore data is still fresh, no network call needed
            if (System.currentTimeMillis() - lastRefreshMs < CACHE_TTL_MS &&
                cachedTracks.isNotEmpty()
            ) return

            // Refresh from network sources in background
            refreshFromSources(context, database)
        }
    }

    /**
     * Tries to refresh profile from sources in priority order:
     * 1. GQL liked songs (always available, good proxy for preferences)
     * 2. REST top tracks/artists (best data, may 429)
     * 3. Local DB play history (always available after first use)
     */
    private suspend fun refreshFromSources(
        context: Context,
        database: MusicDatabase?,
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Refreshing profile from sources...")

        val tracks = mutableListOf<SpotifyTrack>()
        val artistFrequency = mutableMapOf<String, ArtistAccumulator>()

        // ── Tier 1: GQL liked songs (no rate limit) ──
        try {
            val likedResult = Spotify.likedSongs(limit = 100)
            likedResult.onSuccess { paging ->
                val likedTracks = paging.items.map { it.track }
                Timber.d("$TAG: GQL likedSongs returned ${likedTracks.size} tracks")
                tracks.addAll(likedTracks)

                for (track in likedTracks) {
                    for (artist in track.artists) {
                        val id = artist.id ?: continue
                        artistFrequency.getOrPut(id) {
                            ArtistAccumulator(id, artist.name)
                        }.count++
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: GQL likedSongs failed")
        }

        // ── Tier 2: REST top tracks/artists (may 429, skip if recently failed) ──
        val restAvailable = System.currentTimeMillis() - lastRestFailMs > REST_COOLDOWN_MS
        if (restAvailable) {
            try {
                val restTracks = Spotify.topTracks(timeRange = "short_term", limit = 50)
                restTracks.onSuccess { paging ->
                    Timber.d("$TAG: REST topTracks(short_term) returned ${paging.items.size} tracks")
                    // REST top tracks are higher signal — prepend them
                    val merged = paging.items + tracks.filter { t -> paging.items.none { it.id == t.id } }
                    tracks.clear()
                    tracks.addAll(merged)

                    for (track in paging.items) {
                        for (artist in track.artists) {
                            val id = artist.id ?: continue
                            artistFrequency.getOrPut(id) {
                                ArtistAccumulator(id, artist.name)
                            }.restBoost = true
                        }
                    }
                }.onFailure { e ->
                    if (e.message?.contains("429") == true || e.message?.contains("Rate limited") == true) {
                        Timber.w("$TAG: REST topTracks hit 429, entering cooldown")
                        lastRestFailMs = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: REST topTracks failed")
                if (e.message?.contains("429") == true) lastRestFailMs = System.currentTimeMillis()
            }

            if (System.currentTimeMillis() - lastRestFailMs > REST_COOLDOWN_MS) {
                try {
                    val restArtists = Spotify.topArtists(timeRange = "medium_term", limit = 50)
                    restArtists.onSuccess { paging ->
                        Timber.d("$TAG: REST topArtists returned ${paging.items.size} artists")
                        // REST artists have genre data — merge them in
                        for (artist in paging.items) {
                            val acc = artistFrequency.getOrPut(artist.id) {
                                ArtistAccumulator(artist.id, artist.name)
                            }
                            acc.restBoost = true
                            acc.genres = artist.genres
                            acc.images = artist.images
                        }
                    }.onFailure { e ->
                        if (e.message?.contains("429") == true || e.message?.contains("Rate limited") == true) {
                            Timber.w("$TAG: REST topArtists hit 429, entering cooldown")
                            lastRestFailMs = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: REST topArtists failed")
                    if (e.message?.contains("429") == true) lastRestFailMs = System.currentTimeMillis()
                }
            }
        } else {
            Timber.d("$TAG: Skipping REST calls — in cooldown (${REST_COOLDOWN_MS / 1000}s)")
        }

        // ── Tier 3: Local DB play history (always available) ──
        if (tracks.isEmpty() && database != null) {
            try {
                val fromTimestamp = LocalDateTime.now()
                    .minusMonths(3)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
                val localSongs = database.mostPlayedSongs(fromTimestamp, limit = 50).first()
                if (localSongs.isNotEmpty()) {
                    Timber.d("$TAG: Local DB returned ${localSongs.size} most played songs")
                    for (song in localSongs) {
                        tracks.add(
                            SpotifyTrack(
                                id = song.song.id,
                                name = song.song.title,
                                artists = song.artists.map {
                                    com.metrolist.spotify.models.SpotifySimpleArtist(
                                        id = it.id,
                                        name = it.name,
                                    )
                                },
                                durationMs = (song.song.duration * 1000),
                            )
                        )
                        for (artist in song.artists) {
                            artistFrequency.getOrPut(artist.id) {
                                ArtistAccumulator(artist.id, artist.name)
                            }.count++
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Local DB fallback failed")
            }
        }

        if (tracks.isEmpty()) {
            Timber.w("$TAG: No profile data from any source")
            return@withContext false
        }

        // Deduplicate tracks by ID, preserving order (first occurrence wins)
        val seenIds = mutableSetOf<String>()
        val dedupedTracks = tracks.filter { it.id.isNotEmpty() && seenIds.add(it.id) }

        // Build sorted artist list from accumulated data
        val sortedArtists = artistFrequency.values
            .sortedByDescending { acc ->
                var score = acc.count.toFloat()
                if (acc.restBoost) score *= 2f
                score
            }
            .map { acc ->
                SpotifyArtist(
                    id = acc.id,
                    name = acc.name,
                    images = acc.images,
                    genres = acc.genres,
                )
            }

        // Enrich top artists that have no images via GQL (not rate-limited)
        val enrichedArtists = sortedArtists.toMutableList()
        val artistsNeedingImages = enrichedArtists
            .take(10)
            .withIndex()
            .filter { it.value.images.isEmpty() }

        if (artistsNeedingImages.isNotEmpty()) {
            coroutineScope {
                val deferredResults = artistsNeedingImages.map { (idx, artist) ->
                    async {
                        val detail = try {
                            Spotify.artist(artist.id).getOrNull()
                        } catch (e: Exception) {
                            Timber.w(e, "$TAG: Failed to fetch images for artist ${artist.id}")
                            null
                        }
                        Pair(idx, detail)
                    }
                }
                deferredResults.awaitAll().forEach { (idx, detail) ->
                    if (detail != null && detail.images.isNotEmpty()) {
                        enrichedArtists[idx] = enrichedArtists[idx].copy(images = detail.images)
                    }
                }
            }
        }

        cachedTracks = dedupedTracks
        cachedArtists = enrichedArtists
        lastRefreshMs = System.currentTimeMillis()

        Timber.d("$TAG: Profile cached — ${dedupedTracks.size} tracks, ${enrichedArtists.size} artists")

        persistToDataStore(context)
        true
    }

    private suspend fun persistToDataStore(context: Context) {
        try {
            val tracksJson = json.encodeToString(CachedTrackList(cachedTracks.take(100)))
            val artistsJson = json.encodeToString(CachedArtistList(cachedArtists.take(50)))
            context.dataStore.edit { prefs ->
                prefs[CACHE_KEY_TRACKS] = tracksJson
                prefs[CACHE_KEY_ARTISTS] = artistsJson
                prefs[CACHE_KEY_TIMESTAMP] = System.currentTimeMillis()
            }
            Timber.d("$TAG: Profile persisted to DataStore")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to persist profile to DataStore")
        }
    }

    private suspend fun restoreFromDataStore(context: Context) {
        try {
            val prefs = context.dataStore.data.first()
            val tracksJson = prefs[CACHE_KEY_TRACKS] ?: return
            val artistsJson = prefs[CACHE_KEY_ARTISTS] ?: return
            val timestamp = prefs[CACHE_KEY_TIMESTAMP] ?: 0L

            val parsed = json.decodeFromString<CachedTrackList>(tracksJson)
            val parsedArtists = json.decodeFromString<CachedArtistList>(artistsJson)

            if (parsed.tracks.isNotEmpty()) {
                cachedTracks = parsed.tracks
                cachedArtists = parsedArtists.artists
                lastRefreshMs = timestamp
                Timber.d(
                    "$TAG: Restored from DataStore — ${cachedTracks.size} tracks, " +
                        "${cachedArtists.size} artists (age: ${(System.currentTimeMillis() - timestamp) / 60000}min)"
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to restore profile from DataStore")
        }
    }

    private data class ArtistAccumulator(
        val id: String,
        val name: String,
        var count: Int = 0,
        var restBoost: Boolean = false,
        var genres: List<String> = emptyList(),
        var images: List<SpotifyImage> = emptyList(),
    )
}
