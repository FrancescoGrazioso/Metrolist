/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Represents a section in the Spotify-powered home screen.
 * Each section has a title and contains one type of content.
 */
data class SpotifyHomeSection(
    val title: String,
    val type: SectionType,
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
)

enum class SectionType {
    TRACKS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
}
