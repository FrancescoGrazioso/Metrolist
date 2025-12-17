package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.YouTubeAlbumMenu
import com.metrolist.music.ui.menu.YouTubeArtistMenu
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.constants.SuggestionItemHeight
import com.metrolist.music.viewmodels.OnlineSearchSuggestionViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.constants.SearchFilterHeight
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.menu.YouTubeAlbumMenu
import com.metrolist.music.ui.menu.YouTubeArtistMenu
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
    suggestionViewModel: OnlineSearchSuggestionViewModel = hiltViewModel(),
    pureBlack: Boolean = false
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Extract query from navigation arguments
    val encodedQuery = navController.currentBackStackEntry?.arguments?.getString("query") ?: ""
    val decodedQuery = remember(encodedQuery) {
        try {
            URLDecoder.decode(encodedQuery, "UTF-8")
        } catch (e: Exception) {
            encodedQuery
        }
    }

    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(decodedQuery, TextRange(decodedQuery.length)))
    }

    var isSearchBarExpanded by rememberSaveable { mutableStateOf(false) }
 
    val onSearch: (String) -> Unit = remember {
        { searchQuery ->
            if (searchQuery.isNotEmpty()) {
                focusManager.clearFocus()
                isSearchBarExpanded = false
                navController.navigate("search/${URLEncoder.encode(searchQuery, "UTF-8")}") {
                    popUpTo("search/${URLEncoder.encode(decodedQuery, "UTF-8")}") {
                        inclusive = true
                    }
                }
            }
        }
    }

    // Update query when decodedQuery changes
    LaunchedEffect(decodedQuery) {
        query = TextFieldValue(decodedQuery, TextRange(decodedQuery.length))
    }

    val searchFilter by viewModel.filter.collectAsState()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }
    
    // Suggestion states
    val suggestionViewState by suggestionViewModel.viewState.collectAsState()
    val database = LocalDatabase.current

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item: YTItem ->
        val longClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem ->
                        YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is AlbumItem ->
                        YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is ArtistItem ->
                        YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss,
                        )

                    is PlaylistItem ->
                        YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                }
            }
        }
        YouTubeListItem(
            item = item,
            isActive =
            when (item) {
                is SongItem -> mediaMetadata?.id == item.id
                is AlbumItem -> mediaMetadata?.album?.id == item.id
                else -> false
            },
            isPlaying = isPlaying,
            trailingContent = {
                IconButton(
                    onClick = longClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                    )
                }
            },
            modifier =
            Modifier
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata()
                                        )
                                    )
                                }
                            }

                            is AlbumItem -> navController.navigate("album/${item.id}")
                            is ArtistItem -> navController.navigate("artist/${item.id}")
                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                        }
                    },
                    onLongClick = longClick,
                )
                .animateItem(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
    ) {
        // Google-style SearchBar with Material 3 design
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query.text,
                    onQueryChange = { newQuery ->
                        query = TextFieldValue(newQuery, TextRange(newQuery.length))
                    },
                    onSearch = { searchQuery ->
                        onSearch(searchQuery)
                    },
                    expanded = isSearchBarExpanded,
                    onExpandedChange = { expanded ->
                        isSearchBarExpanded = expanded
                        if (expanded) {
                            coroutineScope.launch {
                                focusRequester.requestFocus()
                            }
                        } else {
                            focusManager.clearFocus()
                        }
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_yt_music),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        IconButton(
                            onClick = {
                                if (isSearchBarExpanded) {
                                    isSearchBarExpanded = false
                                    focusManager.clearFocus()
                                } else {
                                    navController.navigateUp()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isSearchBarExpanded) R.drawable.arrow_back else R.drawable.search
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (query.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = TextFieldValue("")
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(focusRequester)
                )
            },
            expanded = isSearchBarExpanded,
            onExpandedChange = { expanded ->
                isSearchBarExpanded = expanded
                if (!expanded) {
                    focusManager.clearFocus()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = SearchBarDefaults.colors(
                containerColor = if (pureBlack) 
                    MaterialTheme.colorScheme.surface 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                dividerColor = Color.Transparent
            ),
            tonalElevation = 6.dp,
            shadowElevation = 0.dp
        ) {
            // Real search suggestions content using ViewModel
            if (isSearchBarExpanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Search history items
                    items(suggestionViewState.history, key = { "history_${it.query}" }) { history ->
                        SuggestionItem(
                            query = history.query,
                            online = false,
                            onClick = {
                                onSearch(history.query)
                                isSearchBarExpanded = false
                            },
                            onDelete = {
                                database.query {
                                    delete(history)
                                }
                            },
                            onFillTextField = {
                                query = TextFieldValue(history.query, TextRange(history.query.length))
                            },
                            pureBlack = pureBlack
                        )
                    }

                    // Online suggestions
                    items(suggestionViewState.suggestions, key = { "suggestion_$it" }) { suggestion ->
                        SuggestionItem(
                            query = suggestion,
                            online = true,
                            onClick = {
                                onSearch(suggestion)
                                isSearchBarExpanded = false
                            },
                            onFillTextField = {
                                query = TextFieldValue(suggestion, TextRange(suggestion.length))
                            },
                            pureBlack = pureBlack
                        )
                    }

                    // Recommended items from YouTube
                    if (suggestionViewState.items.isNotEmpty() && suggestionViewState.history.size + suggestionViewState.suggestions.size > 0) {
                        item(key = "search_divider") {
                            HorizontalDivider()
                        }
                    }

                    items(suggestionViewState.items, key = { "item_${it.id}" }) { item ->
                        YouTubeListItem(
                            item = item,
                            isActive = when (item) {
                                is SongItem -> mediaMetadata?.id == item.id
                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                else -> false
                            },
                            isPlaying = isPlaying,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            when (item) {
                                                is SongItem -> YouTubeSongMenu(
                                                    song = item,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                                is AlbumItem -> YouTubeAlbumMenu(
                                                    albumItem = item,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                                is ArtistItem -> YouTubeArtistMenu(
                                                    artist = item,
                                                    onDismiss = menuState::dismiss
                                                )
                                                is PlaylistItem -> YouTubePlaylistMenu(
                                                    playlist = item,
                                                    coroutineScope = coroutineScope,
                                                    onDismiss = menuState::dismiss
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    when (item) {
                                        is SongItem -> {
                                            if (item.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubeQueue.radio(item.toMediaMetadata())
                                                )
                                                isSearchBarExpanded = false
                                            }
                                        }
                                        is AlbumItem -> {
                                            navController.navigate("album/${item.id}")
                                            isSearchBarExpanded = false
                                        }
                                        is ArtistItem -> {
                                            navController.navigate("artist/${item.id}")
                                            isSearchBarExpanded = false
                                        }
                                        is PlaylistItem -> {
                                            navController.navigate("online_playlist/${item.id}")
                                            isSearchBarExpanded = false
                                        }
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        when (item) {
                                            is SongItem -> YouTubeSongMenu(
                                                song = item,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                            is AlbumItem -> YouTubeAlbumMenu(
                                                albumItem = item,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                            is ArtistItem -> YouTubeArtistMenu(
                                                artist = item,
                                                onDismiss = menuState::dismiss
                                            )
                                            is PlaylistItem -> YouTubePlaylistMenu(
                                                playlist = item,
                                                coroutineScope = coroutineScope,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                }
                            )
                        )
                    }
                }
            }
        }

        // Main content area below search bar (only show when search bar is not expanded)
        if (!isSearchBarExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
            ChipsRow(
                chips = listOf(
                    null to stringResource(R.string.filter_all),
                    FILTER_SONG to stringResource(R.string.filter_songs),
                    FILTER_VIDEO to stringResource(R.string.filter_videos),
                    FILTER_ALBUM to stringResource(R.string.filter_albums),
                    FILTER_ARTIST to stringResource(R.string.filter_artists),
                    FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                    FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
                ),
                currentValue = searchFilter,
                onValueUpdate = {
                    if (viewModel.filter.value != it) {
                        viewModel.filter.value = it
                    }
                    coroutineScope.launch {
                        lazyListState.animateScrollToItem(0)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (searchFilter == null) {
                    searchSummary?.summaries?.forEach { summary ->
                        item {
                            NavigationTitle(summary.title)
                        }

                        items(
                            items = summary.items,
                            key = { "${summary.title}/${it.id}/${summary.items.indexOf(it)}" },
                            itemContent = ytItemContent,
                        )
                    }

                    if (searchSummary?.summaries?.isEmpty() == true) {
                        item {
                            EmptyPlaceholder(
                                icon = R.drawable.search,
                                text = stringResource(R.string.no_results_found),
                            )
                        }
                    }
                } else {
                    items(
                        items = itemsPage?.items.orEmpty().distinctBy { it.id },
                        key = { "filtered_${it.id}" },
                        itemContent = ytItemContent,
                    )

                    if (itemsPage?.continuation != null) {
                        item(key = "loading") {
                            ShimmerHost {
                                repeat(3) {
                                    ListItemPlaceHolder()
                                }
                            }
                        }
                    }

                    if (itemsPage?.items?.isEmpty() == true) {
                        item {
                            EmptyPlaceholder(
                                icon = R.drawable.search,
                                text = stringResource(R.string.no_results_found),
                            )
                        }
                    }
                }

                if (searchFilter == null && searchSummary == null || searchFilter != null && itemsPage == null) {
                    item {
                        ShimmerHost {
                            repeat(8) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight))
                }
            }
            }
        }
    }

    // Auto-focus when search bar is expanded
    LaunchedEffect(isSearchBarExpanded) {
        if (isSearchBarExpanded) {
            focusRequester.requestFocus()
        }
    }
    
    // Update suggestions when query changes
    LaunchedEffect(query.text) {
        snapshotFlow { query.text }.debounce(300L).collectLatest {
            suggestionViewModel.query.value = it
        }
    }
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
    pureBlack: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SuggestionItemHeight)
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
    ) {
        Icon(
            painterResource(if (online) R.drawable.search else R.drawable.history),
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 16.dp).alpha(0.5f)
        )

        Text(
            text = query,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!online) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.alpha(0.5f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                )
            }
        }

        IconButton(
            onClick = onFillTextField,
            modifier = Modifier.alpha(0.5f),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_top_left),
                contentDescription = null,
            )
        }
    }
}
