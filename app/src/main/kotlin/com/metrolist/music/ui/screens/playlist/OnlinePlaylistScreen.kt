package com.metrolist.music.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.*
import com.metrolist.music.R
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.*
import com.metrolist.music.ui.menu.*
import com.metrolist.music.ui.utils.ItemWrapper
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.viewmodels.OnlinePlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OnlinePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: OnlinePlaylistViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.playlistSongs.collectAsState()
    val dbPlaylist by viewModel.dbPlaylist.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val error by viewModel.error.collectAsState()

    var selection by remember { mutableStateOf(false) }
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    val filteredSongs = remember(songs, query) {
        if (query.text.isEmpty()) songs.mapIndexed { i, s -> i to s }
        else songs.mapIndexed { i, s -> i to s }.filter {
            it.second.title.contains(query.text, true) ||
                it.second.artists.fastAny { a -> a.name.contains(query.text, true) }
        }
    }

    val wrappedSongs = remember(filteredSongs) {
        filteredSongs.map { ItemWrapper(it) }
    }.toMutableStateList()

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) { if (isSearching) focusRequester.requestFocus() }

    BackHandler(isSearching) {
        isSearching = false
        query = TextFieldValue()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
        ) {
            playlist?.let { playlist ->
                if (!isSearching) {
                    item(key = "playlist_header") {
                        OnlinePlaylistHeader(
                            playlist = playlist,
                            songs = songs,
                            dbPlaylist = dbPlaylist,
                            navController = navController,
                            coroutineScope = coroutineScope,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                itemsIndexed(wrappedSongs) { index, song ->
                    YouTubeListItem(
                        item = song.item.second,
                        isActive = mediaMetadata?.id == song.item.second.id,
                        isPlaying = isPlaying,
                        isSelected = song.isSelected && selection,
                        modifier = Modifier
                            .combinedClickable(
                                enabled = !hideExplicit || !song.item.second.explicit,
                                onClick = {
                                    if (song.item.second.id == mediaMetadata?.id)
                                        playerConnection.togglePlayPause()
                                    else
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = playlist.title,
                                                items = filteredSongs.map { it.second.toMediaItem() },
                                                startIndex = index
                                            )
                                        )
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selection = true
                                    wrappedSongs.forEach { it.isSelected = false }
                                    song.isSelected = true
                                }
                            )
                            .animateItem(),
                        trailingContent = {
                            IconButton(onClick = {
                                menuState.show {
                                    YouTubeSongMenu(song.item.second, navController, menuState::dismiss)
                                }
                            }) {
                                Icon(painterResource(R.drawable.more_vert), null)
                            }
                        }
                    )
                }
            }
        }

        TopAppBar(
            title = { if (lazyListState.firstVisibleItemIndex > 0) Text(playlist?.title.orEmpty()) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painterResource(R.drawable.arrow_back), null)
                }
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun OnlinePlaylistHeader(
    playlist: PlaylistItem,
    songs: List<SongItem>,
    dbPlaylist: Playlist?,
    navController: NavController,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(240.dp),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 24.dp
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(playlist.thumbnail).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            playlist.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (songs.isNotEmpty()) {
                Button(onClick = {
                    playerConnection.playQueue(ListQueue(playlist.title, songs.map { it.toMediaItem() }))
                }) {
                    Icon(painterResource(R.drawable.play), null)
                }
            }

            Button(onClick = {
                playerConnection.playQueue(
                    ListQueue(playlist.title, songs.map { it.toMediaItem() }.shuffled())
                )
            }) {
                Icon(painterResource(R.drawable.shuffle), null)
            }

            IconButton(onClick = {
                menuState.show {
                    YouTubePlaylistMenu(
                        playlist = playlist,
                        songs = songs,
                        coroutineScope = coroutineScope,
                        onDismiss = menuState::dismiss
                    )
                }
            }) {
                Icon(painterResource(R.drawable.more_vert), null)
            }
        }
    }
}
