/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.webkit.CookieManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.constants.SpotifyHomeOnlyKey
import com.metrolist.music.constants.UseSpotifyHomeKey
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var spotifyUsername by rememberPreference(SpotifyUsernameKey, "")
    var spotifyAccessToken by rememberPreference(SpotifyAccessTokenKey, "")
    var spotifySpDc by rememberPreference(SpotifySpDcKey, "")
    var spotifySpKey by rememberPreference(SpotifySpKeyKey, "")
    var spotifyTokenExpiry by rememberPreference(SpotifyTokenExpiryKey, 0L)
    var spotifyUserId by rememberPreference(SpotifyUserIdKey, "")

    val (enableSpotify, onEnableSpotifyChange) = rememberPreference(
        key = EnableSpotifyKey,
        defaultValue = false,
    )

    val isLoggedIn = remember(spotifyAccessToken, spotifySpDc) {
        spotifyAccessToken.isNotEmpty() && spotifySpDc.isNotEmpty()
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.account),
        )

        PreferenceEntry(
            title = {
                Text(
                    text = if (isLoggedIn) spotifyUsername else stringResource(R.string.spotify_not_logged_in),
                    modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                )
            },
            description = null,
            icon = { Icon(painterResource(R.drawable.spotify), null) },
            trailingContent = {
                if (isLoggedIn) {
                    OutlinedButton(onClick = {
                        spotifyAccessToken = ""
                        spotifySpDc = ""
                        spotifySpKey = ""
                        spotifyTokenExpiry = 0L
                        spotifyUsername = ""
                        spotifyUserId = ""
                        onEnableSpotifyChange(false)
                        CookieManager.getInstance().removeAllCookies(null)
                    }) {
                        Text(stringResource(R.string.action_logout))
                    }
                } else {
                    OutlinedButton(
                        onClick = { navController.navigate("settings/spotify/login") },
                    ) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.options),
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.spotify_enable)) },
            description = stringResource(R.string.spotify_enable_description),
            checked = enableSpotify,
            onCheckedChange = onEnableSpotifyChange,
            isEnabled = isLoggedIn,
        )

        if (isLoggedIn && enableSpotify) {
            val (useSpotifySearch, onUseSpotifySearchChange) = rememberPreference(
                key = UseSpotifySearchKey,
                defaultValue = false,
            )
            val (useSpotifyHome, onUseSpotifyHomeChange) = rememberPreference(
                key = UseSpotifyHomeKey,
                defaultValue = false,
            )
            val (spotifyHomeOnly, onSpotifyHomeOnlyChange) = rememberPreference(
                key = SpotifyHomeOnlyKey,
                defaultValue = false,
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.spotify_use_for_search)) },
                description = stringResource(R.string.spotify_use_for_search_description),
                checked = useSpotifySearch,
                onCheckedChange = onUseSpotifySearchChange,
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.spotify_use_for_home)) },
                description = stringResource(R.string.spotify_use_for_home_description),
                checked = useSpotifyHome,
                onCheckedChange = onUseSpotifyHomeChange,
            )

            if (useSpotifyHome) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.spotify_home_only)) },
                    description = stringResource(R.string.spotify_home_only_description),
                    checked = spotifyHomeOnly,
                    onCheckedChange = onSpotifyHomeOnlyChange,
                )
            }

            PreferenceGroupTitle(
                title = stringResource(R.string.information),
            )

            PreferenceEntry(
                title = {
                    Text(
                        text = stringResource(R.string.spotify_mapping_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                description = null,
                icon = {
                    Icon(
                        painterResource(R.drawable.info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_integration)) },
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
