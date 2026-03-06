/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * ViewModel that manages Spotify as the PRIMARY music source.
 * When Spotify is enabled, all library content comes from Spotify.
 * YouTube Music serves only as a fallback for audio playback
 * (since Spotify free accounts can't stream directly).
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    companion object {
        private const val STAGGER_DELAY_MS = 500L
    }

    val spotifyYouTubeMapper = SpotifyYouTubeMapper(database)

    // =========================================================================
    // State: Spotify active flag
    // When true, Spotify is the PRIMARY source for library content.
    // =========================================================================

    val isSpotifyActive = context.dataStore.data
        .map {
            val enabled = it[EnableSpotifyKey] ?: false
            val hasToken = (it[SpotifyAccessTokenKey] ?: "").isNotEmpty()
            val active = enabled && hasToken
            Timber.d("SpotifyVM: isSpotifyActive=$active (enabled=$enabled, hasToken=$hasToken)")
            active
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // =========================================================================
    // State: Fallback indicator
    // Set to true when a Spotify operation fails and YouTube is used instead.
    // UI can show a snackbar/banner to inform the user.
    // =========================================================================

    private val _isUsingFallback = MutableStateFlow(false)
    val isUsingFallback = _isUsingFallback.asStateFlow()

    private val _fallbackReason = MutableStateFlow<String?>(null)
    val fallbackReason = _fallbackReason.asStateFlow()

    fun clearFallbackState() {
        _isUsingFallback.value = false
        _fallbackReason.value = null
    }

    private fun setFallback(reason: String) {
        Timber.w("SpotifyVM: FALLBACK to YouTube - $reason")
        _isUsingFallback.value = true
        _fallbackReason.value = reason
    }

    // =========================================================================
    // State: Playlists
    // =========================================================================

    private val _spotifyPlaylists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val spotifyPlaylists = _spotifyPlaylists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading = _playlistsLoading.asStateFlow()

    private val _playlistsError = MutableStateFlow<String?>(null)
    val playlistsError = _playlistsError.asStateFlow()

    // =========================================================================
    // State: Liked Songs
    // =========================================================================

    private val _likedSongs = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val likedSongs = _likedSongs.asStateFlow()

    private val _likedSongsLoading = MutableStateFlow(false)
    val likedSongsLoading = _likedSongsLoading.asStateFlow()

    private val _likedSongsTotal = MutableStateFlow(0)
    val likedSongsTotal = _likedSongsTotal.asStateFlow()

    // =========================================================================
    // State: Top Tracks
    // =========================================================================

    private val _topTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val topTracks = _topTracks.asStateFlow()

    // =========================================================================
    // Data loading
    // =========================================================================

    /**
     * Loads ALL Spotify data sequentially with staggered delays.
     * If any call hits a 429, waits the full Retry-After before proceeding
     * to the next endpoint to avoid cascading rate-limit failures.
     */
    fun loadAll() {
        Timber.d("SpotifyVM: loadAll() triggered")
        viewModelScope.launch(Dispatchers.IO) {
            var rateLimitDelay = 0L

            rateLimitDelay = loadPlaylistsInternal()
            if (rateLimitDelay > 0) {
                Timber.d("SpotifyVM: loadAll() waiting ${rateLimitDelay}s after playlists 429")
                delay(rateLimitDelay * 1000)
            } else {
                delay(STAGGER_DELAY_MS)
            }

            rateLimitDelay = loadLikedSongsInternal()
            if (rateLimitDelay > 0) {
                Timber.d("SpotifyVM: loadAll() waiting ${rateLimitDelay}s after liked songs 429")
                delay(rateLimitDelay * 1000)
            } else {
                delay(STAGGER_DELAY_MS)
            }

            loadTopTracksInternal()
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) { loadPlaylistsInternal() }
    }

    fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) { loadLikedSongsInternal() }
    }

    fun loadTopTracks() {
        viewModelScope.launch(Dispatchers.IO) { loadTopTracksInternal() }
    }

    /** @return Retry-After seconds if 429 was hit, 0 otherwise */
    private suspend fun loadPlaylistsInternal(): Long {
        Timber.d("SpotifyVM: loadPlaylists() start")
        _playlistsLoading.value = true
        _playlistsError.value = null

        if (!SpotifyTokenManager.ensureAuthenticated()) {
            Timber.w("SpotifyVM: loadPlaylists() - auth failed")
            _playlistsError.value = "Not authenticated"
            _playlistsLoading.value = false
            setFallback("Authentication failed while loading playlists")
            return 0
        }

        var retryAfter = 0L
        Spotify.myPlaylists(limit = 50).onSuccess { paging ->
            Timber.d("SpotifyVM: loadPlaylists() - SUCCESS, got ${paging.items.size} playlists")
            for (pl in paging.items.take(5)) {
                Timber.d("SpotifyVM:   playlist: '${pl.name}' (${pl.tracks?.total ?: "?"} tracks)")
            }
            _spotifyPlaylists.value = paging.items
        }.onFailure { e ->
            Timber.e(e, "SpotifyVM: loadPlaylists() - FAILED")
            _playlistsError.value = e.message
            retryAfter = (e as? Spotify.SpotifyException)?.retryAfterSec ?: 0
            handleAuthError(e)
            setFallback("Failed to load playlists: ${e.message}")
        }

        _playlistsLoading.value = false
        return retryAfter
    }

    /** @return Retry-After seconds if 429 was hit, 0 otherwise */
    private suspend fun loadLikedSongsInternal(): Long {
        Timber.d("SpotifyVM: loadLikedSongs() start")
        _likedSongsLoading.value = true

        if (!SpotifyTokenManager.ensureAuthenticated()) {
            Timber.w("SpotifyVM: loadLikedSongs() - auth failed")
            _likedSongsLoading.value = false
            setFallback("Authentication failed while loading liked songs")
            return 0
        }

        var retryAfter = 0L
        Spotify.likedSongs(limit = 50).onSuccess { paging ->
            Timber.d("SpotifyVM: loadLikedSongs() - SUCCESS, got ${paging.items.size} songs, total=${paging.total}")
            _likedSongs.value = paging.items.map { it.track }
            _likedSongsTotal.value = paging.total
        }.onFailure { e ->
            Timber.e(e, "SpotifyVM: loadLikedSongs() - FAILED")
            retryAfter = (e as? Spotify.SpotifyException)?.retryAfterSec ?: 0
            handleAuthError(e)
            setFallback("Failed to load liked songs: ${e.message}")
        }

        _likedSongsLoading.value = false
        return retryAfter
    }

    private suspend fun loadTopTracksInternal() {
        Timber.d("SpotifyVM: loadTopTracks() start")
        if (!SpotifyTokenManager.ensureAuthenticated()) {
            Timber.w("SpotifyVM: loadTopTracks() - auth failed")
            return
        }

        Spotify.topTracks(timeRange = "medium_term", limit = 50).onSuccess { paging ->
            Timber.d("SpotifyVM: loadTopTracks() - SUCCESS, got ${paging.items.size} tracks")
            _topTracks.value = paging.items
        }.onFailure { e ->
            Timber.e(e, "SpotifyVM: loadTopTracks() - FAILED")
            handleAuthError(e)
            setFallback("Failed to load top tracks: ${e.message}")
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> {
        Timber.d("SpotifyVM: getPlaylistTracks($playlistId)")
        if (!SpotifyTokenManager.ensureAuthenticated()) return emptyList()

        return Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
            ?.items
            ?.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            .also { Timber.d("SpotifyVM: getPlaylistTracks() - got ${it?.size ?: 0} tracks") }
            ?: emptyList()
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    val needsReLogin = SpotifyTokenManager.needsReLogin

    private fun handleAuthError(error: Throwable) {
        if (error is Spotify.SpotifyException && error.statusCode == 401) {
            Timber.w("SpotifyVM: Got 401, will attempt token refresh")
            viewModelScope.launch(Dispatchers.IO) {
                val refreshed = SpotifyTokenManager.ensureAuthenticated()
                Timber.d("SpotifyVM: handleAuthError - refresh result: $refreshed")
            }
        }
    }
}
