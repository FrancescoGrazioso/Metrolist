/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.queues.SpotifyQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.SpotifyAlbumViewModel
import com.metrolist.spotify.SpotifyMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyAlbumScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyAlbumViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(key = "header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    val thumbnailUrl = album?.images
                        ?.firstOrNull { (it.width ?: 0) >= 300 }?.url
                        ?: album?.images?.firstOrNull()?.url

                    if (thumbnailUrl != null) {
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = album?.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        text = album?.name ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = album?.artists?.joinToString { it.name } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val metaInfo = buildList {
                        album?.albumType?.replaceFirstChar { it.uppercase() }?.let { add(it) }
                        album?.releaseDate?.take(4)?.let { add(it) }
                        if (tracks.isNotEmpty()) {
                            add(pluralStringResource(R.plurals.n_song, tracks.size, tracks.size))
                        }
                    }
                    if (metaInfo.isNotEmpty()) {
                        Text(
                            text = metaInfo.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!isLoading && tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = {
                                    val first = tracks.firstOrNull() ?: return@Button
                                    playerConnection.playQueue(
                                        SpotifyQueue(
                                            initialTrack = first,
                                            mapper = viewModel.mapper,
                                            context = viewModel.context,
                                            database = viewModel.database,
                                        )
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.play),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.play))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val shuffled = tracks.shuffled()
                                    val first = shuffled.firstOrNull() ?: return@OutlinedButton
                                    playerConnection.playQueue(
                                        SpotifyQueue(
                                            initialTrack = first,
                                            mapper = viewModel.mapper,
                                            context = viewModel.context,
                                            database = viewModel.database,
                                        )
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.shuffle),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { viewModel.retry() },
                        ) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }

            if (!isLoading && error == null && tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.spotify_no_tracks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(
                items = tracks,
                key = { index, track -> "track_${track.id}_$index" },
            ) { index, track ->
                val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)

                ListItem(
                    title = track.name,
                    subtitle = joinByBullet(
                        track.artists.joinToString { it.name },
                        makeTimeString(track.durationMs.toLong()),
                    ),
                    thumbnailContent = {
                        ItemThumbnail(
                            thumbnailUrl = thumbnailUrl,
                            isActive = false,
                            isPlaying = false,
                            shape = RoundedCornerShape(ThumbnailCornerRadius),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            playerConnection.playQueue(
                                SpotifyQueue(
                                    initialTrack = track,
                                    mapper = viewModel.mapper,
                                    context = viewModel.context,
                                    database = viewModel.database,
                                )
                            )
                        }
                        .animateItem(),
                )
            }
        }

        TopAppBar(
            title = { Text(album?.name ?: stringResource(R.string.albums)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}
