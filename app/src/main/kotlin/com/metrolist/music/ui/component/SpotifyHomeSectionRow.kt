/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.constants.GridThumbnailHeight
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.models.SectionType
import com.metrolist.music.models.SpotifyHomeSection
import com.metrolist.music.utils.toAlbumItem
import com.metrolist.music.utils.toArtistItem
import com.metrolist.music.utils.toPlaylistItem
import com.metrolist.music.utils.toSongItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Resolves the display title for a Spotify home section.
 * Handles special cases like "spotify_because_you_like:ArtistName".
 */
@Composable
fun resolveSpotifySectionTitle(section: SpotifyHomeSection): String {
    val title = section.title
    return when {
        title.startsWith("spotify_because_you_like:") -> {
            val artistName = title.removePrefix("spotify_because_you_like:")
            stringResource(R.string.spotify_because_you_like, artistName)
        }
        title == "spotify_top_tracks" -> stringResource(R.string.spotify_top_tracks)
        title == "spotify_top_artists" -> stringResource(R.string.spotify_top_artists)
        title == "spotify_made_for_you" -> stringResource(R.string.spotify_made_for_you)
        title == "spotify_discover" -> stringResource(R.string.spotify_discover)
        title == "spotify_your_playlists" -> stringResource(R.string.spotify_your_playlists)
        title == "spotify_new_releases" -> stringResource(R.string.spotify_new_releases)
        else -> title
    }
}

/**
 * Renders a section of tracks as a horizontal scrollable grid (4 rows).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyTrackSectionRow(
    tracks: List<SpotifyTrack>,
    horizontalItemWidth: Dp,
    isPlaying: Boolean,
    currentMediaId: String?,
    onTrackClick: (SpotifyTrack) -> Unit,
    onTrackLongClick: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyHorizontalGrid(
        state = rememberLazyGridState(),
        rows = GridCells.Fixed(4),
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues(),
        modifier = modifier
            .fillMaxWidth()
            .height(ListItemHeight * 4)
    ) {
        items(
            items = tracks,
            key = { "spotify_track_${it.id}" }
        ) { track ->
            val songItem = track.toSongItem()
            YouTubeListItem(
                item = songItem,
                isActive = false,
                isPlaying = isPlaying,
                isSwipeable = false,
                modifier = Modifier
                    .width(horizontalItemWidth)
                    .combinedClickable(
                        onClick = { onTrackClick(track) },
                        onLongClick = { onTrackLongClick(track) },
                    ),
            )
        }
    }
}

/**
 * Renders a section of artists as a horizontal scrollable row with circular images.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyArtistSectionRow(
    artists: List<SpotifyArtist>,
    onArtistClick: (SpotifyArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = artists,
            key = { "spotify_artist_${it.id}" }
        ) { artist ->
            val thumbnail = artist.images.firstOrNull { it.width in 200..400 }?.url
                ?: artist.images.firstOrNull()?.url

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(GridThumbnailHeight + 24.dp)
                    .padding(horizontal = 6.dp)
                    .combinedClickable(
                        onClick = { onArtistClick(artist) },
                    ),
            ) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(GridThumbnailHeight)
                        .clip(CircleShape),
                )
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Renders a section of albums as a horizontal row with cover art.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyAlbumSectionRow(
    albums: List<SpotifyAlbum>,
    onAlbumClick: (SpotifyAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = albums,
            key = { "spotify_album_${it.id}" }
        ) { album ->
            val albumItem = album.toAlbumItem()
            YouTubeGridItem(
                item = albumItem,
                isActive = false,
                isPlaying = false,
                modifier = Modifier
                    .width(GridThumbnailHeight + 24.dp)
                    .padding(horizontal = 6.dp)
                    .combinedClickable(
                        onClick = { onAlbumClick(album) },
                    ),
            )
        }
    }
}

/**
 * Renders a section of playlists as a horizontal row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlaylistSectionRow(
    playlists: List<SpotifyPlaylist>,
    onPlaylistClick: (SpotifyPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = playlists,
            key = { "spotify_playlist_${it.id}" }
        ) { playlist ->
            val playlistItem = playlist.toPlaylistItem()
            YouTubeGridItem(
                item = playlistItem,
                isActive = false,
                isPlaying = false,
                modifier = Modifier
                    .width(GridThumbnailHeight + 24.dp)
                    .padding(horizontal = 6.dp)
                    .combinedClickable(
                        onClick = { onPlaylistClick(playlist) },
                    ),
            )
        }
    }
}
