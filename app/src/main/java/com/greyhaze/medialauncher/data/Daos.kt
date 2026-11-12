package com.greyhaze.medialauncher.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title ASC")
    fun observeSubscribedPodcasts(): LiveData<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts ORDER BY title ASC")
    suspend fun getSubscribedPodcasts(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getPodcast(feedUrl: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: PodcastEntity)

    @Delete
    suspend fun deletePodcast(podcast: PodcastEntity)

    @Query("SELECT * FROM episodes WHERE podcastFeedUrl = :feedUrl ORDER BY pubDateMillis DESC")
    fun observeEpisodesForPodcast(feedUrl: String): LiveData<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastFeedUrl = :feedUrl ORDER BY pubDateMillis DESC")
    suspend fun getEpisodesForPodcast(feedUrl: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE downloadStatus = 2 ORDER BY pubDateMillis DESC")
    fun observeDownloadedEpisodes(): LiveData<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodesIgnore(episodes: List<EpisodeEntity>)

    @Update
    suspend fun updateEpisode(episode: EpisodeEntity)

    @Query("UPDATE episodes SET lastPositionMs = :positionMs, durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :episodeId")
    suspend fun updateEpisodePosition(episodeId: String, positionMs: Long, durationMs: Long)

    @Query("UPDATE episodes SET isPlayed = :played WHERE id = :episodeId")
    suspend fun markEpisodePlayed(episodeId: String, played: Boolean)

    @Query("UPDATE episodes SET downloadStatus = :status, localFilePath = :localPath WHERE id = :episodeId")
    suspend fun updateEpisodeDownload(episodeId: String, status: Int, localPath: String?)
}

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobooks ORDER BY CASE WHEN lastPlayedAt > 0 THEN 0 ELSE 1 END, lastPlayedAt DESC, title ASC")
    fun observeAudiobooks(): LiveData<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks")
    suspend fun getAllAudiobooks(): List<AudiobookEntity>

    @Query("SELECT * FROM audiobooks WHERE id = :bookId LIMIT 1")
    suspend fun getAudiobookById(bookId: String): AudiobookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobook(book: AudiobookEntity)

    @Delete
    suspend fun deleteAudiobook(book: AudiobookEntity)

    @Query("SELECT * FROM audiobook_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersForBook(bookId: String): List<AudiobookChapterEntity>

    @Query("DELETE FROM audiobook_chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<AudiobookChapterEntity>)

    @Query("UPDATE audiobooks SET currentChapterIndex = :chapterIndex, currentPositionMs = :positionMs, lastPlayedAt = :timestamp WHERE id = :bookId")
    suspend fun updateAudiobookProgress(bookId: String, chapterIndex: Int, positionMs: Long, timestamp: Long)
}

@Dao
interface MusicPlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun observePlaylists(): LiveData<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY sortOrder ASC, id ASC")
    fun observeTracksForPlaylist(playlistId: Long): LiveData<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY sortOrder ASC, id ASC")
    suspend fun getTracksForPlaylist(playlistId: Long): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Delete
    suspend fun deletePlaylistTrack(track: PlaylistTrackEntity)
}

