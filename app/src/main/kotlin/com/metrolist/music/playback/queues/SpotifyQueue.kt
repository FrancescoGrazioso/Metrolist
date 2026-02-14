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
 * Queue implementation that uses Spotify recommendations to build a radio-like queue.
 * Each Spotify track is resolved to a YouTube equivalent before playback.
 */
class SpotifyQueue(
    private val initialTrack: SpotifyTrack,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var hasMore = true

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        // Resolve the initial track
        val initialMediaItem = mapper.resolveToMediaItem(initialTrack)
        if (initialMediaItem != null) {
            items.add(initialMediaItem)
        }

        // Fetch recommendations based on the seed track
        val seedTrackId = initialTrack.id
        val seedArtistIds = initialTrack.artists.mapNotNull { it.id }.take(2)

        try {
            val recommendations = Spotify.recommendations(
                seedTrackIds = listOf(seedTrackId),
                seedArtistIds = seedArtistIds,
                limit = 25,
            ).getOrNull()

            if (recommendations != null) {
                for (track in recommendations.tracks) {
                    val mediaItem = mapper.resolveToMediaItem(track)
                    if (mediaItem != null) {
                        items.add(mediaItem)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Spotify recommendations")
        }

        hasMore = false

        Queue.Status(
            title = null,
            items = items,
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = hasMore

    override suspend fun nextPage(): List<MediaItem> = emptyList()
}
