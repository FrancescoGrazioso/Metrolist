/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalFilesViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    val songs =
        context.dataStore.data
            .map {
                it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged()
            .flatMapLatest { (sortType, descending) ->
                database.localSongs(sortType, descending)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun scanLocalFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                performScan()
            } catch (e: Exception) {
                Timber.tag("LocalFilesVM").e(e, "Error scanning local files")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun performScan() {
        val artworkDir = File(context.cacheDir, "artwork").also { it.mkdirs() }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val mediaStoreId = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: continue
                val artist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" }
                val album = cursor.getString(albumCol)?.takeIf { it != "<unknown>" }
                val durationMs = cursor.getLong(durationCol)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mediaStoreId.toString()
                ).toString()

                val songId = "local:$mediaStoreId"

                val existing = database.getSongByIdBlocking(songId)
                if (existing == null) {
                    val thumbnailUri = extractAndCacheArtwork(
                        contentUri, songId, artworkDir,
                    )

                    val songEntity = SongEntity(
                        id = songId,
                        title = title,
                        duration = (durationMs / 1000).toInt(),
                        thumbnailUrl = thumbnailUri,
                        albumName = album,
                        isLocal = true,
                        localPath = contentUri,
                        inLibrary = LocalDateTime.now(),
                    )

                    database.query {
                        upsert(songEntity)
                        if (artist != null) {
                            val existingArtist = artistByName(artist)
                            val artistId = existingArtist?.id ?: run {
                                val newArtist = ArtistEntity(
                                    id = ArtistEntity.generateArtistId(),
                                    name = artist
                                )
                                insert(newArtist)
                                newArtist.id
                            }
                            insert(SongArtistMap(songId = songId, artistId = artistId, position = 0))
                        }
                    }
                } else {
                    if (existing.song.localPath != contentUri) {
                        database.query { setLocalPath(songId, contentUri) }
                    }
                    // Backfill artwork for songs that were imported before artwork extraction
                    if (existing.song.thumbnailUrl == null) {
                        val thumbnailUri = extractAndCacheArtwork(
                            contentUri, songId, artworkDir,
                        )
                        if (thumbnailUri != null) {
                            database.query {
                                upsert(existing.song.copy(thumbnailUrl = thumbnailUri))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts embedded cover art from a local audio file via its content URI
     * and saves it to the artwork cache directory. Returns a file:// URI
     * pointing to the cached image, or null if no artwork is embedded.
     */
    private fun extractAndCacheArtwork(
        contentUri: String,
        songId: String,
        artworkDir: File,
    ): String? {
        val safeFileName = songId.replace(":", "_") + ".jpg"
        val artworkFile = File(artworkDir, safeFileName)

        if (artworkFile.exists()) {
            return Uri.fromFile(artworkFile).toString()
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(contentUri))
            val artBytes = retriever.embeddedPicture ?: return null
            artworkFile.writeBytes(artBytes)
            Uri.fromFile(artworkFile).toString()
        } catch (e: Exception) {
            Timber.tag("LocalFilesVM").w(e, "Failed to extract artwork for $songId")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
