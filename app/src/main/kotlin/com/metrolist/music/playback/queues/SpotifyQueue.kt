/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation that builds a radio-like queue from Spotify artist top tracks.
 * Uses artistTopTracks (still available) instead of the deprecated recommendations endpoint.
 *
 * Optimized for fast playback start: only the initial track is resolved in
 * [getInitialStatus]. Subsequent tracks are resolved progressively in [nextPage] batches.
 */
class SpotifyQueue(
    private val initialTrack: SpotifyTrack,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val RESOLVE_BATCH_SIZE = 10
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

        // Build queue from artist top tracks (avoids deprecated recommendations endpoint)
        try {
            val artistIds = initialTrack.artists.mapNotNull { it.id }.take(2)
            val seenIds = mutableSetOf(initialTrack.id)

            for (artistId in artistIds) {
                val topTracks = Spotify.artistTopTracks(artistId).getOrNull()
                if (topTracks != null) {
                    val newTracks = topTracks.tracks.filter { it.id !in seenIds }
                    queuedTracks.addAll(newTracks)
                    seenIds.addAll(newTracks.map { it.id })
                    Timber.d("SpotifyQueue: Got ${newTracks.size} tracks from artist $artistId")
                }
            }

            // If we also have an album, get other tracks from the same album
            initialTrack.album?.id?.let { albumId ->
                val album = Spotify.album(albumId).getOrNull()
                if (album != null) {
                    val albumTracks = album.tracks?.items
                        ?.filter { it.id.isNotEmpty() && it.id !in seenIds }
                        ?: emptyList()
                    queuedTracks.addAll(albumTracks)
                    seenIds.addAll(albumTracks.map { it.id })
                    Timber.d("SpotifyQueue: Got ${albumTracks.size} tracks from album $albumId")
                }
            }

            // Shuffle for variety
            queuedTracks.shuffle()
        } catch (e: Exception) {
            Timber.e(e, "SpotifyQueue: Failed to build queue")
        }

        Timber.d("SpotifyQueue: Resolved initial track '${initialTrack.name}' instantly, " +
            "${queuedTracks.size} tracks queued for resolution")

        Queue.Status(
            title = null,
            items = listOf(initialMediaItem),
            mediaItemIndex = 0,
        )
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

        Timber.d("SpotifyQueue: Resolving batch of ${batch.size} tracks " +
            "(offset=$resolveOffset/${queuedTracks.size})")

        coroutineScope {
            batch.map { track -> async { mapper.resolveToMediaItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }
}
