package com.greyhaze.medialauncher.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.greyhaze.medialauncher.data.AppDatabase
import com.greyhaze.medialauncher.data.PlaylistEntity
import com.greyhaze.medialauncher.data.PlaylistTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val contentUri: String,
    val artworkUri: String,
    val dataPath: String = ""
)

data class MusicAlbum(
    val albumId: Long,
    val title: String,
    val artist: String,
    val artworkUri: String,
    val tracks: List<MusicTrack>
)

data class MusicArtist(
    val name: String,
    val tracks: List<MusicTrack>
)

class MusicRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val playlistDao = db.musicPlaylistDao()

    fun observePlaylists() = playlistDao.observePlaylists()
    fun observeTracksForPlaylist(playlistId: Long) = playlistDao.observeTracksForPlaylist(playlistId)

    suspend fun loadAllMusicTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
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
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                val albumArtBase = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val lowerPath = dataPath.lowercase(Locale.US)
                    if (lowerPath.contains("/audiobooks/") || lowerPath.contains("/podcasts/") || lowerPath.endsWith(".m4b")) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val artist = (cursor.getString(artistCol) ?: "Unknown Artist").let {
                        if (it == "<unknown>") "Unknown Artist" else it
                    }
                    val album = (cursor.getString(albumCol) ?: "Unknown Album").let {
                        if (it == "<unknown>") "Unknown Album" else it
                    }
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                    val artworkUri = ContentUris.withAppendedId(albumArtBase, albumId).toString()

                    tracks.add(
                        MusicTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = albumId,
                            durationMs = durationMs,
                            contentUri = contentUri,
                            artworkUri = artworkUri,
                            dataPath = dataPath
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        tracks
    }

    fun groupAlbums(tracks: List<MusicTrack>): List<MusicAlbum> {
        return tracks.groupBy { it.albumId }
            .map { (albumId, albumTracks) ->
                val first = albumTracks.first()
                MusicAlbum(
                    albumId = albumId,
                    title = first.album,
                    artist = first.artist,
                    artworkUri = first.artworkUri,
                    tracks = albumTracks
                )
            }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun groupArtists(tracks: List<MusicTrack>): List<MusicArtist> {
        return tracks.groupBy { it.artist }
            .map { (artistName, artistTracks) ->
                MusicArtist(
                    name = artistName,
                    tracks = artistTracks
                )
            }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylist(PlaylistEntity(name = name.trim()))
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlist)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) = withContext(Dispatchers.IO) {
        val current = playlistDao.getTracksForPlaylist(playlistId)
        playlistDao.insertPlaylistTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                mediaStoreAudioId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                contentUri = track.contentUri,
                artworkUri = track.artworkUri,
                durationMs = track.durationMs,
                sortOrder = current.size
            )
        )
    }

    suspend fun removeTrackFromPlaylist(entity: PlaylistTrackEntity) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistTrack(entity)
    }

    suspend fun getAllPlaylists(): List<PlaylistEntity> = withContext(Dispatchers.IO) {
        playlistDao.getAllPlaylists()
    }
}

