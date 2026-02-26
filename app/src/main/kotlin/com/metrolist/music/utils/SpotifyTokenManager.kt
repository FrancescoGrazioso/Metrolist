package com.metrolist.music.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Centralized Spotify token management. All token refresh operations go through
 * this singleton to prevent race conditions from multiple callers refreshing
 * simultaneously, which can cause redundant API calls and inconsistent state.
 */
object SpotifyTokenManager {
    private lateinit var dataStore: DataStore<Preferences>
    private val refreshMutex = Mutex()

    private val _needsReLogin = MutableStateFlow(false)
    val needsReLogin: StateFlow<Boolean> = _needsReLogin.asStateFlow()

    fun init(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
    }

    /**
     * Ensures a valid Spotify access token is available. If the current token
     * is expired, acquires the shared mutex and refreshes it via sp_dc cookie.
     * Only one refresh can happen at a time across the entire app.
     *
     * @return true if a valid token is set on [Spotify.accessToken], false otherwise
     */
    suspend fun ensureAuthenticated(): Boolean {
        val settings = dataStore.data.first()
        val accessToken = settings[SpotifyAccessTokenKey] ?: ""
        val expiry = settings[SpotifyTokenExpiryKey] ?: 0L

        if (accessToken.isEmpty()) {
            Timber.d("SpotifyTokenManager: no token stored")
            return false
        }

        if (System.currentTimeMillis() < expiry) {
            Spotify.accessToken = accessToken
            return true
        }

        return refreshMutex.withLock {
            // Re-read after acquiring lock — another caller may have already refreshed
            val freshSettings = dataStore.data.first()
            val freshToken = freshSettings[SpotifyAccessTokenKey] ?: ""
            val freshExpiry = freshSettings[SpotifyTokenExpiryKey] ?: 0L

            if (freshToken.isNotEmpty() && System.currentTimeMillis() < freshExpiry) {
                Spotify.accessToken = freshToken
                Timber.d("SpotifyTokenManager: token already refreshed by another caller")
                return@withLock true
            }

            val spDc = freshSettings[SpotifySpDcKey] ?: ""
            val spKey = freshSettings[SpotifySpKeyKey] ?: ""
            if (spDc.isEmpty()) {
                Timber.w("SpotifyTokenManager: no sp_dc cookie available")
                return@withLock false
            }

            Timber.d("SpotifyTokenManager: token expired, refreshing via cookie...")

            SpotifyAuth.fetchAccessToken(spDc, spKey).fold(
                onSuccess = { token ->
                    Spotify.accessToken = token.accessToken
                    dataStore.edit { prefs ->
                        prefs[SpotifyAccessTokenKey] = token.accessToken
                        prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
                    }
                    _needsReLogin.value = false
                    Timber.d("SpotifyTokenManager: token refreshed successfully")
                    true
                },
                onFailure = { e ->
                    Timber.e(e, "SpotifyTokenManager: refresh FAILED")

                    val isCookieExpired = e.message?.contains("anonymous") == true ||
                        e.message?.contains("expired") == true
                    if (isCookieExpired) {
                        Timber.w("SpotifyTokenManager: cookie expired, clearing session")
                        dataStore.edit { prefs ->
                            prefs.remove(SpotifyAccessTokenKey)
                            prefs.remove(SpotifySpDcKey)
                            prefs.remove(SpotifySpKeyKey)
                            prefs.remove(SpotifyTokenExpiryKey)
                        }
                        Spotify.accessToken = null
                        _needsReLogin.value = true
                    }

                    false
                },
            )
        }
    }

    fun clearReLoginFlag() {
        _needsReLogin.value = false
    }
}
