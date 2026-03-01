/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.HideAtvSongsKey
import com.metrolist.music.constants.HideOmvSongsKey
import com.metrolist.music.constants.HideUgcSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SpotifyMatchEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.get
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Handles the matching of Spotify tracks to YouTube Music equivalents.
 * Uses fuzzy matching on title, artist, and duration to find the best result.
 * Caches successful matches in the local Room database.
 */
class SpotifyYouTubeMapper(
    private val database: MusicDatabase,
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Maps a Spotify track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     */
    suspend fun mapToYouTube(track: SpotifyTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        val hiddenTypes = buildSet {
            if (dataStore.get(HideUgcSongsKey, false)) add(MUSIC_VIDEO_TYPE_UGC)
            if (dataStore.get(HideOmvSongsKey, false)) add(MUSIC_VIDEO_TYPE_OMV)
            if (dataStore.get(HideAtvSongsKey, false)) add(MUSIC_VIDEO_TYPE_ATV)
        }

        val cached = database.getSpotifyMatch(track.id)
        if (cached != null && !cached.isManualOverride && cached.musicVideoType in hiddenTypes) {
            Timber.d("Spotify cache hit but type ${cached.musicVideoType} is hidden, re-resolving: ${track.name}")
            database.deleteSpotifyMatch(track.id)
        } else if (cached != null) {
            Timber.d("Spotify match cache hit: ${track.name} -> ${cached.youtubeId} (type=${cached.musicVideoType}, manual=${cached.isManualOverride})")
            return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist, musicVideoType = cached.musicVideoType)
        }

        val query = SpotifyMapper.buildSearchQuery(track)
        Timber.d("Searching YouTube for Spotify track: $query")

        val searchResult = YouTube.searchSummary(query).getOrNull() ?: return@withContext null
        val bestMatch = findBestMatch(track, searchResult, hiddenTypes)

        if (bestMatch != null) {
            database.upsertSpotifyMatch(
                SpotifyMatchEntity(
                    spotifyId = track.id,
                    youtubeId = bestMatch.id,
                    title = bestMatch.title,
                    artist = bestMatch.artists.firstOrNull()?.name ?: "",
                    matchScore = bestMatch.score,
                    musicVideoType = bestMatch.musicVideoType,
                )
            )
            Timber.d("Spotify match found: ${track.name} -> ${bestMatch.id} (type=${bestMatch.musicVideoType}, score: ${bestMatch.score})")
            return@withContext buildMediaMetadata(
                youtubeId = bestMatch.id,
                spotifyTrack = track,
                ytTitle = bestMatch.title,
                ytArtist = bestMatch.artistName,
                ytThumbnailUrl = bestMatch.thumbnailUrl,
                musicVideoType = bestMatch.musicVideoType,
            )
        }

        Timber.w("No YouTube match found for Spotify track: ${track.name} by ${track.artists.firstOrNull()?.name}")
        null
    }

    /**
     * Persists a user-chosen YouTube match for a Spotify track.
     * Manual overrides are never replaced by the automatic fuzzy matcher.
     */
    suspend fun overrideMatch(
        spotifyId: String,
        youtubeId: String,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        database.upsertSpotifyMatch(
            SpotifyMatchEntity(
                spotifyId = spotifyId,
                youtubeId = youtubeId,
                title = title,
                artist = artist,
                matchScore = 1.0,
                isManualOverride = true,
            )
        )
        Timber.d("Manual override saved: $spotifyId -> $youtubeId ($title by $artist)")
    }

    /**
     * Resolves a Spotify track to a MediaItem suitable for the player queue.
     * The MediaItem's id is the YouTube video ID, allowing the existing
     * ResolvingDataSource to resolve the actual stream URL.
     * Returns null if no match was found (track will be skipped).
     */
    suspend fun resolveToMediaItem(track: SpotifyTrack): androidx.media3.common.MediaItem? {
        Timber.d("SpotifyMapper: resolving '${track.name}' by ${track.artists.firstOrNull()?.name}")
        val metadata = mapToYouTube(track)
        if (metadata == null) {
            Timber.w("SpotifyMapper: FAILED to resolve '${track.name}' - no YouTube match")
            return null
        }
        Timber.d("SpotifyMapper: resolved '${track.name}' -> YouTube ID: ${metadata.id}")
        return metadata.toMediaItem()
    }

    private fun findBestMatch(
        spotifyTrack: SpotifyTrack,
        searchResult: SearchSummaryPage,
        hiddenTypes: Set<String> = emptySet(),
    ): MatchCandidate? {
        val spotifyArtist = spotifyTrack.artists.firstOrNull()?.name ?: ""
        val candidates = mutableListOf<MatchCandidate>()

        // Extract SongItems from all search summaries, filtering hidden video types
        val songs = searchResult.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()
            .let { list ->
                if (hiddenTypes.isNotEmpty()) {
                    list.filter { it.musicVideoType !in hiddenTypes }
                } else {
                    list
                }
            }

        for (song in songs) {
            val rawScore = SpotifyMapper.matchScore(
                spotifyTitle = spotifyTrack.name,
                spotifyArtist = spotifyArtist,
                spotifyDurationMs = spotifyTrack.durationMs,
                candidateTitle = song.title,
                candidateArtist = song.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = song.duration,
            )
            // Prefer official audio tracks (ATV) over live versions, music videos, or UGC
            val atvBonus = if (song.musicVideoType == MUSIC_VIDEO_TYPE_ATV) ATV_SCORE_BONUS else 0.0
            val score = rawScore + atvBonus
            candidates.add(
                MatchCandidate(
                    id = song.id,
                    title = song.title,
                    artistName = song.artists.firstOrNull()?.name ?: "",
                    artists = song.artists.map { MediaMetadata.Artist(id = it.id, name = it.name) },
                    duration = song.duration ?: -1,
                    thumbnailUrl = song.thumbnail,
                    albumId = song.album?.id,
                    albumTitle = song.album?.name,
                    explicit = song.explicit,
                    score = score,
                    musicVideoType = song.musicVideoType,
                )
            )
        }

        return candidates.maxByOrNull { it.score }?.takeIf { it.score >= MIN_MATCH_THRESHOLD }
    }

    private fun buildMediaMetadata(
        youtubeId: String,
        spotifyTrack: SpotifyTrack,
        ytTitle: String,
        ytArtist: String,
        ytThumbnailUrl: String? = null,
        musicVideoType: String? = null,
    ): MediaMetadata {
        val thumbnail = SpotifyMapper.getTrackThumbnail(spotifyTrack)
            ?: ytThumbnailUrl
            ?: "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"

        return MediaMetadata(
            id = youtubeId,
            title = ytTitle.ifEmpty { spotifyTrack.name },
            artists = if (ytArtist.isNotEmpty()) {
                listOf(MediaMetadata.Artist(id = null, name = ytArtist))
            } else {
                spotifyTrack.artists.map { MediaMetadata.Artist(id = null, name = it.name) }
            },
            duration = spotifyTrack.durationMs / 1000,
            thumbnailUrl = thumbnail,
            album = spotifyTrack.album?.let {
                MediaMetadata.Album(id = it.id, title = it.name)
            },
            explicit = spotifyTrack.explicit,
            musicVideoType = musicVideoType,
        )
    }

    private data class MatchCandidate(
        val id: String,
        val title: String,
        val artistName: String,
        val artists: List<MediaMetadata.Artist>,
        val duration: Int,
        val thumbnailUrl: String?,
        val albumId: String?,
        val albumTitle: String?,
        val explicit: Boolean,
        val score: Double,
        val musicVideoType: String? = null,
    )

    companion object {
        private const val MIN_MATCH_THRESHOLD = 0.35

        // Bonus applied to official audio tracks (MUSIC_VIDEO_TYPE_ATV) during scoring.
        // Ensures studio recordings are preferred over live versions and user-generated content
        // when title/artist/duration scores are otherwise similar.
        private const val ATV_SCORE_BONUS = 0.05
    }
}
