/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val CACHE_TTL_FULL_MS = 6L * 60 * 60 * 1000 // 6h when REST data is available
    private const val CACHE_TTL_DEGRADED_MS = 30L * 60 * 1000 // 30min when only GQL data — retry sooner
    private const val REST_COOLDOWN_MS = 60L * 1000 // 1 min cooldown after 429
    private const val REST_CALL_TIMEOUT_MS = 8000L // Max wait for a single REST call
    private const val FOLLOWED_ARTISTS_TTL_MS = 12L * 60 * 60 * 1000 // 12h — followed artists change rarely

    private val CACHE_KEY_TRACKS = stringPreferencesKey("spotify_profile_tracks_json")
    private val CACHE_KEY_ARTISTS = stringPreferencesKey("spotify_profile_artists_json")
    private val CACHE_KEY_TIMESTAMP = longPreferencesKey("spotify_profile_cache_ts")
    private val CACHE_KEY_HAD_REST = booleanPreferencesKey("spotify_profile_had_rest")
    private val CACHE_KEY_FOLLOWED_ARTISTS = stringPreferencesKey("spotify_followed_artists_json")
    private val CACHE_KEY_FOLLOWED_TS = longPreferencesKey("spotify_followed_artists_ts")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshMutex = Mutex()

    @Volatile private var cachedTracks: List<SpotifyTrack> = emptyList()
    @Volatile private var cachedArtists: List<SpotifyArtist> = emptyList()
    @Volatile private var lastRefreshMs: Long = 0L
    @Volatile private var lastRestFailMs: Long = 0L
    @Volatile private var lastRefreshHadRest: Boolean = false

    private const val RELATED_ARTISTS_TTL_MS = 24L * 60 * 60 * 1000 // 24h — related artists are stable

    private val CACHE_KEY_RELATED_NAMES = stringPreferencesKey("spotify_related_artist_names_json")
    private val CACHE_KEY_RELATED_TS = longPreferencesKey("spotify_related_artist_names_ts")

    @Volatile private var cachedFollowedArtists: List<SpotifyArtist> = emptyList()
    @Volatile private var followedArtistsRefreshMs: Long = 0L
    private val followedArtistsMutex = Mutex()

    @Volatile private var cachedRelatedArtistNames: Set<String> = emptySet()
    @Volatile private var relatedArtistsRefreshMs: Long = 0L
    private val relatedArtistsMutex = Mutex()

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
     * Returns followed/library artists from Spotify via GQL libraryV3.
     * Fallback chain: GQL -> DataStore cache -> top artists (from profile cache).
     */
    suspend fun getFollowedArtists(
        context: Context,
        database: MusicDatabase? = null,
        limit: Int = 50,
    ): List<SpotifyArtist> {
        if (System.currentTimeMillis() - followedArtistsRefreshMs < FOLLOWED_ARTISTS_TTL_MS &&
            cachedFollowedArtists.isNotEmpty()
        ) return cachedFollowedArtists.take(limit)

        followedArtistsMutex.withLock {
            if (System.currentTimeMillis() - followedArtistsRefreshMs < FOLLOWED_ARTISTS_TTL_MS &&
                cachedFollowedArtists.isNotEmpty()
            ) return cachedFollowedArtists.take(limit)

            if (cachedFollowedArtists.isEmpty()) {
                restoreFollowedArtistsFromDataStore(context)
                if (System.currentTimeMillis() - followedArtistsRefreshMs < FOLLOWED_ARTISTS_TTL_MS &&
                    cachedFollowedArtists.isNotEmpty()
                ) return cachedFollowedArtists.take(limit)
            }

            try {
                val allArtists = mutableListOf<SpotifyArtist>()
                var offset = 0
                val pageSize = 50
                do {
                    val result = withContext(Dispatchers.IO) {
                        Spotify.myArtists(limit = pageSize, offset = offset)
                    }
                    val paging = result.getOrNull() ?: break
                    allArtists.addAll(paging.items)
                    offset += pageSize
                } while (allArtists.size < paging.total && paging.items.isNotEmpty())

                if (allArtists.isNotEmpty()) {
                    cachedFollowedArtists = allArtists
                    followedArtistsRefreshMs = System.currentTimeMillis()
                    persistFollowedArtistsToDataStore(context)
                    Timber.d("$TAG: Followed artists loaded — ${allArtists.size} artists from GQL")
                } else {
                    Timber.w("$TAG: GQL myArtists returned empty, falling back")
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: GQL myArtists failed, falling back")
            }

            // Fallback: if GQL failed and no cache, use top artists
            if (cachedFollowedArtists.isEmpty()) {
                Timber.d("$TAG: Falling back to top artists for followed artists")
                return getTopArtists(context, database, limit)
            }
        }
        return cachedFollowedArtists.take(limit)
    }

    private suspend fun persistFollowedArtistsToDataStore(context: Context) {
        try {
            val artistsJson = json.encodeToString(CachedArtistList(cachedFollowedArtists.take(200)))
            context.dataStore.edit { prefs ->
                prefs[CACHE_KEY_FOLLOWED_ARTISTS] = artistsJson
                prefs[CACHE_KEY_FOLLOWED_TS] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to persist followed artists")
        }
    }

    private suspend fun restoreFollowedArtistsFromDataStore(context: Context) {
        try {
            val prefs = context.dataStore.data.first()
            val artistsJson = prefs[CACHE_KEY_FOLLOWED_ARTISTS] ?: return
            val timestamp = prefs[CACHE_KEY_FOLLOWED_TS] ?: 0L
            val parsed = json.decodeFromString<CachedArtistList>(artistsJson)
            if (parsed.artists.isNotEmpty()) {
                cachedFollowedArtists = parsed.artists
                followedArtistsRefreshMs = timestamp
                Timber.d("$TAG: Restored followed artists from DataStore — ${parsed.artists.size} artists (age: ${(System.currentTimeMillis() - timestamp) / 60000}min)")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to restore followed artists from DataStore")
        }
    }

    /**
     * Returns a set of normalized artist names that are "related" to the user's
     * followed artists. Built from Spotify GQL queryArtistOverview relatedContent
     * for the top followed artists. Used to filter YouTube new releases for the
     * Discover tab.
     */
    suspend fun getRelatedArtistNames(
        context: Context,
        database: MusicDatabase? = null,
        seedLimit: Int = 10,
    ): Set<String> {
        if (System.currentTimeMillis() - relatedArtistsRefreshMs < RELATED_ARTISTS_TTL_MS &&
            cachedRelatedArtistNames.isNotEmpty()
        ) return cachedRelatedArtistNames

        relatedArtistsMutex.withLock {
            if (System.currentTimeMillis() - relatedArtistsRefreshMs < RELATED_ARTISTS_TTL_MS &&
                cachedRelatedArtistNames.isNotEmpty()
            ) return cachedRelatedArtistNames

            if (cachedRelatedArtistNames.isEmpty()) {
                restoreRelatedArtistNamesFromDataStore(context)
                if (System.currentTimeMillis() - relatedArtistsRefreshMs < RELATED_ARTISTS_TTL_MS &&
                    cachedRelatedArtistNames.isNotEmpty()
                ) return cachedRelatedArtistNames
            }

            try {
                val followed = getFollowedArtists(context, database, limit = seedLimit)
                if (followed.isEmpty()) return cachedRelatedArtistNames

                val allNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

                coroutineScope {
                    followed.take(seedLimit).map { artist ->
                        async(Dispatchers.IO) {
                            try {
                                Spotify.artistRelatedArtists(artist.id).onSuccess { related ->
                                    related.forEach { relArtist ->
                                        allNames.add(normalizeArtistName(relArtist.name))
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "$TAG: Failed to get related artists for ${artist.name}")
                            }
                        }
                    }.awaitAll()
                }

                if (allNames.isNotEmpty()) {
                    cachedRelatedArtistNames = allNames.toSet()
                    relatedArtistsRefreshMs = System.currentTimeMillis()
                    persistRelatedArtistNamesToDataStore(context)
                    Timber.d("$TAG: Related artist names cached — ${allNames.size} names from ${followed.take(seedLimit).size} seeds")
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to build related artist names cache")
            }
        }
        return cachedRelatedArtistNames
    }

    fun normalizeArtistName(name: String): String =
        name.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private suspend fun persistRelatedArtistNamesToDataStore(context: Context) {
        try {
            val namesJson = json.encodeToString(cachedRelatedArtistNames.toList())
            context.dataStore.edit { prefs ->
                prefs[CACHE_KEY_RELATED_NAMES] = namesJson
                prefs[CACHE_KEY_RELATED_TS] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to persist related artist names")
        }
    }

    private suspend fun restoreRelatedArtistNamesFromDataStore(context: Context) {
        try {
            val prefs = context.dataStore.data.first()
            val namesJson = prefs[CACHE_KEY_RELATED_NAMES] ?: return
            val timestamp = prefs[CACHE_KEY_RELATED_TS] ?: 0L
            val parsed = json.decodeFromString<List<String>>(namesJson)
            if (parsed.isNotEmpty()) {
                cachedRelatedArtistNames = parsed.toSet()
                relatedArtistsRefreshMs = timestamp
                Timber.d("$TAG: Restored related artist names from DataStore — ${parsed.size} names (age: ${(System.currentTimeMillis() - timestamp) / 60000}min)")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to restore related artist names from DataStore")
        }
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
        cachedFollowedArtists = emptyList()
        followedArtistsRefreshMs = 0L
        cachedRelatedArtistNames = emptySet()
        relatedArtistsRefreshMs = 0L
    }

    private fun effectiveTtl(): Long =
        if (lastRefreshHadRest) CACHE_TTL_FULL_MS else CACHE_TTL_DEGRADED_MS

    private suspend fun ensureLoaded(context: Context, database: MusicDatabase?) {
        if (System.currentTimeMillis() - lastRefreshMs < effectiveTtl() &&
            cachedTracks.isNotEmpty()
        ) return

        refreshMutex.withLock {
            // Double-check after acquiring lock
            if (System.currentTimeMillis() - lastRefreshMs < effectiveTtl() &&
                cachedTracks.isNotEmpty()
            ) return

            // Fast path: restore from DataStore first so UI has data immediately
            if (cachedTracks.isEmpty()) {
                restoreFromDataStore(context)
            }

            // If DataStore data is still fresh, no network call needed
            if (System.currentTimeMillis() - lastRefreshMs < effectiveTtl() &&
                cachedTracks.isNotEmpty()
            ) return

            // Refresh from network sources in background
            refreshFromSources(context, database)
        }
    }

    /**
     * Single-attempt REST call with timeout. Fail-fast on 429 — retrying is
     * counterproductive because the Spotify REST client has its own internal
     * retry/backoff that already consumes most of the timeout budget.
     */
    private suspend fun <T> singleRestCall(
        label: String,
        block: suspend () -> Result<T>,
    ): Result<T>? = try {
        withTimeoutOrNull(REST_CALL_TIMEOUT_MS) { block() }
    } catch (e: Exception) {
        Timber.w(e, "$TAG: $label failed")
        null
    }

    /**
     * Tries to refresh profile from sources in priority order:
     * 1. GQL liked songs (always available, good proxy for preferences)
     * 2. REST top tracks/artists (best data, may 429) — single attempt, fail-fast
     * 3. Local DB play history (used to reorder GQL data when REST fails)
     */
    private suspend fun refreshFromSources(
        context: Context,
        database: MusicDatabase?,
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.d("$TAG: Refreshing profile from sources...")

        val tracks = mutableListOf<SpotifyTrack>()
        val artistFrequency = mutableMapOf<String, ArtistAccumulator>()
        var restSucceeded = false

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
            val restTracksResult = singleRestCall("topTracks") {
                Spotify.topTracks(timeRange = "short_term", limit = 50)
            }

            if (restTracksResult != null) {
                restTracksResult.onSuccess { paging ->
                    Timber.d("$TAG: REST topTracks(short_term) returned ${paging.items.size} tracks")
                    val merged = paging.items + tracks.filter { t -> paging.items.none { it.id == t.id } }
                    tracks.clear()
                    tracks.addAll(merged)
                    restSucceeded = true

                    for (track in paging.items) {
                        for (artist in track.artists) {
                            val id = artist.id ?: continue
                            artistFrequency.getOrPut(id) {
                                ArtistAccumulator(id, artist.name)
                            }.restBoost = true
                        }
                    }
                }.onFailure {
                    Timber.w("$TAG: REST topTracks hit 429/error, entering cooldown")
                    lastRestFailMs = System.currentTimeMillis()
                }
            } else {
                Timber.w("$TAG: REST topTracks timed out, entering cooldown")
                lastRestFailMs = System.currentTimeMillis()
            }

            if (System.currentTimeMillis() - lastRestFailMs > REST_COOLDOWN_MS) {
                val restArtistsResult = singleRestCall("topArtists") {
                    Spotify.topArtists(timeRange = "medium_term", limit = 50)
                }

                if (restArtistsResult != null) {
                    restArtistsResult.onSuccess { paging ->
                        Timber.d("$TAG: REST topArtists returned ${paging.items.size} artists")
                        for (artist in paging.items) {
                            val acc = artistFrequency.getOrPut(artist.id) {
                                ArtistAccumulator(artist.id, artist.name)
                            }
                            acc.restBoost = true
                            acc.genres = artist.genres
                            acc.images = artist.images
                        }
                    }.onFailure {
                        Timber.w("$TAG: REST topArtists hit 429/error, entering cooldown")
                        lastRestFailMs = System.currentTimeMillis()
                    }
                } else {
                    Timber.w("$TAG: REST topArtists timed out, entering cooldown")
                    lastRestFailMs = System.currentTimeMillis()
                }
            }
        } else {
            Timber.d("$TAG: Skipping REST calls — in cooldown (${REST_COOLDOWN_MS / 1000}s)")
        }

        // ── Tier 3: Local DB play history ──
        // When REST failed but GQL liked songs are available, use local play data
        // to reorder tracks by actual listening frequency instead of liked-date order.
        // When tracks are completely empty, use local data as the sole source.
        if (database != null) {
            try {
                val fromTimestamp = LocalDateTime.now()
                    .minusMonths(3)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
                val localSongs = database.mostPlayedSongs(fromTimestamp, limit = 100).first()

                if (tracks.isEmpty() && localSongs.isNotEmpty()) {
                    Timber.d("$TAG: No GQL/REST data — using ${localSongs.size} local songs as sole source")
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
                } else if (!restSucceeded && tracks.isNotEmpty() && localSongs.isNotEmpty()) {
                    Timber.d("$TAG: REST failed — reordering ${tracks.size} GQL tracks using ${localSongs.size} local play ranks")
                    val localRank = localSongs.withIndex().associate { (idx, song) -> song.song.id to idx }
                    tracks.sortBy { track -> localRank[track.id] ?: Int.MAX_VALUE }
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
        lastRefreshHadRest = restSucceeded

        val quality = if (restSucceeded) "full (REST)" else "degraded (GQL-only, TTL=${CACHE_TTL_DEGRADED_MS / 60000}min)"
        Timber.d("$TAG: Profile cached — ${dedupedTracks.size} tracks, ${enrichedArtists.size} artists [$quality]")

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
                prefs[CACHE_KEY_HAD_REST] = lastRefreshHadRest
            }
            Timber.d("$TAG: Profile persisted to DataStore (hadRest=$lastRefreshHadRest)")
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
            val hadRest = prefs[CACHE_KEY_HAD_REST] ?: false

            val parsed = json.decodeFromString<CachedTrackList>(tracksJson)
            val parsedArtists = json.decodeFromString<CachedArtistList>(artistsJson)

            if (parsed.tracks.isNotEmpty()) {
                cachedTracks = parsed.tracks
                cachedArtists = parsedArtists.artists
                lastRefreshMs = timestamp
                lastRefreshHadRest = hadRest
                Timber.d(
                    "$TAG: Restored from DataStore — ${cachedTracks.size} tracks, " +
                        "${cachedArtists.size} artists (age: ${(System.currentTimeMillis() - timestamp) / 60000}min, hadRest=$hadRest)"
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
