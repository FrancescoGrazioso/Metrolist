/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifyClientIdKey
import com.metrolist.music.constants.SpotifyRefreshTokenKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.constants.UseSpotifyHomeKey
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.spotify.SpotifyAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var spotifyUsername by rememberPreference(SpotifyUsernameKey, "")
    var spotifyAccessToken by rememberPreference(SpotifyAccessTokenKey, "")
    var spotifyRefreshToken by rememberPreference(SpotifyRefreshTokenKey, "")
    var spotifyTokenExpiry by rememberPreference(SpotifyTokenExpiryKey, 0L)
    var spotifyUserId by rememberPreference(SpotifyUserIdKey, "")
    var savedClientId by rememberPreference(SpotifyClientIdKey, "")

    val (enableSpotify, onEnableSpotifyChange) = rememberPreference(
        key = EnableSpotifyKey,
        defaultValue = false,
    )

    // Resolve effective Client ID: DataStore > BuildConfig
    val builtInClientId = BuildConfig.SPOTIFY_CLIENT_ID
    val effectiveClientId = savedClientId.ifEmpty { builtInClientId }
    val hasClientId = effectiveClientId.isNotEmpty()

    // Text field state initialized from the effective Client ID
    var clientIdInput by rememberSaveable(effectiveClientId) {
        mutableStateOf(effectiveClientId)
    }

    // Re-initialize SpotifyAuth when the effective Client ID changes
    LaunchedEffect(effectiveClientId) {
        if (effectiveClientId.isNotEmpty()) {
            SpotifyAuth.initialize(effectiveClientId)
        }
    }

    val isLoggedIn = remember(spotifyAccessToken) {
        spotifyAccessToken.isNotEmpty()
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        // --- Client ID Section ---
        PreferenceGroupTitle(
            title = stringResource(R.string.spotify_client_id),
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = clientIdInput,
                onValueChange = { clientIdInput = it.trim() },
                label = { Text(stringResource(R.string.spotify_client_id)) },
                placeholder = { Text(stringResource(R.string.spotify_client_id_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (clientIdInput.isNotEmpty() && clientIdInput != effectiveClientId) {
                            savedClientId = clientIdInput
                            SpotifyAuth.initialize(clientIdInput)
                            // Clear tokens if Client ID changed while logged in
                            if (isLoggedIn) {
                                spotifyAccessToken = ""
                                spotifyRefreshToken = ""
                                spotifyTokenExpiry = 0L
                                spotifyUsername = ""
                                spotifyUserId = ""
                                onEnableSpotifyChange(false)
                            }
                        }
                    },
                ),
                supportingText = {
                    if (clientIdInput.isEmpty()) {
                        Text(
                            text = stringResource(R.string.spotify_client_id_required),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (clientIdInput != effectiveClientId) {
                        Text(
                            text = "Press Done to save",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )

            Spacer(Modifier.height(4.dp))

            // Save button (visible when input differs from saved value)
            AnimatedVisibility(
                visible = clientIdInput.isNotEmpty() && clientIdInput != effectiveClientId,
            ) {
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        savedClientId = clientIdInput
                        SpotifyAuth.initialize(clientIdInput)
                        if (isLoggedIn) {
                            spotifyAccessToken = ""
                            spotifyRefreshToken = ""
                            spotifyTokenExpiry = 0L
                            spotifyUsername = ""
                            spotifyUserId = ""
                            onEnableSpotifyChange(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Client ID")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Setup guide card
            AnimatedVisibility(visible = !hasClientId || !isLoggedIn) {
                val dashboardUrl = stringResource(R.string.spotify_client_id_help_url)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.spotify_client_id_setup_guide),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.spotify_client_id_setup_step1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.spotify_client_id_setup_step2),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.spotify_client_id_setup_step3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.spotify_client_id_setup_step4),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            dashboardUrl.toUri(),
                                        )
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.language),
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(stringResource(R.string.spotify_open_dashboard))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // --- Account Section ---
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
            description = if (!hasClientId) stringResource(R.string.spotify_client_id_required) else null,
            icon = { Icon(painterResource(R.drawable.spotify), null) },
            trailingContent = {
                if (isLoggedIn) {
                    OutlinedButton(onClick = {
                        spotifyAccessToken = ""
                        spotifyRefreshToken = ""
                        spotifyTokenExpiry = 0L
                        spotifyUsername = ""
                        spotifyUserId = ""
                        onEnableSpotifyChange(false)
                    }) {
                        Text(stringResource(R.string.action_logout))
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            navController.navigate("settings/spotify/login")
                        },
                        enabled = hasClientId,
                    ) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            },
        )

        // --- Options Section ---
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
        }
    )
}
