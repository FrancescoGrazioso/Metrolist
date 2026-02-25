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
import com.metrolist.spotify.models.SpotifyAlbum
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
class SpotifyAlbumViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
) : ViewModel() {
    val albumId: String = savedStateHandle.get<String>("albumId")
        ?: throw IllegalArgumentException("albumId is required")
    val mapper = SpotifyYouTubeMapper(database)

    private val _album = MutableStateFlow<SpotifyAlbum?>(null)
    val album = _album.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            Spotify.album(albumId).onSuccess { album ->
                _album.value = album
                _tracks.value = album.tracks?.items?.filter { !it.isLocal } ?: emptyList()
                _isLoading.value = false
            }.onFailure { e ->
                Timber.e(e, "Failed to load Spotify album: $albumId")
                _error.value = e.message ?: "Failed to load album"
                _isLoading.value = false
            }
        }
    }

    fun retry() = loadAlbum()
}
