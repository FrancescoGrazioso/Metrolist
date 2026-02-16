/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Prefix used to distinguish Spotify IDs from YouTube IDs in YTItem wrappers.
 * When an item's ID starts with this prefix, it originated from Spotify.
 */
const val SPOTIFY_ID_PREFIX = "spotify:"

fun String.isSpotifyId(): Boolean = startsWith(SPOTIFY_ID_PREFIX)

fun String.stripSpotifyPrefix(): String = removePrefix(SPOTIFY_ID_PREFIX)

fun SpotifyTrack.toSongItem(): SongItem = SongItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    artists = artists.map { Artist(name = it.name, id = it.id?.let { i -> "$SPOTIFY_ID_PREFIX$i" }) },
    album = album?.let { Album(name = it.name, id = "$SPOTIFY_ID_PREFIX${it.id}") },
    duration = durationMs / 1000,
    thumbnail = SpotifyMapper.getTrackThumbnail(this) ?: "",
    explicit = explicit,
)

fun SpotifyAlbum.toAlbumItem(): AlbumItem = AlbumItem(
    browseId = "$SPOTIFY_ID_PREFIX$id",
    playlistId = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    artists = artists.map { Artist(name = it.name, id = it.id?.let { i -> "$SPOTIFY_ID_PREFIX$i" }) },
    year = releaseDate?.take(4)?.toIntOrNull(),
    thumbnail = images.firstOrNull { it.width in 200..400 }?.url
        ?: images.firstOrNull()?.url ?: "",
    explicit = false,
)

fun SpotifyArtist.toArtistItem(): ArtistItem = ArtistItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    thumbnail = images.firstOrNull { it.width in 200..400 }?.url
        ?: images.firstOrNull()?.url,
    shuffleEndpoint = null,
    radioEndpoint = null,
)

fun SpotifyPlaylist.toPlaylistItem(): PlaylistItem = PlaylistItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    author = owner?.let { Artist(name = it.displayName ?: "", id = null) },
    songCountText = tracks?.total?.toString(),
    thumbnail = SpotifyMapper.getPlaylistThumbnail(this),
    playEndpoint = null,
    shuffleEndpoint = null,
    radioEndpoint = null,
)

/**
 * Extracts the original Spotify track from its SpotifyTrack data
 * for use with SpotifyQueue when a converted YTItem is clicked.
 */
fun SongItem.toSpotifyTrackStub(): SpotifyTrack? {
    if (!id.isSpotifyId()) return null
    return SpotifyTrack(
        id = id.stripSpotifyPrefix(),
        name = title,
        artists = artists.map {
            com.metrolist.spotify.models.SpotifySimpleArtist(
                id = it.id?.stripSpotifyPrefix(),
                name = it.name,
            )
        },
        durationMs = (duration ?: 0) * 1000,
        explicit = explicit,
    )
}
