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
import com.metrolist.music.constants.SpotifyRefreshTokenKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
     * Loads ALL Spotify data (playlists, liked songs, top tracks).
     * Call this when entering the Library or Home screen.
     */
    fun loadAll() {
        Timber.d("SpotifyVM: loadAll() triggered")
        loadPlaylists()
        loadLikedSongs()
        loadTopTracks()
    }

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("SpotifyVM: loadPlaylists() start")
            if (!ensureAuthenticated()) {
                Timber.w("SpotifyVM: loadPlaylists() - auth failed")
                _playlistsError.value = "Not authenticated"
                setFallback("Authentication failed while loading playlists")
                return@launch
            }
            _playlistsLoading.value = true
            _playlistsError.value = null

            Spotify.myPlaylists(limit = 50).onSuccess { paging ->
                Timber.d("SpotifyVM: loadPlaylists() - SUCCESS, got ${paging.items.size} playlists")
                for (pl in paging.items.take(5)) {
                    Timber.d("SpotifyVM:   playlist: '${pl.name}' (${pl.tracks?.total ?: "?"} tracks)")
                }
                _spotifyPlaylists.value = paging.items
            }.onFailure { e ->
                Timber.e(e, "SpotifyVM: loadPlaylists() - FAILED")
                _playlistsError.value = e.message
                handleAuthError(e)
                setFallback("Failed to load playlists: ${e.message}")
            }

            _playlistsLoading.value = false
        }
    }

    fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("SpotifyVM: loadLikedSongs() start")
            if (!ensureAuthenticated()) {
                Timber.w("SpotifyVM: loadLikedSongs() - auth failed")
                return@launch
            }
            _likedSongsLoading.value = true

            Spotify.likedSongs(limit = 50).onSuccess { paging ->
                Timber.d("SpotifyVM: loadLikedSongs() - SUCCESS, got ${paging.items.size} songs, total=${paging.total}")
                _likedSongs.value = paging.items.map { it.track }
                _likedSongsTotal.value = paging.total
            }.onFailure { e ->
                Timber.e(e, "SpotifyVM: loadLikedSongs() - FAILED")
                handleAuthError(e)
            }

            _likedSongsLoading.value = false
        }
    }

    fun loadTopTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("SpotifyVM: loadTopTracks() start")
            if (!ensureAuthenticated()) {
                Timber.w("SpotifyVM: loadTopTracks() - auth failed")
                return@launch
            }

            Spotify.topTracks(timeRange = "medium_term", limit = 50).onSuccess { paging ->
                Timber.d("SpotifyVM: loadTopTracks() - SUCCESS, got ${paging.items.size} tracks")
                _topTracks.value = paging.items
            }.onFailure { e ->
                Timber.e(e, "SpotifyVM: loadTopTracks() - FAILED")
                handleAuthError(e)
            }
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> {
        Timber.d("SpotifyVM: getPlaylistTracks($playlistId)")
        if (!ensureAuthenticated()) return emptyList()

        return Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
            ?.items
            ?.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            .also { Timber.d("SpotifyVM: getPlaylistTracks() - got ${it?.size ?: 0} tracks") }
            ?: emptyList()
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    private suspend fun ensureAuthenticated(): Boolean {
        val settings = context.dataStore.data.first()
        val accessToken = settings[SpotifyAccessTokenKey] ?: ""
        val refreshToken = settings[SpotifyRefreshTokenKey] ?: ""
        val expiry = settings[SpotifyTokenExpiryKey] ?: 0L

        Timber.d("SpotifyVM: ensureAuthenticated() - token present=${accessToken.isNotEmpty()}, " +
            "expires in ${(expiry - System.currentTimeMillis()) / 1000}s, " +
            "refreshToken present=${refreshToken.isNotEmpty()}")

        if (accessToken.isEmpty()) {
            Timber.w("SpotifyVM: ensureAuthenticated() - NO TOKEN")
            return false
        }

        // Token still valid
        if (System.currentTimeMillis() < expiry) {
            Spotify.accessToken = accessToken
            Timber.d("SpotifyVM: ensureAuthenticated() - token valid, set on Spotify client")
            return true
        }

        // Token expired, try refresh
        Timber.d("SpotifyVM: ensureAuthenticated() - token EXPIRED, attempting refresh...")
        if (refreshToken.isEmpty()) {
            Timber.w("SpotifyVM: ensureAuthenticated() - no refresh token available")
            return false
        }
        if (!SpotifyAuth.isInitialized()) {
            Timber.w("SpotifyVM: ensureAuthenticated() - SpotifyAuth not initialized (no client ID?)")
            return false
        }

        return SpotifyAuth.refreshToken(refreshToken).fold(
            onSuccess = { token ->
                Spotify.accessToken = token.accessToken
                context.dataStore.edit { prefs ->
                    prefs[SpotifyAccessTokenKey] = token.accessToken
                    prefs[SpotifyTokenExpiryKey] = System.currentTimeMillis() + (token.expiresIn * 1000L)
                    token.refreshToken?.let { prefs[SpotifyRefreshTokenKey] = it }
                }
                Timber.d("SpotifyVM: ensureAuthenticated() - token refreshed successfully (expires in ${token.expiresIn}s)")
                true
            },
            onFailure = { e ->
                Timber.e(e, "SpotifyVM: ensureAuthenticated() - REFRESH FAILED")
                false
            },
        )
    }

    private fun handleAuthError(error: Throwable) {
        if (error is Spotify.SpotifyException && error.statusCode == 401) {
            Timber.w("SpotifyVM: Got 401, will retry auth on next request")
            viewModelScope.launch(Dispatchers.IO) {
                ensureAuthenticated()
            }
        }
    }
}
