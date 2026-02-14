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
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation that loads tracks from a Spotify playlist.
 * Supports pagination and resolves each Spotify track to a YouTube equivalent.
 */
class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var nextOffset: Int = 0
    private var hasMore = true
    private var totalTracks = 0

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        val tracks = if (initialTracks.isNotEmpty()) {
            nextOffset = initialTracks.size
            totalTracks = initialTracks.size
            initialTracks
        } else {
            // Fetch first page of playlist tracks
            try {
                val result = Spotify.playlistTracks(playlistId, limit = 50, offset = 0).getOrThrow()
                nextOffset = result.items.size
                totalTracks = result.total
                result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch Spotify playlist tracks")
                hasMore = false
                return@withContext Queue.Status(
                    title = null,
                    items = emptyList(),
                    mediaItemIndex = 0,
                )
            }
        }

        hasMore = nextOffset < totalTracks

        for (track in tracks) {
            val mediaItem = mapper.resolveToMediaItem(track)
            if (mediaItem != null) {
                items.add(mediaItem)
            }
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
            val result = Spotify.playlistTracks(playlistId, limit = 50, offset = nextOffset).getOrThrow()
            val tracks = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            nextOffset += tracks.size
            hasMore = nextOffset < totalTracks

            tracks.mapNotNull { track -> mapper.resolveToMediaItem(track) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch next page of Spotify playlist")
            hasMore = false
            emptyList()
        }
    }
}
