/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.SpotifyPreloadState
import com.metrolist.music.viewmodels.SpotifyPreloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPreloadScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyPreloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state is SpotifyPreloadState.Idle) viewModel.startPreload()
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        when (val s = state) {
            is SpotifyPreloadState.Idle -> {
                Text(
                    text = stringResource(R.string.spotify_preload_songs),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.spotify_preload_songs_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.startPreload() }) {
                    Text(stringResource(R.string.spotify_preload_start))
                }
            }
            is SpotifyPreloadState.FetchingPlaylists -> {
                Text(
                    text = stringResource(R.string.spotify_preload_fetching_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is SpotifyPreloadState.Counting -> {
                Text(
                    text = stringResource(R.string.spotify_preload_counting, s.total),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is SpotifyPreloadState.Converting -> {
                Text(
                    text = stringResource(R.string.spotify_preload_converting, s.current, s.total),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                val progressValue = if (s.total > 0) s.current.toFloat() / s.total else 0f
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is SpotifyPreloadState.Completed -> {
                Text(
                    text = stringResource(R.string.spotify_preload_completed),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.resetToIdle(); navController.navigateUp() }) {
                    Text(stringResource(R.string.back_button_desc))
                }
            }
            is SpotifyPreloadState.Cancelled -> {
                Text(
                    text = stringResource(R.string.spotify_preload_cancelled),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.resetToIdle(); navController.navigateUp() }) {
                    Text(stringResource(R.string.back_button_desc))
                }
            }
            is SpotifyPreloadState.Error -> {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { viewModel.resetToIdle(); navController.navigateUp() }) {
                    Text(stringResource(R.string.back_button_desc))
                }
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_preload_songs)) },
        navigationIcon = {
            IconButton(
                onClick = {
                    if (state is SpotifyPreloadState.FetchingPlaylists ||
                        state is SpotifyPreloadState.Counting ||
                        state is SpotifyPreloadState.Converting
                    ) {
                        viewModel.cancel()
                    }
                    viewModel.resetToIdle()
                    navController.navigateUp()
                },
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
