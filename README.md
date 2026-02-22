<div align="center">
<img src="fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" style="display: block; margin: 0 auto"/>
<h1>Meld</h1>
<p>A music client that fuses Spotify and YouTube Music into one seamless experience</p>

[![Latest release](https://img.shields.io/github/v/release/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases)
[![GitHub license](https://img.shields.io/github/license/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/FrancescoGrazioso/Meld/total?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases)

</div>

## What is Meld?

**Meld** is an Android music client that brings together the best of Spotify and YouTube Music. It uses your Spotify account to power personalized recommendations, search, and home content — while streaming audio through YouTube Music.

The name "Meld" reflects the core idea: **melding** two music platforms into a single, unified listening experience.

### Why Meld?

- **Spotify's personalization** — Your top tracks, favorite artists, and curated playlists from Spotify drive the recommendations
- **YouTube Music's catalog** — Access YouTube Music's vast library for streaming, including rare tracks, live performances, and remixes
- **No Spotify Premium required** — Meld uses Spotify's data APIs (not streaming), so a free Spotify account is all you need
- **Built-in recommendation engine** — A custom algorithm builds personalized queues using your Spotify listening history, without relying on deprecated API endpoints

## Features

### Spotify Integration
- **Spotify as search source** — Search results powered by Spotify, with automatic YouTube Music matching for playback
- **Spotify as home source** — Home screen populated with your Spotify top tracks, top artists, playlists, and new releases
- **Smart queue generation** — Custom recommendation engine that builds radio-like queues from your Spotify taste profile (top tracks/artists across 3 time ranges, genre similarity, popularity matching)
- **Spotify library sync** — Access your Spotify playlists and liked songs directly in the app
- **Spotify-to-YouTube matching** — Fuzzy matching algorithm with local caching for fast, accurate track resolution

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

Meld connects to your Spotify account via OAuth2 (PKCE flow) to access your listening data. Here's what happens under the hood:

1. **Authentication** — You log in with your Spotify account. Meld only requests read access to your library, top items, and playlists.
2. **Home screen** — When "Use Spotify for Home" is enabled, Meld fetches your top tracks (short/medium/long term), top artists, playlists, and new releases to build a personalized home feed.
3. **Search** — When "Use Spotify for Search" is enabled, search queries go to Spotify's API. Results are displayed as Spotify content; clicking a song resolves it to YouTube Music for playback.
4. **Queue generation** — When you play a Spotify-sourced song, Meld's recommendation engine builds a queue by:
   - Fetching top tracks from the song's artists
   - Finding genre-similar artists from your taste profile
   - Mixing in tracks from your personal top tracks pool
   - Scoring candidates by artist affinity, genre overlap, popularity, and recency
   - Diversifying the queue to avoid monotony
5. **Playback** — Each Spotify track is matched to its YouTube Music equivalent using fuzzy title/artist/duration matching, then streamed via YouTube Music's infrastructure.

## Setup

### Spotify Integration

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and log in with your **regular Spotify account** (free or Premium — both work). This automatically gives you access to the developer dashboard; there is no separate sign-up.
2. Create a new app (any name/description will work). When asked for **Redirect URIs**, enter: `meld://spotify/callback`
3. Copy the **Client ID** from your app's dashboard
4. In Meld, go to **Settings → Integrations → Spotify** and paste your Client ID
5. Tap **Login** and authorize with your Spotify account
6. **Important:** After logging in, enable **"Use Spotify for Search"** and/or **"Use Spotify for Home"** in the same settings screen — these are off by default
7. Go back to the home screen and **pull down to refresh**. Your Spotify playlists, top tracks, and recommendations should appear within a few seconds.

> **Note:** Each user needs their own Spotify Client ID. This is free and takes about 2 minutes. You do **not** need Spotify Premium — any Spotify account can create a developer app.

### Building from source

If you prefer to build from source, you can set `SPOTIFY_CLIENT_ID` in `local.properties` to have it bundled at compile time. Users of the pre-built APK can enter their Client ID directly in the app settings.

For GitHub Actions builds, add these secrets to your repository:
- `LASTFM_API_KEY` / `LASTFM_SECRET` — from [last.fm/api/account/create](https://www.last.fm/api/account/create)
- `SPOTIFY_CLIENT_ID` *(optional)* — bundled as default for your builds

## FAQ

### Q: How do I download and install Meld?

Go to the [latest release](https://github.com/FrancescoGrazioso/Meld/releases/latest) and download the **Meld.apk** file. Open it on your Android device — you may need to allow "Install from unknown sources" in your phone's settings when prompted. You do **not** need to download the source code files.

### Q: I set up Spotify but my playlists aren't showing

After logging in with Spotify, make sure you've enabled **"Use Spotify for Home"** and/or **"Use Spotify for Search"** in **Settings → Integrations → Spotify**. These are off by default. Then go back to the home screen and **pull down to refresh**. It can take a few seconds for your content to load the first time.

### Q: I can't create a Spotify Developer app / "Only Premium members can use Spotify for Developers"

This is not the case — **any Spotify account** (free or Premium) can create a developer app. Just go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and log in with your regular Spotify credentials. If you see an error, try logging out and back in, or use a different browser.

### Q: Songs aren't playing / playback isn't working

If songs aren't playing after tapping them, try the following:
1. Wait a moment — the first playback after launch can take a few seconds while the player initializes
2. Check your internet connection
3. Try playing a different song
4. Force-close and reopen the app

In most cases, playback starts working on its own after a brief delay.

### Q: Does Meld work with Bluetooth headphones / AirPods?

Yes. Meld streams audio through YouTube Music's infrastructure like any other music player. It works with any audio output device including Bluetooth headphones, AirPods, car stereos, and speakers.

### Q: Why isn't Meld showing in Android Auto?

1. Go to Android Auto's settings and tap multiple times on the version in the bottom to enable developer settings
2. In the three dots menu at the top-right of the screen, click "Developer settings"
3. Enable "Unknown sources"

### Q: Do I need Spotify Premium?

No. Meld uses Spotify's Web API for data (your library, top tracks, search results) — not for audio streaming. A free Spotify account works perfectly.

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
