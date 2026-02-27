/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.NewReleaseItem
import com.metrolist.music.models.ReleaseSource
import com.metrolist.music.playback.SpotifyProfileCache
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.toAlbumItem
import com.metrolist.spotify.Spotify
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

enum class NewReleaseTab { FOR_YOU, FOLLOWING, DISCOVER }

@HiltViewModel
class NewReleaseViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val _followingReleases = MutableStateFlow<List<NewReleaseItem>>(emptyList())
    private val _discoverReleases = MutableStateFlow<List<NewReleaseItem>>(emptyList())
    private val _isLoading = MutableStateFlow(true)

    private val _selectedTab = MutableStateFlow(NewReleaseTab.FOR_YOU)
    val selectedTab = _selectedTab.asStateFlow()
    val isLoading = _isLoading.asStateFlow()

    val displayedReleases = combine(_followingReleases, _discoverReleases, _selectedTab) { following, discover, tab ->
        when (tab) {
            NewReleaseTab.FOR_YOU -> following + discover
            NewReleaseTab.FOLLOWING -> following
            NewReleaseTab.DISCOVER -> discover
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val followingCount = _followingReleases
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val discoverCount = _discoverReleases
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            loadReleases()
        }
    }

    fun selectTab(tab: NewReleaseTab) {
        _selectedTab.value = tab
    }

    private suspend fun loadReleases() {
        val spotifyEnabled = context.dataStore.get(EnableSpotifyKey, false)
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)

        if (spotifyEnabled) {
            loadSpotifyPersonalized(hideExplicit)
        } else {
            loadYouTubeFallback(hideExplicit)
        }
        _isLoading.value = false
    }

    private suspend fun loadSpotifyPersonalized(hideExplicit: Boolean) {
        try {
            coroutineScope {
                val spotifyDeferred = async {
                    try {
                        Spotify.newReleases(limit = 50).getOrNull()?.albums?.items ?: emptyList()
                    } catch (e: Exception) {
                        Timber.w(e, "NewReleaseVM: Spotify newReleases failed")
                        emptyList()
                    }
                }

                val followedDeferred = async {
                    try {
                        SpotifyProfileCache.getFollowedArtists(context, database)
                    } catch (e: Exception) {
                        Timber.w(e, "NewReleaseVM: followed artists failed")
                        emptyList()
                    }
                }

                val relatedNamesDeferred = async {
                    try {
                        SpotifyProfileCache.getRelatedArtistNames(context, database)
                    } catch (e: Exception) {
                        Timber.w(e, "NewReleaseVM: related artist names failed")
                        emptySet()
                    }
                }

                val youtubeDeferred = async {
                    try {
                        YouTube.newReleaseAlbums().getOrNull() ?: emptyList()
                    } catch (e: Exception) {
                        Timber.w(e, "NewReleaseVM: YouTube newReleaseAlbums failed")
                        emptyList()
                    }
                }

                val spotifyAlbums = spotifyDeferred.await()
                val followedArtists = followedDeferred.await()
                val relatedNames = relatedNamesDeferred.await()
                val youtubeAlbums = youtubeDeferred.await()

                val followedIds = followedArtists.mapTo(mutableSetOf()) { it.id }
                val followedNames = followedArtists.mapTo(mutableSetOf()) {
                    SpotifyProfileCache.normalizeArtistName(it.name)
                }

                val localArtists = database.allArtistsByPlayTime().first()
                val localBookmarkedIds = localArtists
                    .filter { it.artist.bookmarkedAt != null }
                    .mapTo(mutableSetOf()) { it.id }

                val allFollowedIds = followedIds + localBookmarkedIds

                val spotifyItems = spotifyAlbums.map { album ->
                    val artistIds = album.artists.mapNotNullTo(mutableSetOf()) { it.id }
                    val isFollowed = artistIds.any { it in allFollowedIds }
                    NewReleaseItem(
                        albumItem = album.toAlbumItem(),
                        source = ReleaseSource.SPOTIFY,
                        spotifyAlbumId = album.id,
                        artistIds = artistIds,
                        releaseDate = album.releaseDate,
                        isFromFollowedArtist = isFollowed,
                    )
                }

                val following = spotifyItems
                    .filter { it.isFromFollowedArtist }
                    .sortedWith(Comparator { a, b -> compareDatesDescending(a.releaseDate, b.releaseDate) })
                    .let { if (hideExplicit) it.filter { item -> !item.albumItem.explicit } else it }

                // Discover: YouTube new releases filtered to related artists only,
                // excluding artists the user already follows
                val followingAlbumTitles = following.mapTo(mutableSetOf()) { normalize(it.albumItem.title) }

                val ytDiscover = youtubeAlbums.mapNotNull { album ->
                    val artistNames = album.artists.orEmpty().map {
                        SpotifyProfileCache.normalizeArtistName(it.name)
                    }
                    val isRelated = artistNames.any { it in relatedNames }
                    val isAlreadyFollowed = artistNames.any { it in followedNames }

                    if (!isRelated || isAlreadyFollowed) return@mapNotNull null

                    // Skip duplicates already present in following
                    if (normalize(album.title) in followingAlbumTitles) return@mapNotNull null

                    NewReleaseItem(
                        albumItem = album,
                        source = ReleaseSource.YOUTUBE,
                        spotifyAlbumId = null,
                        artistIds = album.artists.orEmpty().mapNotNullTo(mutableSetOf()) { it.id },
                        releaseDate = album.year?.toString(),
                        isFromFollowedArtist = false,
                    )
                }.let { if (hideExplicit) it.filter { item -> !item.albumItem.explicit } else it }

                _followingReleases.value = following
                _discoverReleases.value = ytDiscover

                Timber.d(
                    "NewReleaseVM: Spotify personalized — ${following.size} following, " +
                        "${ytDiscover.size} discover (from ${youtubeAlbums.size} YT filtered by ${relatedNames.size} related names)"
                )
            }
        } catch (e: Exception) {
            reportException(e)
            loadYouTubeFallback(context.dataStore.get(HideExplicitKey, false))
        }
    }

    private suspend fun loadYouTubeFallback(hideExplicit: Boolean) {
        YouTube.newReleaseAlbums()
            .onSuccess { albums ->
                val localArtists = database.allArtistsByPlayTime().first()
                val artistRank = localArtists.withIndex().associate { (i, a) -> a.id to i }
                val bookmarkedIds = localArtists
                    .filter { it.artist.bookmarkedAt != null }
                    .mapTo(mutableSetOf()) { it.id }

                val items = albums.map { album ->
                    val artistIds = album.artists.orEmpty().mapNotNullTo(mutableSetOf()) { it.id }
                    NewReleaseItem(
                        albumItem = album,
                        source = ReleaseSource.YOUTUBE,
                        spotifyAlbumId = null,
                        artistIds = artistIds,
                        releaseDate = album.year?.toString(),
                        isFromFollowedArtist = artistIds.any { it in bookmarkedIds },
                    )
                }

                val sorted = items.sortedWith(
                    Comparator { a, b ->
                        val bookedA = if (a.isFromFollowedArtist) 0 else 1
                        val bookedB = if (b.isFromFollowedArtist) 0 else 1
                        if (bookedA != bookedB) return@Comparator bookedA - bookedB
                        val rankA = a.artistIds.minOfOrNull { artistRank[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
                        val rankB = b.artistIds.minOfOrNull { artistRank[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
                        rankA.compareTo(rankB)
                    }
                )

                val filtered = if (hideExplicit) sorted.filter { !it.albumItem.explicit } else sorted
                _followingReleases.value = filtered
                _discoverReleases.value = emptyList()
            }
            .onFailure { reportException(it) }
    }

    private fun compareDatesDescending(a: String?, b: String?): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        return b.compareTo(a)
    }

    companion object {
        private fun normalize(s: String): String =
            s.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
