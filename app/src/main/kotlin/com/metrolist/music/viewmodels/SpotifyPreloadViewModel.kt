/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * ViewModel for the "Precarica canzoni di Spotify" job: fetches all playlists
 * and liked songs, then resolves each track to YouTube and caches in the local DB.
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

sealed class SpotifyPreloadState {
    data object Idle : SpotifyPreloadState()
    data object FetchingPlaylists : SpotifyPreloadState()
    data class Counting(val total: Int) : SpotifyPreloadState()
    data class Converting(val current: Int, val total: Int) : SpotifyPreloadState()
    data object Completed : SpotifyPreloadState()
    data object Cancelled : SpotifyPreloadState()
    data class Error(val message: String) : SpotifyPreloadState()
}

@HiltViewModel
class SpotifyPreloadViewModel @Inject constructor(
    private val database: MusicDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow<SpotifyPreloadState>(SpotifyPreloadState.Idle)
    val state: StateFlow<SpotifyPreloadState> = _state.asStateFlow()

    private var preloadJob: Job? = null
    private var cancelled = false

    fun startPreload() {
        if (_state.value !is SpotifyPreloadState.Idle) return
        cancelled = false
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            runPreload()
        }
    }

    fun cancel() {
        cancelled = true
        preloadJob?.cancel()
    }

    private suspend fun runPreload() = withContext(Dispatchers.IO) {
        val mapper = SpotifyYouTubeMapper(database)

        _state.value = SpotifyPreloadState.FetchingPlaylists

        if (!com.metrolist.music.utils.SpotifyTokenManager.ensureAuthenticated()) {
            _state.value = SpotifyPreloadState.Error("Authentication failed")
            return@withContext
        }

        val allTracks = mutableSetOf<SpotifyTrack>() // dedupe by id

        // Fetch all playlists (paginated)
        var plOffset = 0
        val plLimit = 50
        while (true) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            val plResult = Spotify.myPlaylists(limit = plLimit, offset = plOffset).getOrNull()
                ?: run {
                    _state.value = SpotifyPreloadState.Error("Failed to load playlists")
                    return@withContext
                }
            val playlists = plResult.items
            if (playlists.isEmpty()) break

            for (playlist in playlists) {
                if (cancelled) {
                    _state.value = SpotifyPreloadState.Cancelled
                    return@withContext
                }
                var trackOffset = 0
                val trackLimit = 100
                while (true) {
                    val ptResult = Spotify.playlistTracks(
                        playlist.id,
                        limit = trackLimit,
                        offset = trackOffset,
                    ).getOrNull() ?: break
                    val items = ptResult.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                    allTracks.addAll(items)
                    if (items.size < trackLimit || trackOffset + items.size >= ptResult.total) break
                    trackOffset += items.size
                }
            }
            if (playlists.size < plLimit || plOffset + playlists.size >= plResult.total) break
            plOffset += playlists.size
        }

        // Fetch all liked songs (paginated)
        var likedOffset = 0
        val likedLimit = 50
        while (true) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            val likedResult = Spotify.likedSongs(limit = likedLimit, offset = likedOffset).getOrNull()
                ?: break
            val tracks = likedResult.items.map { it.track }.filter { !it.isLocal }
            allTracks.addAll(tracks)
            if (tracks.size < likedLimit || likedOffset + tracks.size >= likedResult.total) break
            likedOffset += tracks.size
        }

        val total = allTracks.size
        _state.value = SpotifyPreloadState.Counting(total)

        if (total == 0) {
            _state.value = SpotifyPreloadState.Completed
            return@withContext
        }

        val list = allTracks.toList()
        var converted = 0
        for ((index, track) in list.withIndex()) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            mapper.mapToYouTube(track) // populates cache; ignore return
            converted = index + 1
            _state.value = SpotifyPreloadState.Converting(converted, total)
        }

        Timber.d("SpotifyPreload: completed, converted $total tracks")
        _state.value = SpotifyPreloadState.Completed
    }

    fun resetToIdle() {
        _state.value = SpotifyPreloadState.Idle
    }
}
