/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Recommendation engine that builds personalized queues using Spotify user data.
 *
 * Since the Spotify recommendations and related-artists endpoints are deprecated
 * for development-mode apps (fuck you spotify), this engine acts as a lightweight recommender by
 * combining the user's listening profile with seed-track context.
 *
 * Algorithm overview:
 * 1. **User taste profile** — top tracks/artists across 3 time ranges
 *    (short_term, medium_term, long_term), weighted by recency.
 * 2. **Artist affinity map** — position-weighted score per artist, normalized [0,1].
 * 3. **Genre graph** — artist genres from Spotify metadata used to approximate
 *    artist similarity without the deprecated related-artists endpoint.
 * 4. **Candidate generation** — 4 sources: seed artist top tracks, same album,
 *    genre-neighbor artist top tracks, user top tracks pool.
 * 5. **Composite scoring** — source relevance, artist affinity, popularity
 *    similarity, and recency boost.
 * 6. **Diversification** — per-artist cap and bucket interleaving for variety.
 */
object SpotifyRecommendationEngine {

    private const val PROFILE_TTL_MS = 30L * 60 * 1000 // 30 minutes
    private const val MAX_TRACKS_PER_ARTIST = 3
    private const val TAG = "SpotifyRecEngine"

    // Scoring weights
    private const val W_SOURCE = 0.25f
    private const val W_AFFINITY = 0.30f
    private const val W_GENRE = 0.20f
    private const val W_POPULARITY = 0.10f
    private const val W_RECENCY = 0.15f

    // Cached user profile
    @Volatile
    private var artistAffinityMap: Map<String, Float> = emptyMap()
    @Volatile
    private var artistGenreMap: Map<String, Set<String>> = emptyMap()
    @Volatile
    private var topTrackPool: List<WeightedTrack> = emptyList()
    @Volatile
    private var shortTermArtistIds: Set<String> = emptySet()
    @Volatile
    private var lastProfileRefresh: Long = 0L

    /**
     * A track annotated with its time-range weight for scoring.
     */
    private data class WeightedTrack(
        val track: SpotifyTrack,
        val timeWeight: Float,
    )

    /**
     * Candidate bucket — describes the source of each candidate track,
     * used for interleaving and source-relevance scoring.
     */
    private enum class Bucket(val sourceScore: Float) {
        SEED_ARTIST(1.0f),
        SAME_ALBUM(0.85f),
        GENRE_NEIGHBOR(0.65f),
        USER_TOP(0.45f),
    }

    /**
     * A candidate track with all the metadata needed for scoring.
     */
    private data class ScoredCandidate(
        val track: SpotifyTrack,
        val bucket: Bucket,
        val sourceScore: Float,
        val artistAffinity: Float,
        val genreOverlap: Float,
        val popularitySimilarity: Float,
        val recencyBoost: Float,
    ) {
        val finalScore: Float
            get() = (W_SOURCE * sourceScore) +
                (W_AFFINITY * artistAffinity) +
                (W_GENRE * genreOverlap) +
                (W_POPULARITY * popularitySimilarity) +
                (W_RECENCY * recencyBoost)
    }

    /**
     * Builds or refreshes the user taste profile from Spotify top tracks/artists.
     * This is the foundation of all recommendations — it captures what the user
     * likes, weighted by recency (short_term 3x > medium_term 2x > long_term 1x).
     *
     * The profile is cached for [PROFILE_TTL_MS] to avoid excessive API calls.
     */
    suspend fun ensureProfileLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastProfileRefresh < PROFILE_TTL_MS &&
            artistAffinityMap.isNotEmpty()
        ) {
            return@withContext true
        }

        Timber.d("$TAG: Building user taste profile...")

        try {
            // Parallel fetch: 3 time ranges for tracks + 3 for artists = 6 calls
            val (tracks, artists) = coroutineScope {
                val trackDeferred = listOf("short_term", "medium_term", "long_term").map { range ->
                    async {
                        range to (Spotify.topTracks(range, limit = 50).getOrNull()?.items
                            ?: emptyList())
                    }
                }
                val artistDeferred = listOf("short_term", "medium_term", "long_term").map { range ->
                    async {
                        range to (Spotify.topArtists(range, limit = 50).getOrNull()?.items
                            ?: emptyList())
                    }
                }
                trackDeferred.awaitAll() to artistDeferred.awaitAll()
            }

            val tracksByRange = tracks.toMap()
            val artistsByRange = artists.toMap()

            // Build artist affinity map: position-weighted, time-weighted scores
            val affinityBuilder = mutableMapOf<String, Float>()
            val genreBuilder = mutableMapOf<String, MutableSet<String>>()
            val timeWeights = mapOf("short_term" to 3.0f, "medium_term" to 2.0f, "long_term" to 1.0f)

            for ((range, artistList) in artistsByRange) {
                val weight = timeWeights[range] ?: 1.0f
                for ((index, artist) in artistList.withIndex()) {
                    if (artist.id.isEmpty()) continue
                    // Position score: #1 gets 1.0, last gets ~0.02
                    val positionScore = 1.0f - (index.toFloat() / artistList.size.coerceAtLeast(1))
                    affinityBuilder[artist.id] =
                        (affinityBuilder[artist.id] ?: 0f) + (positionScore * weight)

                    // Collect genres per artist
                    if (artist.genres.isNotEmpty()) {
                        genreBuilder.getOrPut(artist.id) { mutableSetOf() }
                            .addAll(artist.genres)
                    }
                }
            }

            // Also add affinity from top tracks' artists (weaker signal but useful for
            // artists that appear in tracks but not in the top artists list)
            for ((range, trackList) in tracksByRange) {
                val weight = (timeWeights[range] ?: 1.0f) * 0.5f
                for ((index, track) in trackList.withIndex()) {
                    val positionScore = 1.0f - (index.toFloat() / trackList.size.coerceAtLeast(1))
                    for (artist in track.artists) {
                        val artistId = artist.id ?: continue
                        affinityBuilder[artistId] =
                            (affinityBuilder[artistId] ?: 0f) + (positionScore * weight)
                    }
                }
            }

            // Normalize affinity to [0, 1]
            val maxAffinity = affinityBuilder.values.maxOrNull() ?: 1f
            val normalizedAffinity = if (maxAffinity > 0f) {
                affinityBuilder.mapValues { it.value / maxAffinity }
            } else {
                affinityBuilder
            }

            // Build deduplicated top track pool with time weights
            val seenTrackIds = mutableSetOf<String>()
            val trackPool = mutableListOf<WeightedTrack>()
            for (range in listOf("short_term", "medium_term", "long_term")) {
                val weight = timeWeights[range] ?: 1.0f
                for (track in tracksByRange[range].orEmpty()) {
                    if (track.id.isNotEmpty() && seenTrackIds.add(track.id)) {
                        trackPool.add(WeightedTrack(track, weight))
                    }
                }
            }

            // Capture short-term artist IDs for recency boost
            val shortArtists = artistsByRange["short_term"]
                ?.map { it.id }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

            // Commit to cache atomically
            artistAffinityMap = normalizedAffinity
            artistGenreMap = genreBuilder.mapValues { it.value.toSet() }
            topTrackPool = trackPool
            shortTermArtistIds = shortArtists
            lastProfileRefresh = System.currentTimeMillis()

            Timber.d(
                "$TAG: Profile built — ${normalizedAffinity.size} artists, " +
                    "${trackPool.size} tracks, ${genreBuilder.size} artists with genres"
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to build user profile")
            false
        }
    }

    /**
     * Generate a personalized list of recommended tracks based on a seed track.
     *
     * The algorithm works in 4 phases:
     * 1. Candidate generation from multiple sources
     * 2. Composite scoring of each candidate
     * 3. Ranking by score
     * 4. Diversification via per-artist caps and bucket interleaving
     */
    suspend fun getRecommendations(
        seedTrack: SpotifyTrack,
        limit: Int = 50,
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        if (!ensureProfileLoaded()) {
            Timber.w("$TAG: Profile not available, falling back to basic queue")
            return@withContext emptyList()
        }

        val candidates = mutableListOf<ScoredCandidate>()
        val seenIds = mutableSetOf(seedTrack.id)
        val seedArtistIds = seedTrack.artists.mapNotNull { it.id }.toSet()
        val seedPopularity = seedTrack.popularity ?: 50
        val seedGenres = seedArtistIds.flatMap { artistGenreMap[it].orEmpty() }.toSet()

        Timber.d(
            "$TAG: Generating recommendations for '${seedTrack.name}' " +
                "(artists: ${seedArtistIds.size}, genres: ${seedGenres.size}, pop: $seedPopularity)"
        )

        // --- Source 1: Seed artist top tracks (highest relevance) ---
        coroutineScope {
            seedArtistIds.take(2).map { artistId ->
                async {
                    Spotify.artistTopTracks(artistId).getOrNull()?.tracks?.let { tracks ->
                        synchronized(candidates) {
                            for (track in tracks) {
                                if (track.id.isNotEmpty() && seenIds.add(track.id)) {
                                    candidates.add(
                                        buildCandidate(
                                            track, Bucket.SEED_ARTIST,
                                            seedPopularity, seedGenres,
                                        )
                                    )
                                }
                            }
                        }
                        Timber.d("$TAG: Source SEED_ARTIST($artistId): ${tracks.size} tracks")
                    }
                }
            }.awaitAll()
        }

        // --- Source 2: Same album tracks ---
        seedTrack.album?.id?.let { albumId ->
            Spotify.album(albumId).getOrNull()?.tracks?.items?.let { albumTracks ->
                for (track in albumTracks) {
                    if (track.id.isNotEmpty() && seenIds.add(track.id)) {
                        candidates.add(
                            buildCandidate(
                                track, Bucket.SAME_ALBUM,
                                seedPopularity, seedGenres,
                            )
                        )
                    }
                }
                Timber.d("$TAG: Source SAME_ALBUM($albumId): ${albumTracks.size} tracks")
            }
        }

        // --- Source 3: Genre-neighbor artist top tracks ---
        val neighborArtistIds = findGenreNeighbors(seedArtistIds, seedGenres)
        Timber.d("$TAG: Found ${neighborArtistIds.size} genre-neighbor artists")

        coroutineScope {
            neighborArtistIds.take(4).map { artistId ->
                async {
                    Spotify.artistTopTracks(artistId).getOrNull()?.tracks?.let { tracks ->
                        synchronized(candidates) {
                            for (track in tracks) {
                                if (track.id.isNotEmpty() && seenIds.add(track.id)) {
                                    candidates.add(
                                        buildCandidate(
                                            track, Bucket.GENRE_NEIGHBOR,
                                            seedPopularity, seedGenres,
                                        )
                                    )
                                }
                            }
                        }
                        Timber.d("$TAG: Source GENRE_NEIGHBOR($artistId): ${tracks.size} tracks")
                    }
                }
            }.awaitAll()
        }

        // --- Source 4: User's top tracks pool (personalized background) ---
        for (weightedTrack in topTrackPool) {
            val track = weightedTrack.track
            if (track.id.isNotEmpty() && seenIds.add(track.id)) {
                candidates.add(
                    buildCandidate(
                        track, Bucket.USER_TOP,
                        seedPopularity, seedGenres,
                    )
                )
            }
        }
        Timber.d("$TAG: Source USER_TOP: ${topTrackPool.size} tracks in pool")
        Timber.d("$TAG: Total candidates: ${candidates.size}")

        // --- Rank and diversify ---
        diversify(candidates.sortedByDescending { it.finalScore }, limit)
    }

    /**
     * Builds a scored candidate from a track and its context.
     */
    private fun buildCandidate(
        track: SpotifyTrack,
        bucket: Bucket,
        seedPopularity: Int,
        seedGenres: Set<String>,
    ): ScoredCandidate {
        val trackArtistIds = track.artists.mapNotNull { it.id }

        // Artist affinity: best affinity among the track's artists
        val affinity = trackArtistIds
            .maxOfOrNull { artistAffinityMap[it] ?: 0f } ?: 0f

        // Genre overlap: Jaccard similarity between seed genres and track artist genres
        val trackGenres = trackArtistIds
            .flatMap { artistGenreMap[it].orEmpty() }
            .toSet()
        val genreOverlap = if (seedGenres.isNotEmpty() && trackGenres.isNotEmpty()) {
            val intersection = seedGenres.intersect(trackGenres).size
            val union = seedGenres.union(trackGenres).size
            intersection.toFloat() / union.toFloat()
        } else {
            0f
        }

        // Popularity similarity: how close to seed's popularity (normalized)
        val popDiff = abs((track.popularity ?: 50) - seedPopularity)
        val popSimilarity = 1.0f - (popDiff.toFloat() / 100f)

        // Recency boost: extra score if artist is in the user's short-term favorites
        val recency = if (trackArtistIds.any { it in shortTermArtistIds }) 1.0f else 0f

        return ScoredCandidate(
            track = track,
            bucket = bucket,
            sourceScore = bucket.sourceScore,
            artistAffinity = affinity,
            genreOverlap = genreOverlap,
            popularitySimilarity = popSimilarity,
            recencyBoost = recency,
        )
    }

    /**
     * Finds artists from the user's profile that share genres with the seed
     * artists but are NOT the seed artists themselves.
     *
     * This approximates the deprecated related-artists endpoint by using the
     * user's own listening data as a collaborative filtering signal: if a user
     * listens to both Artist A and Artist B, and they share genres, they are
     * likely "related" for this user's taste.
     *
     * Artists are ranked by: genre overlap * affinity, so we prefer artists
     * that are both genre-similar and well-liked by the user.
     */
    private fun findGenreNeighbors(
        seedArtistIds: Set<String>,
        seedGenres: Set<String>,
    ): List<String> {
        if (seedGenres.isEmpty()) {
            // No genre data: fall back to highest-affinity artists
            return artistAffinityMap
                .filter { it.key !in seedArtistIds }
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }
        }

        return artistGenreMap
            .filter { (artistId, _) -> artistId !in seedArtistIds }
            .map { (artistId, genres) ->
                val intersection = seedGenres.intersect(genres).size
                val union = seedGenres.union(genres).size
                val jaccard = if (union > 0) intersection.toFloat() / union.toFloat() else 0f
                val affinity = artistAffinityMap[artistId] ?: 0f
                // Combined score: genre similarity weighted by user affinity
                artistId to (jaccard * 0.6f + affinity * 0.4f)
            }
            .filter { it.second > 0.05f }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }
    }

    /**
     * Diversifies the ranked candidate list to avoid monotony.
     *
     * Rules:
     * - Max [MAX_TRACKS_PER_ARTIST] tracks per artist
     * - Interleave buckets: for every ~3 closely-related tracks, insert
     *   1 from a different source to maintain variety
     * - Preserves overall score ordering within each bucket
     */
    private fun diversify(
        ranked: List<ScoredCandidate>,
        limit: Int,
    ): List<SpotifyTrack> {
        val result = mutableListOf<SpotifyTrack>()
        val artistCount = mutableMapOf<String, Int>()
        val lastBuckets = mutableListOf<Bucket>()

        // Split into primary (high-score) and discovery (lower-score, different source) pools
        val primaryPool = ranked.toMutableList()
        val usedIndices = mutableSetOf<Int>()

        while (result.size < limit && usedIndices.size < ranked.size) {
            // Determine desired bucket: every 3rd track, prefer a different bucket
            val preferDifferentBucket = lastBuckets.size >= 3 &&
                lastBuckets.takeLast(3).distinct().size == 1

            var bestIndex = -1
            for (i in primaryPool.indices) {
                if (i in usedIndices) continue
                val candidate = primaryPool[i]
                val mainArtist = candidate.track.artists.firstOrNull()?.id ?: ""

                // Check per-artist cap
                if ((artistCount[mainArtist] ?: 0) >= MAX_TRACKS_PER_ARTIST) continue

                // If we want diversity, prefer a different bucket
                if (preferDifferentBucket && lastBuckets.isNotEmpty()) {
                    val lastBucket = lastBuckets.last()
                    if (candidate.bucket != lastBucket) {
                        bestIndex = i
                        break
                    }
                    // If we can't find a different bucket, take the next best
                    if (bestIndex == -1) bestIndex = i
                } else {
                    bestIndex = i
                    break
                }
            }

            if (bestIndex == -1) break

            val chosen = primaryPool[bestIndex]
            usedIndices.add(bestIndex)
            result.add(chosen.track)

            val mainArtist = chosen.track.artists.firstOrNull()?.id ?: ""
            artistCount[mainArtist] = (artistCount[mainArtist] ?: 0) + 1
            lastBuckets.add(chosen.bucket)
            if (lastBuckets.size > 5) lastBuckets.removeAt(0)
        }

        Timber.d(
            "$TAG: Diversified queue: ${result.size} tracks " +
                "(${artistCount.size} unique artists)"
        )
        return result
    }

    /**
     * Invalidates the cached user profile, forcing a rebuild on next use.
     * Call this when the user disables Spotify as a source.
     */
    fun invalidateProfile() {
        lastProfileRefresh = 0L
        artistAffinityMap = emptyMap()
        artistGenreMap = emptyMap()
        topTrackPool = emptyList()
        shortTermArtistIds = emptySet()
        Timber.d("$TAG: Profile cache invalidated")
    }
}
