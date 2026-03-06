/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyPlaylistViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {
    val playlistId: String = savedStateHandle.get<String>("playlistId")
        ?: throw IllegalArgumentException("playlistId is required")
    val mapper = SpotifyYouTubeMapper(database)

    private val _playlist = MutableStateFlow<SpotifyPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadPlaylist()
    }

    private fun loadPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            Spotify.playlist(playlistId).onSuccess { pl ->
                _playlist.value = pl
            }.onFailure { e ->
                Timber.e(e, "Failed to load Spotify playlist metadata")
                _error.value = e.message ?: "Failed to load playlist info"
            }

            // Load all tracks
            Spotify.playlistTracks(playlistId, limit = 100, offset = 0).onSuccess { paging ->
                val allTracks = paging.items
                    .mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                    .toMutableList()

                // Load remaining pages
                var offset = allTracks.size
                val total = paging.total
                while (offset < total) {
                    Spotify.playlistTracks(playlistId, limit = 100, offset = offset)
                        .onSuccess { nextPage ->
                            val nextTracks = nextPage.items
                                .mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                            allTracks.addAll(nextTracks)
                            offset += nextTracks.size
                        }
                        .onFailure {
                            offset = total // Stop pagination on error
                        }
                }

                _tracks.value = allTracks
                _isLoading.value = false
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to load playlist tracks"
                _isLoading.value = false
                Timber.e(e, "Failed to load Spotify playlist tracks")
            }
        }
    }

    fun retry() = loadPlaylist()
}
