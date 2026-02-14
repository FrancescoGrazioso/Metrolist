/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify OAuth login using Chrome Custom Tabs.
 * Custom Tabs are used instead of an embedded WebView because
 * third-party OAuth providers (Facebook, Google, Apple) block or
 * degrade their login flows inside WebViews.
 * The OAuth redirect is handled via a deep link intent-filter
 * (metrolist://spotify/callback) registered in AndroidManifest.xml,
 * with token exchange handled in MainActivity.handleDeepLinkIntent().
 */

package com.metrolist.music.ui.screens

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.metrolist.spotify.SpotifyAuth

@Composable
fun SpotifyLoginScreen(
    navController: NavController,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val authUrl = SpotifyAuth.getAuthorizationUrl()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, Uri.parse(authUrl))

        // Navigate back immediately; the OAuth callback will be
        // handled by MainActivity via the deep link intent-filter
        navController.navigateUp()
    }
}
