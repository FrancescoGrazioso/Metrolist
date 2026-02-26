<div align="center">
<img src="fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" style="display: block; margin: 0 auto"/>
<h1>Meld</h1>
<p>A music client that fuses Spotify and YouTube Music into one seamless experience</p>

[![Latest release](https://img.shields.io/github/v/release/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases/latest)
[![GitHub license](https://img.shields.io/github/license/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/FrancescoGrazioso/Meld/total?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases)

</div>

## What is Meld?

**Meld** is an Android music client that brings together the best of Spotify and YouTube Music. It uses your Spotify account to power personalized recommendations, search, and home content — while streaming audio through YouTube Music.

The name "Meld" reflects the core idea: **melding** two music platforms into a single, unified listening experience.

### Why Meld?

- **Spotify's personalization** — Your top tracks, favorite artists, and curated playlists from Spotify drive the recommendations
- **YouTube Music's catalog** — Access YouTube Music's vast library for streaming, including rare tracks, live performances, and remixes
- **No setup required** — Just log in with your Spotify account directly in the app. No developer dashboard, no Client ID, no extra steps
- **No Spotify Premium required** — Meld uses Spotify's data APIs (not streaming), so a free Spotify account is all you need
- **Built-in recommendation engine** — A custom algorithm builds personalized queues using your Spotify listening history, without relying on deprecated API endpoints

## Features

### Spotify Integration
- **Spotify as search source** — Search results powered by Spotify, with automatic YouTube Music matching for playback
- **Spotify as home source** — Home screen populated with your Spotify top tracks, top artists, playlists, and new releases
- **Spotify-only mode** — Option to hide all YouTube-based content and show exclusively Spotify-powered sections on the home screen
- **Smart queue generation** — Custom recommendation engine that builds radio-like queues from your Spotify taste profile (top tracks/artists across 3 time ranges, genre similarity, popularity matching)
- **Spotify library sync** — Access your Spotify playlists and liked songs directly in the app
- **Spotify-to-YouTube matching** — Fuzzy matching algorithm with local caching for fast, accurate track resolution
- **Spotify album browsing** — Dedicated album screen for Spotify albums with full tracklist, metadata, and one-tap playback
- **Hybrid profile cache** — 3-tier data strategy (GraphQL → REST API → local DB) with persistent caching for instant home screen loading on app restart, automatic rate-limit handling, and parallel artist image enrichment
- **Artist navigation** — Tap any Spotify artist on the home screen to navigate directly to their YouTube Music artist page

### Core Music Features
- Play any song or video from YouTube Music
- Background playback
- Personalized quick picks
- Library management
- Listen together with friends
- Download and cache songs for offline playback
- Search for songs, albums, artists, videos and playlists
- Live lyrics
- YouTube Music account login support
- Syncing of songs, artists, albums and playlists, from and to your account
- Skip silence
- Import playlists
- Audio normalization
- Adjust tempo/pitch
- Local playlist management
- Reorder songs in playlist or queue
- Home screen widget with playback controls
- Light / Dark / Black / Dynamic theme
- Sleep timer
- Material 3 design
- Discord Rich Presence

## Download

<div align="center">
<a href="https://github.com/FrancescoGrazioso/Meld/releases/latest/download/Meld.apk"><img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="82"></a>
</div>

> **First time here?** Tap the badge above or go to the [Releases page](https://github.com/FrancescoGrazioso/Meld/releases), then download the **Meld.apk** file and open it on your Android device. You may need to allow installation from unknown sources in your phone's settings.

## How the Spotify Integration Works

Meld connects to your Spotify account through a built-in WebView login — no developer setup or Client ID required. Here's what happens under the hood:

1. **Authentication** — You log in with your regular Spotify credentials (email, Google, Facebook, or Apple) directly inside the app. Meld extracts session cookies and generates access tokens using TOTP, keeping you logged in without manual token management.
2. **Data layer** — Meld communicates with Spotify primarily through GraphQL endpoints (for playlists, liked songs, artist details, albums, new releases, and search) with REST API fallbacks for top tracks and top artists. GraphQL avoids the aggressive rate limits that affect REST endpoints.
3. **Home screen** — When "Use Spotify for Home" is enabled, Meld builds a personalized home feed from your top tracks, top artists, playlists, and new releases. Enable "Spotify only" to hide all YouTube-based sections for a fully Spotify-driven experience.
4. **Profile caching** — Your Spotify profile data (top tracks, top artists with images) is persisted locally and served instantly on app restart. Background network refreshes only happen when the cache is stale (6-hour TTL), keeping the home screen fast and responsive.
5. **Search** — When "Use Spotify for Search" is enabled, search queries go through Spotify's GraphQL search. Results are displayed as Spotify content; tapping a song resolves it to YouTube Music for playback.
6. **Queue generation** — When you play a Spotify-sourced song, Meld's recommendation engine builds a queue by:
   - Fetching top tracks from the song's artists
   - Finding genre-similar artists from your taste profile
   - Mixing in tracks from your personal top tracks pool
   - Scoring candidates by artist affinity (30%), genre overlap (20%), source relevance (25%), recency (15%), and popularity similarity (10%)
   - Diversifying the queue to avoid repetition (max 3 tracks per artist)
7. **Playback** — Each Spotify track is matched to its YouTube Music equivalent using fuzzy title/artist/duration matching, then streamed via YouTube Music's infrastructure. Matched results are cached locally for instant resolution on subsequent plays.

## Setup

### Spotify Integration

1. In Meld, go to **Settings → Integrations → Spotify**
2. Tap **Login** — a Spotify login page will open directly inside the app
3. Sign in with your Spotify account (email/password, Google, Facebook, or Apple)
4. Once logged in, enable **"Use Spotify for Search"** and/or **"Use Spotify for Home"** — these are off by default
5. Optionally enable **"Spotify only"** to hide all YouTube-based content from the home screen
6. Go back to the home screen and **pull down to refresh**. Your Spotify playlists, top tracks, and recommendations should appear within a few seconds.

> **Note:** No developer account, Client ID, or any external setup is required. Just log in with your regular Spotify account — free or Premium.

> **Important:** For reliable playback, disable battery optimization for Meld in your phone settings (**Settings → Apps → Meld → Battery → Unrestricted**). Without this, Android may throttle the app and cause long delays before songs start playing.

### Building from source

For GitHub Actions builds, add these secrets to your repository:
- `LASTFM_API_KEY` / `LASTFM_SECRET` — from [last.fm/api/account/create](https://www.last.fm/api/account/create)

## FAQ

### Q: How do I download and install Meld?

Go to the [latest release](https://github.com/FrancescoGrazioso/Meld/releases/latest) and download the **Meld.apk** file. Open it on your Android device — you may need to allow "Install from unknown sources" in your phone's settings when prompted. You do **not** need to download the source code files.

### Q: I logged into Spotify but my playlists aren't showing

After logging in, make sure you've enabled **"Use Spotify for Home"** and/or **"Use Spotify for Search"** in **Settings → Integrations → Spotify**. These are off by default. Then go back to the home screen and **pull down to refresh**. The first load may take a few seconds; subsequent launches will be instant thanks to local caching.

### Q: Songs aren't playing / playback is very slow to start

If songs aren't playing or take a long time to start, try the following:

1. **Disable battery optimization for Meld** — Go to your phone's **Settings → Apps → Meld → Battery → Unrestricted** (or "No restrictions"). This is the most common fix. Android aggressively throttles background network and CPU usage for battery-optimized apps, which directly impacts Meld's stream resolution pipeline. Without this setting, playback may take over a minute to start, especially when the screen is locked.
2. Wait a moment — the first playback after a fresh launch requires initializing the streaming engine (signature verification, token generation). Subsequent plays are much faster.
3. Check your internet connection
4. Try playing a different song
5. Force-close and reopen the app

In general for the first time you play a song it's normal for it to take alonger time, the process to download metadata from spotify, look for a correspondent on youtube and match it can take time, for some song more than others! From the second time it will be stored in a local DB and this process won't need to be run again

### Q: Does Meld work with Bluetooth headphones / AirPods?

Yes. Meld streams audio through YouTube Music's infrastructure like any other music player. It works with any audio output device including Bluetooth headphones, AirPods, car stereos, and speakers.

### Q: Why isn't Meld showing in Android Auto?

1. Go to Android Auto's settings and tap multiple times on the version in the bottom to enable developer settings
2. In the three dots menu at the top-right of the screen, click "Developer settings"
3. Enable "Unknown sources"

### Q: Do I need Spotify Premium?

No. Meld uses Spotify for data only (your library, top tracks, search results) — not for audio streaming. Audio is streamed through YouTube Music. A free Spotify account works perfectly.

### Q: Some songs won't play — I get a playback error

Certain tracks on YouTube may be age-restricted or region-locked. If you're not logged into YouTube, some of these tracks cannot be played because YouTube requires authentication to verify your identity. To fix this:

1. Go to **Settings → Account** and log in with your YouTube / Google account
2. Go back and try playing the song again

If the track still doesn't play after logging in, it may be restricted in your country or permanently unavailable.

### Q: Why do some songs not match correctly?

The Spotify-to-YouTube matching uses fuzzy matching on title, artist name, and duration. In rare cases (live versions, remasters, regional variants), the match may not be perfect. Matched results are cached locally so they're resolved instantly on subsequent plays.

## Credits

Meld is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist), originally created by [Mo Agamy](https://github.com/mostafaalagamy).

### Upstream Projects

- **InnerTune** — [Zion Huang](https://github.com/z-huang) · [Malopieds](https://github.com/Malopieds)
- **OuterTune** — [Davide Garberi](https://github.com/DD3Boh) · [Michael Zh](https://github.com/mikooomich)

### Libraries and Integrations

- [**Kizzy**](https://github.com/dead8309/Kizzy) — Discord Rich Presence implementation
- [**Better Lyrics**](https://better-lyrics.boidu.dev) — Time-synced lyrics with word-by-word highlighting
- [**SimpMusic Lyrics**](https://github.com/maxrave-dev/SimpMusic) — Lyrics data through the SimpMusic Lyrics API
- [**metroserver**](https://github.com/MetrolistGroup/metroserver) — Listen Together implementation
- [**MusicRecognizer**](https://github.com/aleksey-saenko/MusicRecognizer) — Music recognition and Shazam API integration

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC, Spotify AB, or any of their affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
