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
 * Queue implementation that loads tracks from a Spotify playlist.
 *
 * Optimized for fast playback start: only the selected track is resolved
 * during [getInitialStatus], while the remaining tracks are resolved
 * progressively in [nextPage] batches as the player approaches the end of
 * the currently loaded queue.
 */
class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val SPOTIFY_PAGE_SIZE = 50
        private const val RESOLVE_BATCH_SIZE = 20
        private const val INITIAL_WINDOW_BEFORE = 5
        private const val INITIAL_WINDOW_AFTER = 19
    }

    // All Spotify tracks fetched so far (may span multiple API pages)
    private val allTracks = mutableListOf<SpotifyTrack>()

    // Index into [allTracks] for the next batch to resolve to YouTube
    private var resolveOffset = 0

    // Spotify API pagination state
    private var apiFetchOffset = 0
    private var apiTotal = 0
    private var apiHasMore = true

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        try {
            if (initialTracks.isNotEmpty()) {
                allTracks.addAll(initialTracks)
                apiTotal = initialTracks.size
                apiFetchOffset = apiTotal
                apiHasMore = false
            } else {
                val result = Spotify.playlistTracks(
                    playlistId, limit = SPOTIFY_PAGE_SIZE, offset = 0
                ).getOrThrow()
                apiTotal = result.total
                val fetched = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                allTracks.addAll(fetched)
                apiFetchOffset = result.items.size
                apiHasMore = apiFetchOffset < apiTotal
            }

            while (startIndex >= allTracks.size && apiHasMore) {
                fetchNextApiPage()
            }

            val targetIndex = startIndex.coerceIn(0, (allTracks.size - 1).coerceAtLeast(0))

            // Resolve a window of tracks around the selected one
            val windowStart = (targetIndex - INITIAL_WINDOW_BEFORE).coerceAtLeast(0)
            val windowEnd = (targetIndex + INITIAL_WINDOW_AFTER + 1).coerceAtMost(allTracks.size)
            val windowTracks = allTracks.subList(windowStart, windowEnd)

            val resolvedItems = coroutineScope {
                windowTracks.map { track -> async { mapper.resolveToMediaItem(track) } }
                    .awaitAll()
                    .filterNotNull()
            }

            if (resolvedItems.isEmpty()) {
                Timber.w("SpotifyPlaylistQueue: Could not resolve any track in initial window")
                return@withContext Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
            }

            resolveOffset = windowEnd

            val mediaItemIndex = (targetIndex - windowStart)
                .coerceIn(0, (resolvedItems.size - 1).coerceAtLeast(0))

            Timber.d("SpotifyPlaylistQueue: Resolved ${resolvedItems.size} tracks " +
                "(window $windowStart..$windowEnd, target=$targetIndex, total=$apiTotal)")

            Queue.Status(
                title = null,
                items = resolvedItems,
                mediaItemIndex = mediaItemIndex,
            )
        } catch (e: Exception) {
            Timber.e(e, "SpotifyPlaylistQueue: Failed initial fetch")
            Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
        }
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < allTracks.size || apiHasMore

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        // If we've resolved all fetched tracks but the API has more, fetch another page
        if (resolveOffset >= allTracks.size && apiHasMore) {
            fetchNextApiPage()
        }

        if (resolveOffset >= allTracks.size) {
            return@withContext emptyList()
        }

        // Resolve the next batch
        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(allTracks.size)
        val batch = allTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d("SpotifyPlaylistQueue: Resolving batch of ${batch.size} tracks " +
            "(offset=$resolveOffset/${allTracks.size}, apiTotal=$apiTotal)")

        coroutineScope {
            batch.map { track -> async { mapper.resolveToMediaItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun fetchNextApiPage() {
        if (!apiHasMore) return
        try {
            val result = Spotify.playlistTracks(
                playlistId, limit = SPOTIFY_PAGE_SIZE, offset = apiFetchOffset,
            ).getOrThrow()
            val fetched = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            allTracks.addAll(fetched)
            apiFetchOffset += result.items.size
            apiHasMore = apiFetchOffset < apiTotal
            Timber.d("SpotifyPlaylistQueue: Fetched API page, now have ${allTracks.size} tracks")
        } catch (e: Exception) {
            Timber.e(e, "SpotifyPlaylistQueue: Failed to fetch next API page")
            apiHasMore = false
        }
    }
}
