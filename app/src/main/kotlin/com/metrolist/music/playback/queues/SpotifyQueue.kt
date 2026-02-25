/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import android.content.Context
import androidx.media3.common.MediaItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyRecommendationEngine
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Queue implementation that builds a personalized radio-like queue using the
 * [SpotifyRecommendationEngine].
 *
 * The engine builds a user taste profile from Spotify top tracks/artists,
 * then generates recommendations by combining seed-artist relevance, genre
 * similarity, user affinity, and popularity matching — with diversification
 * to avoid monotony.
 *
 * If the engine fails (e.g. network issues), falls back to a basic queue
 * built from the seed artist's top tracks.
 *
 * Optimized for fast playback start: only the initial track is resolved in
 * [getInitialStatus]. Subsequent tracks are resolved progressively in
 * [nextPage] batches.
 */
class SpotifyQueue(
    private val initialTrack: SpotifyTrack,
    private val mapper: SpotifyYouTubeMapper,
    private val context: Context? = null,
    private val database: MusicDatabase? = null,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val RESOLVE_BATCH_SIZE = 10
        private const val RECOMMENDATION_TIMEOUT_MS = 4000L
    }

    private val queuedTracks = mutableListOf<SpotifyTrack>()
    private var resolveOffset = 0

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val initialMediaItem = mapper.resolveToMediaItem(initialTrack)

        if (initialMediaItem == null) {
            Timber.w("SpotifyQueue: Could not resolve initial track '${initialTrack.name}'")
            return@withContext Queue.Status(
                title = null,
                items = emptyList(),
                mediaItemIndex = 0,
            )
        }

        try {
            val recommendations = withTimeoutOrNull(RECOMMENDATION_TIMEOUT_MS) {
                SpotifyRecommendationEngine.getRecommendations(
                    seedTrack = initialTrack,
                    context = context,
                    database = database,
                )
            }

            if (recommendations != null && recommendations.isNotEmpty()) {
                queuedTracks.addAll(recommendations)
                Timber.d(
                    "SpotifyQueue: Engine produced ${recommendations.size} recommendations " +
                        "for '${initialTrack.name}'"
                )
            } else {
                if (recommendations == null) {
                    Timber.w("SpotifyQueue: Engine timed out, falling back to basic queue")
                } else {
                    Timber.w("SpotifyQueue: Engine returned empty, falling back to basic queue")
                }
                buildFallbackQueue()
            }
        } catch (e: Exception) {
            Timber.e(e, "SpotifyQueue: Engine failed, falling back to basic queue")
            buildFallbackQueue()
        }

        Timber.d(
            "SpotifyQueue: Resolved initial track '${initialTrack.name}' instantly, " +
                "${queuedTracks.size} tracks queued for resolution"
        )

        Queue.Status(
            title = null,
            items = listOf(initialMediaItem),
            mediaItemIndex = 0,
        )
    }

    /**
     * Basic fallback queue: artist top tracks + same album, shuffled.
     * Used when the recommendation engine is unavailable.
     */
    private suspend fun buildFallbackQueue() {
        val seenIds = mutableSetOf(initialTrack.id)

        for (artistId in initialTrack.artists.mapNotNull { it.id }.take(2)) {
            val topTracks = Spotify.artistTopTracks(artistId).getOrNull()
            if (topTracks != null) {
                val newTracks = topTracks.tracks.filter {
                    it.id.isNotEmpty() && it.id !in seenIds
                }
                queuedTracks.addAll(newTracks)
                seenIds.addAll(newTracks.map { it.id })
            }
        }

        initialTrack.album?.id?.let { albumId ->
            val album = Spotify.album(albumId).getOrNull()
            album?.tracks?.items
                ?.filter { it.id.isNotEmpty() && it.id !in seenIds }
                ?.let { albumTracks ->
                    queuedTracks.addAll(albumTracks)
                    seenIds.addAll(albumTracks.map { it.id })
                }
        }

        queuedTracks.shuffle()
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < queuedTracks.size

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (resolveOffset >= queuedTracks.size) {
            return@withContext emptyList()
        }

        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(queuedTracks.size)
        val batch = queuedTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d(
            "SpotifyQueue: Resolving batch of ${batch.size} tracks " +
                "(offset=$resolveOffset/${queuedTracks.size})"
        )

        coroutineScope {
            batch.map { track -> async { mapper.resolveToMediaItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }
}
