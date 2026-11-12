package com.greyhaze.medialauncher.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val author: String = "",
    val artworkUrl: String = "",
    val description: String = "",
    val lastUpdated: Long = 0L
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["feedUrl"],
            childColumns = ["podcastFeedUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("podcastFeedUrl")]
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastFeedUrl: String,
    val podcastTitle: String,
    val title: String,
    val description: String = "",
    val pubDateMillis: Long = 0L,
    val durationMs: Long = 0L,
    val audioUrl: String,
    val artworkUrl: String = "",
    val localFilePath: String? = null,
    val downloadStatus: Int = DOWNLOAD_NONE, // 0=none, 1=downloading, 2=downloaded
    val lastPositionMs: Long = 0L,
    val isPlayed: Boolean = false
) {
    companion object {
        const val DOWNLOAD_NONE = 0
        const val DOWNLOAD_ACTIVE = 1
        const val DOWNLOAD_DONE = 2
    }
}

@Entity(tableName = "audiobooks")
data class AudiobookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val rootPath: String,
    val isSingleFile: Boolean,
    val coverArtPath: String? = null,
    val totalDurationMs: Long = 0L,
    val currentChapterIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val lastPlayedAt: Long = 0L
)

@Entity(
    tableName = "audiobook_chapters",
    foreignKeys = [
        ForeignKey(
            entity = AudiobookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class AudiobookChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val filePath: String,
    val startOffsetMs: Long = 0L,
    val durationMs: Long = 0L
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val mediaStoreAudioId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val contentUri: String,
    val artworkUri: String = "",
    val durationMs: Long = 0L,
    val sortOrder: Int = 0
)

