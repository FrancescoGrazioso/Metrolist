/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
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
class SpotifyLikedSongsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val mapper = SpotifyYouTubeMapper(database)

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total = _total.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadLikedSongs()
    }

    private fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            Spotify.likedSongs(limit = 50, offset = 0).onSuccess { paging ->
                val allTracks = paging.items
                    .map { it.track }
                    .filter { !it.isLocal }
                    .toMutableList()

                _total.value = paging.total

                // Load remaining pages
                var offset = paging.items.size
                while (offset < paging.total) {
                    Spotify.likedSongs(limit = 50, offset = offset)
                        .onSuccess { nextPage ->
                            val nextTracks = nextPage.items
                                .map { it.track }
                                .filter { !it.isLocal }
                            allTracks.addAll(nextTracks)
                            offset += nextPage.items.size
                        }
                        .onFailure {
                            offset = paging.total // Stop pagination on error
                            Timber.e(it, "Failed to load next page of liked songs at offset $offset")
                        }
                }

                _tracks.value = allTracks
                _isLoading.value = false
                Timber.d("SpotifyLikedSongs: Loaded ${allTracks.size} tracks (total=${paging.total})")
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to load liked songs"
                _isLoading.value = false
                Timber.e(e, "Failed to load Spotify liked songs")
            }
        }
    }

    fun retry() = loadLikedSongs()
}
