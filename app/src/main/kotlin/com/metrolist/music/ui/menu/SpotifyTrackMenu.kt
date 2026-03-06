/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.YouTubeMatchDialog
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu for Spotify tracks that haven't been resolved to a Room [Song] yet.
 * Provides the most common actions: play next, add to queue, and change YouTube match.
 */
@Composable
fun SpotifyTrackMenu(
    track: SpotifyTrack,
    mapper: SpotifyYouTubeMapper,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    var showYouTubeMatchDialog by rememberSaveable { mutableStateOf(false) }

    val currentMatch by produceState<com.metrolist.music.db.entities.SpotifyMatchEntity?>(
        initialValue = null,
        track.id,
    ) {
        withContext(Dispatchers.IO) {
            value = database.getSpotifyMatch(track.id)
        }
    }

    if (showYouTubeMatchDialog) {
        YouTubeMatchDialog(
            currentYouTubeId = currentMatch?.youtubeId,
            onConfirm = { result ->
                coroutineScope.launch(Dispatchers.IO) {
                    mapper.overrideMatch(
                        spotifyId = track.id,
                        youtubeId = result.videoId,
                        title = result.title,
                        artist = result.artist,
                    )
                }
            },
            onDismiss = { showYouTubeMatchDialog = false },
        )
    }

    val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)

    ListItem(
        headlineContent = {
            Text(
                text = track.name,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = joinByBullet(
                    track.artists.joinToString { it.name },
                    makeTimeString(track.durationMs.toLong()),
                ),
            )
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.play_next)) },
                description = { Text(text = stringResource(R.string.play_next_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.playlist_play),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.playNext(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_play_next),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.add_to_queue)) },
                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.addToQueue(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_queue),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
        ),
    )

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.change_youtube_version)) },
                description = { Text(text = stringResource(R.string.change_youtube_version_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.link),
                        contentDescription = null,
                    )
                },
                onClick = {
                    showYouTubeMatchDialog = true
                },
            ),
        ),
    )
}
