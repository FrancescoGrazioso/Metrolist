/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify login using an embedded WebView.
 * Loads Spotify's web login page, which supports all auth methods
 * (email/password, Facebook, Google, Apple). After successful login,
 * the sp_dc and sp_key cookies are extracted and used to obtain
 * internal access tokens — no Spotify Developer Client ID required.
 *
 * Token acquisition strategy:
 * 1. User logs in via WebView, landing on open.spotify.com
 * 2. sp_dc/sp_key cookies are extracted
 * 3. JavaScript hooks on fetch() and XMLHttpRequest intercept
 *    the web player's own internal get_access_token call
 * 4. A DOM/localStorage poller acts as a fallback if the hooks
 *    miss the request
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.metrolist.spotify.models.SpotifyInternalToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isExchangingToken by remember { mutableStateOf(false) }

    val tokenBridge = remember {
        SpotifyTokenBridge(context, scope, navController) { exchanging ->
            isExchangingToken = exchanging
        }
    }

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

        if (isLoading || isExchangingToken) {
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

                        @SuppressLint("JavascriptInterface")
                        addJavascriptInterface(tokenBridge, "MetrolistBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                Timber.d("SpotifyLogin: page started: $url")
                                if (url?.startsWith("https://open.spotify.com") == true) {
                                    installNetworkInterceptors(view)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Timber.d("SpotifyLogin: page finished: $url")
                                if (!isExchangingToken) {
                                    tryExtractCookiesAndPollToken(view, url, tokenBridge) {
                                        isExchangingToken = it
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString()
                                Timber.d("SpotifyLogin: navigating to: $requestUrl")
                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                if (reqUrl.contains("access_token") || reqUrl.contains("token")) {
                                    Timber.d("SpotifyLogin: [INTERCEPT] url=$reqUrl")
                                    Timber.d("SpotifyLogin: [INTERCEPT] headers=${request.requestHeaders}")
                                }
                                return null
                            }
                        }

                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
            )

            if (isExchangingToken) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.padding())
                        Text(
                            text = stringResource(R.string.spotify_logging_in),
                            modifier = Modifier.padding(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hooks both window.fetch and XMLHttpRequest.prototype before any page
 * scripts execute. This intercepts whichever mechanism the Spotify web
 * player uses to obtain its access token.
 */
private fun installNetworkInterceptors(view: WebView?) {
    if (view == null) return
    Timber.d("SpotifyLogin: installing fetch+XHR interceptors")
    view.evaluateJavascript(
        """
        (function() {
            if (window.__metrolistHooked) return;
            window.__metrolistHooked = true;
            window.__metrolistDone = false;

            function handleToken(body) {
                try {
                    var data = JSON.parse(body);
                    if (data.accessToken && !data.isAnonymous) {
                        window.__metrolistDone = true;
                        MetrolistBridge.onTokenResult(body);
                        return true;
                    }
                } catch(e) {}
                return false;
            }

            var origFetch = window.fetch;
            window.fetch = function() {
                var urlArg = arguments[0];
                var urlStr = (typeof urlArg === 'string') ? urlArg : (urlArg && urlArg.url ? urlArg.url : '');
                return origFetch.apply(this, arguments).then(function(response) {
                    if (!window.__metrolistDone && urlStr.indexOf('access_token') !== -1 && response.ok) {
                        response.clone().text().then(function(body) { handleToken(body); });
                    }
                    return response;
                });
            };

            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function() {
                this._mUrl = (arguments[1] || '').toString();
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                var xhr = this;
                if (xhr._mUrl.indexOf('access_token') !== -1) {
                    xhr.addEventListener('load', function() {
                        if (!window.__metrolistDone && xhr.status === 200) {
                            handleToken(xhr.responseText);
                        }
                    });
                }
                return origSend.apply(this, arguments);
            };
        })();
        """.trimIndent(),
        null,
    )
}

/**
 * Extracts sp_dc/sp_key cookies once the WebView reaches open.spotify.com,
 * then polls the page DOM and storage for the access token that the Spotify
 * web player obtains during initialization.
 */
private fun tryExtractCookiesAndPollToken(
    view: WebView?,
    url: String?,
    bridge: SpotifyTokenBridge,
    setExchanging: (Boolean) -> Unit,
) {
    if (url == null || !url.startsWith("https://open.spotify.com")) return
    if (view == null) return

    val cookieManager = CookieManager.getInstance()
    val allCookies = cookieManager.getCookie("https://open.spotify.com")
    Timber.d("SpotifyLogin: checking cookies for open.spotify.com: $allCookies")

    if (allCookies.isNullOrBlank()) {
        Timber.w("SpotifyLogin: no cookies found yet")
        return
    }

    val cookieMap = allCookies.split(";")
        .associate { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }

    Timber.d("SpotifyLogin: cookie keys: ${cookieMap.keys}")

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        Timber.w("SpotifyLogin: sp_dc not found, keys present: ${cookieMap.keys}")
        return
    }

    val spKey = cookieMap["sp_key"] ?: ""
    Timber.d("SpotifyLogin: sp_dc found (${spDc.take(8)}...), polling for token...")

    setExchanging(true)
    bridge.spDc = spDc
    bridge.spKey = spKey

    view.evaluateJavascript(
        """
        (function() {
            if (window.__metrolistDone) return;
            var attempts = 0;
            var maxAttempts = 30;

            function handleToken(body) {
                try {
                    var data = (typeof body === 'string') ? JSON.parse(body) : body;
                    if (data.accessToken && !data.isAnonymous) {
                        window.__metrolistDone = true;
                        var json = (typeof body === 'string') ? body : JSON.stringify(data);
                        MetrolistBridge.onTokenResult(json);
                        return true;
                    }
                } catch(e) {}
                return false;
            }

            function searchObj(obj, depth) {
                if (!obj || depth > 3) return false;
                try {
                    if (typeof obj.accessToken === 'string' && obj.accessToken.length > 50) {
                        if (obj.isAnonymous === false || obj.isAnonymous === undefined) {
                            return handleToken(obj);
                        }
                    }
                    if (depth < 3 && typeof obj === 'object') {
                        var keys = Object.keys(obj);
                        for (var k = 0; k < keys.length && k < 50; k++) {
                            try { if (searchObj(obj[keys[k]], depth + 1)) return true; } catch(e) {}
                        }
                    }
                } catch(e) {}
                return false;
            }

            function tryExtract() {
                if (window.__metrolistDone) return;
                attempts++;
                MetrolistBridge.onTokenError('poll attempt ' + attempts);

                // 1. Inline script tags
                var scripts = document.querySelectorAll('script:not([src])');
                for (var i = 0; i < scripts.length; i++) {
                    var text = scripts[i].textContent;
                    if (text && text.indexOf('accessToken') !== -1) {
                        var re = /\{[^{}]*"accessToken"\s*:\s*"[^"]+?"[^{}]*\}/g;
                        var m; while ((m = re.exec(text)) !== null) { if (handleToken(m[0])) return; }
                    }
                }

                // 2. localStorage
                try {
                    for (var j = 0; j < localStorage.length; j++) {
                        var lv = localStorage.getItem(localStorage.key(j));
                        if (lv && lv.indexOf('accessToken') !== -1) { if (handleToken(lv)) return; }
                    }
                } catch(e) {}

                // 3. sessionStorage
                try {
                    for (var k = 0; k < sessionStorage.length; k++) {
                        var sv = sessionStorage.getItem(sessionStorage.key(k));
                        if (sv && sv.indexOf('accessToken') !== -1) { if (handleToken(sv)) return; }
                    }
                } catch(e) {}

                // 4. Search window properties (shallow) for objects with accessToken
                try {
                    var wkeys = Object.getOwnPropertyNames(window);
                    for (var w = 0; w < wkeys.length; w++) {
                        try {
                            var wv = window[wkeys[w]];
                            if (wv && typeof wv === 'object' && searchObj(wv, 0)) return;
                        } catch(e) {}
                    }
                } catch(e) {}

                // 5. Search for token strings matching Spotify pattern (BQ...)
                try {
                    var html = document.documentElement.innerHTML;
                    var tokenRe = /BQ[A-Za-z0-9_-]{100,}/g;
                    var tm;
                    while ((tm = tokenRe.exec(html)) !== null) {
                        var candidate = tm[0];
                        MetrolistBridge.onTokenError('found BQ candidate len=' + candidate.length);
                        var synth = '{"accessToken":"' + candidate + '","accessTokenExpirationTimestampMs":' + (Date.now() + 3600000) + ',"isAnonymous":false}';
                        if (handleToken(synth)) return;
                    }
                } catch(e) {}

                if (attempts < maxAttempts) {
                    setTimeout(tryExtract, 500);
                } else if (!window.__metrolistDone) {
                    MetrolistBridge.onTokenError('Token not found after polling ' + maxAttempts + ' attempts');
                }
            }

            setTimeout(tryExtract, 2000);
        })();
        """.trimIndent(),
        null,
    )
}

/**
 * JavaScript bridge that receives the Spotify access token from the WebView.
 * Methods annotated with @JavascriptInterface are called from JS on a
 * background thread managed by WebView.
 */
private class SpotifyTokenBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val navController: NavController,
    private val setExchanging: (Boolean) -> Unit,
) {
    var spDc: String = ""
    var spKey: String = ""

    @Volatile
    private var tokenProcessed = false

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @JavascriptInterface
    fun onTokenResult(tokenJson: String) {
        if (tokenProcessed) return
        tokenProcessed = true
        Timber.d("SpotifyLogin: received token response (${tokenJson.length} chars)")
        scope.launch(Dispatchers.IO) {
            try {
                val token = json.decodeFromString<SpotifyInternalToken>(tokenJson)

                if (token.isAnonymous || token.accessToken.isBlank()) {
                    throw IllegalStateException("Received anonymous token — sp_dc cookie is invalid or expired")
                }

                Timber.d("SpotifyLogin: access token obtained (anonymous=${token.isAnonymous})")
                Spotify.accessToken = token.accessToken

                context.dataStore.edit { prefs ->
                    prefs[SpotifySpDcKey] = spDc
                    prefs[SpotifySpKeyKey] = spKey
                    prefs[SpotifyAccessTokenKey] = token.accessToken
                    prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
                }

                Spotify.me().onSuccess { user ->
                    Timber.d("SpotifyLogin: logged in as ${user.displayName} (${user.id})")
                    context.dataStore.edit { prefs ->
                        prefs[SpotifyUsernameKey] = user.displayName ?: user.id
                        prefs[SpotifyUserIdKey] = user.id
                    }
                }.onFailure { Timber.e(it, "SpotifyLogin: failed to fetch user profile") }

                scope.launch(Dispatchers.Main) {
                    navController.navigateUp()
                }
            } catch (e: Exception) {
                Timber.e(e, "SpotifyLogin: token parsing/processing failed")
                scope.launch(Dispatchers.Main) { setExchanging(false) }
            }
        }
    }

    @JavascriptInterface
    fun onTokenError(errorMessage: String) {
        if (errorMessage.startsWith("poll attempt") || errorMessage.startsWith("found BQ")) {
            Timber.d("SpotifyLogin: $errorMessage")
            return
        }
        Timber.e("SpotifyLogin: WebView token fetch error: $errorMessage")
        scope.launch(Dispatchers.Main) { setExchanging(false) }
    }
}

/**
 * Desktop Chrome User-Agent. Using desktop UA is critical because:
 * - Facebook's mobile JS has compatibility issues with Android WebView
 * - Spotify and social login providers render more stable desktop pages
 */
private const val USER_AGENT_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
