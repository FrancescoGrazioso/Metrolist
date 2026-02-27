/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.AlbumItem

enum class ReleaseSource { SPOTIFY, YOUTUBE }

/**
 * Unified model for new releases from either Spotify or YouTube.
 * Wraps an [AlbumItem] (the common UI model) with source metadata
 * used for sorting, deduplication, and navigation.
 */
data class NewReleaseItem(
    val albumItem: AlbumItem,
    val source: ReleaseSource,
    val spotifyAlbumId: String?,
    val artistIds: Set<String>,
    val releaseDate: String?,
    val isFromFollowedArtist: Boolean = false,
)
