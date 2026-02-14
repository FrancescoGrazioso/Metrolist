/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation for Spotify Liked Songs (saved tracks).
 * Loads songs in pages and resolves each to a YouTube equivalent.
 */
class SpotifyLikedSongsQueue(
    private val startIndex: Int = 0,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var nextOffset: Int = 0
    private var hasMore = true
    private var totalTracks = 0

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        try {
            val result = Spotify.likedSongs(limit = 50, offset = 0).getOrThrow()
            nextOffset = result.items.size
            totalTracks = result.total
            hasMore = nextOffset < totalTracks

            for (savedTrack in result.items) {
                val track = savedTrack.track
                if (!track.isLocal) {
                    val mediaItem = mapper.resolveToMediaItem(track)
                    if (mediaItem != null) {
                        items.add(mediaItem)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Spotify liked songs")
            hasMore = false
        }

        Queue.Status(
            title = null,
            items = items,
            mediaItemIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }

    override fun hasNextPage(): Boolean = hasMore

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!hasMore) return@withContext emptyList()

        try {
            val result = Spotify.likedSongs(limit = 50, offset = nextOffset).getOrThrow()
            nextOffset += result.items.size
            hasMore = nextOffset < totalTracks

            result.items.mapNotNull { savedTrack ->
                if (!savedTrack.track.isLocal) {
                    mapper.resolveToMediaItem(savedTrack.track)
                } else null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch next page of Spotify liked songs")
            hasMore = false
            emptyList()
        }
    }
}
