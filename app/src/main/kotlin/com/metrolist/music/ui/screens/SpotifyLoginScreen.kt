/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify login using an embedded WebView.
 * Loads Spotify's web login page, which supports all auth methods
 * (email/password, Facebook, Google, Apple). After successful login,
 * the WebView redirect to open.spotify.com is intercepted and the
 * sp_dc cookie is extracted to fetch an access token.
 *
 * Token acquisition uses TOTP (Time-based One-Time Password) generated
 * from a community-maintained shared secret, following the approach used
 * by the Spotube Spotify plugin. The token is fetched entirely in the
 * background using HttpURLConnection — no web player, no rate limit issues.
 *
 * Reference: https://github.com/sonic-liberation/spotube-plugin-spotify
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.spotify_login)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        if (isLoading || isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = USER_AGENT_DESKTOP

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                Timber.d("SpotifyLogin: page started: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Timber.d("SpotifyLogin: page finished: $url")

                                if (url?.startsWith("https://open.spotify.com") == true &&
                                    tokenFetchStarted.compareAndSet(false, true)
                                ) {
                                    Timber.d("SpotifyLogin: extracting token from onPageFinished")
                                    extractAndFetchToken(
                                        view = view,
                                        context = context,
                                        scope = scope,
                                        navController = navController,
                                        setProcessing = { isProcessing = it },
                                        setStatus = { statusMessage = it },
                                        setError = { hasError = it },
                                        tokenFetchStarted = tokenFetchStarted,
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                Timber.d("SpotifyLogin: navigating to: $requestUrl")

                                if (requestUrl.startsWith("https://open.spotify.com")) {
                                    val spDc = extractSpDcCookie()
                                    if (spDc != null && tokenFetchStarted.compareAndSet(false, true)) {
                                        Timber.d("SpotifyLogin: sp_dc available at redirect, processing immediately")
                                        extractAndFetchToken(
                                            view = view,
                                            context = context,
                                            scope = scope,
                                            navController = navController,
                                            setProcessing = { isProcessing = it },
                                            setStatus = { statusMessage = it },
                                            setError = { hasError = it },
                                            tokenFetchStarted = tokenFetchStarted,
                                        )
                                        return true
                                    }
                                    // sp_dc not ready yet — let the page load so
                                    // onPageFinished can pick up the cookie later
                                    Timber.d("SpotifyLogin: sp_dc not ready at redirect, deferring to onPageFinished")
                                    return false
                                }

                                return false
                            }
                        }

                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
            )

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!hasError) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = statusMessage.ifEmpty {
                                stringResource(R.string.spotify_logging_in)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (hasError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = {
                                    hasError = false
                                    isProcessing = false
                                    statusMessage = ""
                                    tokenFetchStarted.set(false)
                                    retryCount++
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Attempts to read the sp_dc cookie from the CookieManager.
 * Returns null if the cookie is not yet available.
 */
private fun extractSpDcCookie(): String? {
    val allCookies = CookieManager.getInstance().getCookie("https://open.spotify.com")
    if (allCookies.isNullOrBlank()) return null

    return allCookies.split(";")
        .mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .firstOrNull { it.first == "sp_dc" && it.second.isNotBlank() }
        ?.second
}

/**
 * Extracts sp_dc/sp_key cookies, stops the WebView, and fetches the access
 * token in the background using [SpotifyAuth.fetchAccessToken].
 *
 * Uses [tokenFetchStarted] as an atomic guard so only one invocation ever runs,
 * preventing the race between shouldOverrideUrlLoading and onPageFinished.
 */
private fun extractAndFetchToken(
    view: WebView?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    navController: NavController,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
) {
    val cookieManager = CookieManager.getInstance()
    val allCookies = cookieManager.getCookie("https://open.spotify.com")
    Timber.d("SpotifyLogin: cookies present: ${!allCookies.isNullOrBlank()}")

    val cookieMap = allCookies?.split(";")
        ?.mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                parts[0].trim() to parts[1].trim()
            } else {
                null
            }
        }?.toMap() ?: emptyMap()

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        Timber.w("SpotifyLogin: sp_dc not found in cookies (keys: ${cookieMap.keys})")
        setProcessing(true)
        setStatus(context.getString(R.string.spotify_login_error_no_cookie))
        setError(true)
        tokenFetchStarted.set(false)
        return
    }

    val spKey = cookieMap["sp_key"] ?: ""
    Timber.d("SpotifyLogin: sp_dc found (${spDc.take(8)}...), starting token fetch")

    setProcessing(true)
    setError(false)
    setStatus(context.getString(R.string.spotify_status_verifying))

    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        try {
            context.dataStore.edit { prefs ->
                prefs[SpotifySpDcKey] = spDc
                prefs[SpotifySpKeyKey] = spKey
            }

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_status_connecting))
            }
            Timber.d("SpotifyLogin: fetching access token via SpotifyAuth (with TOTP)...")

            val token = SpotifyAuth.fetchAccessToken(spDc, spKey).getOrThrow()
            Timber.d("SpotifyLogin: token obtained (anonymous=${token.isAnonymous})")
            Spotify.accessToken = token.accessToken

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_status_loading_profile))
            }
            Timber.d("SpotifyLogin: fetching user profile...")

            Spotify.me().onSuccess { user ->
                Timber.d("SpotifyLogin: logged in as ${user.displayName} (${user.id})")
                context.dataStore.edit { prefs ->
                    prefs[SpotifyUsernameKey] = user.displayName ?: user.id
                    prefs[SpotifyUserIdKey] = user.id
                }
            }.onFailure { e ->
                Timber.w(e, "SpotifyLogin: could not fetch profile (non-fatal)")
            }

            context.dataStore.edit { prefs ->
                prefs[SpotifyAccessTokenKey] = token.accessToken
                prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
            }

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_login_success))
            }
            Timber.d("SpotifyLogin: login complete, navigating back")

            delay(300)

            withContext(Dispatchers.Main) {
                navController.navigateUp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "SpotifyLogin: login failed — ${e.message}")
            val errorMsg = classifyLoginError(context, e)
            withContext(Dispatchers.Main) {
                setStatus(errorMsg)
                setError(true)
            }
            tokenFetchStarted.set(false)
        }
    }
}

/**
 * Maps authentication exceptions to user-friendly error messages.
 */
private fun classifyLoginError(context: Context, e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        "anonymous" in msg || "expired" in msg ->
            context.getString(R.string.spotify_login_error_expired)
        "HTTP 403" in msg || "HTTP 401" in msg ->
            context.getString(R.string.spotify_login_error_rejected)
        "gist" in msg.lowercase() || "nuance" in msg.lowercase() ->
            context.getString(R.string.spotify_login_error_network)
        "UnknownHostException" in msg || "timeout" in msg.lowercase() ||
            "SocketTimeoutException" in e.javaClass.simpleName ->
            context.getString(R.string.spotify_login_error_network)
        else ->
            context.getString(R.string.spotify_login_error)
    }
}

/**
 * Desktop Chrome User-Agent. Using desktop UA is critical because:
 * - Facebook's mobile JS has compatibility issues with Android WebView
 * - Spotify and social login providers render more stable desktop pages
 */
private const val USER_AGENT_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
