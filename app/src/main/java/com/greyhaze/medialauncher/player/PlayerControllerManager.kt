package com.greyhaze.medialauncher.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.greyhaze.medialauncher.data.AudiobookChapterEntity
import com.greyhaze.medialauncher.data.AudiobookEntity
import com.greyhaze.medialauncher.data.EpisodeEntity
import com.greyhaze.medialauncher.music.MusicTrack
import java.io.File

object PlayerControllerManager {
    const val EXTRA_MEDIA_TYPE = "extra_media_type"
    const val EXTRA_ITEM_ID = "extra_item_id"
    const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
    const val EXTRA_CHAPTER_START_MS = "extra_chapter_start_ms"

    const val TYPE_PODCAST = "podcast"
    const val TYPE_AUDIOBOOK = "audiobook"
    const val TYPE_MUSIC = "music"

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    // Sleep timer state
    private var sleepTimerEndRealtimeMs: Long = 0L
    private var sleepAtEndOfChapterOrTrack: Boolean = false
    private val _sleepTimerLabel = MutableLiveData<String?>(null)
    val sleepTimerLabel: LiveData<String?> = _sleepTimerLabel

    // Current audiobook chapters (for the chapter selector in FullPlayerBottomSheet)
    var activeBookChapters: List<AudiobookChapterEntity> = emptyList()
        private set
    var activeBook: AudiobookEntity? = null
        private set

    fun initialize(context: Context, onReady: ((MediaController) -> Unit)? = null) {
        val existing = controller
        if (existing != null) {
            onReady?.invoke(existing)
            return
        }
        val appContext = context.applicationContext
        val sessionToken = SessionToken(appContext, ComponentName(appContext, MediaPlaybackService::class.java))
        val future = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    onReady?.invoke(c)
                } catch (_: Exception) {
                }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    fun playPodcastEpisode(context: Context, episode: EpisodeEntity) {
        initialize(context) { player ->
            activeBook = null
            activeBookChapters = emptyList()

            val uri = if (!episode.localFilePath.isNullOrEmpty() && File(episode.localFilePath).exists()) {
                Uri.fromFile(File(episode.localFilePath))
            } else {
                Uri.parse(episode.audioUrl)
            }

            val extras = Bundle().apply {
                putString(EXTRA_MEDIA_TYPE, TYPE_PODCAST)
                putString(EXTRA_ITEM_ID, episode.id)
            }

            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(episode.podcastTitle)
                .setSubtitle(episode.podcastTitle)
                .setExtras(extras)

            if (episode.artworkUrl.isNotEmpty()) {
                metadataBuilder.setArtworkUri(Uri.parse(episode.artworkUrl))
            }

            val mediaItem = MediaItem.Builder()
                .setMediaId(episode.id)
                .setUri(uri)
                .setMediaMetadata(metadataBuilder.build())
                .build()

            val startPos = if (episode.lastPositionMs > 3000L) episode.lastPositionMs - 2000L else episode.lastPositionMs
            player.setMediaItem(mediaItem, startPos.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
    }

    fun playAudiobook(
        context: Context,
        book: AudiobookEntity,
        chapters: List<AudiobookChapterEntity>,
        targetChapterIndex: Int = book.currentChapterIndex,
        targetPositionMs: Long = book.currentPositionMs
    ) {
        initialize(context) { player ->
            activeBook = book
            activeBookChapters = chapters

            val items = if (chapters.isEmpty()) {
                val extras = Bundle().apply {
                    putString(EXTRA_MEDIA_TYPE, TYPE_AUDIOBOOK)
                    putString(EXTRA_ITEM_ID, book.id)
                    putInt(EXTRA_CHAPTER_INDEX, 0)
                    putLong(EXTRA_CHAPTER_START_MS, 0L)
                }
                val meta = MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author.ifEmpty { "Audiobook" })
                    .setExtras(extras)
                    .apply {
                        book.coverArtPath?.let { setArtworkUri(Uri.fromFile(File(it))) }
                    }
                    .build()
                listOf(
                    MediaItem.Builder()
                        .setMediaId(book.id)
                        .setUri(Uri.fromFile(File(book.rootPath)))
                        .setMediaMetadata(meta)
                        .build()
                )
            } else {
                chapters.map { ch ->
                    val extras = Bundle().apply {
                        putString(EXTRA_MEDIA_TYPE, TYPE_AUDIOBOOK)
                        putString(EXTRA_ITEM_ID, book.id)
                        putInt(EXTRA_CHAPTER_INDEX, ch.chapterIndex)
                        putLong(EXTRA_CHAPTER_START_MS, ch.startOffsetMs)
                    }
                    val meta = MediaMetadata.Builder()
                        .setTitle("${book.title} — ${ch.title}")
                        .setArtist(book.author.ifEmpty { book.title })
                        .setSubtitle(ch.title)
                        .setExtras(extras)
                        .apply {
                            book.coverArtPath?.let { setArtworkUri(Uri.fromFile(File(it))) }
                        }
                        .build()

                    val builder = MediaItem.Builder()
                        .setMediaId(ch.id)
                        .setUri(Uri.fromFile(File(ch.filePath)))
                        .setMediaMetadata(meta)

                    if (book.isSingleFile && ch.durationMs > 0L) {
                        builder.setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(ch.startOffsetMs)
                                .setEndPositionMs(ch.startOffsetMs + ch.durationMs)
                                .build()
                        )
                    }
                    builder.build()
                }
            }

            val safeIdx = targetChapterIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val resumeOffset = if (targetPositionMs > 3000L) targetPositionMs - 2000L else targetPositionMs
            player.setMediaItems(items, safeIdx, resumeOffset.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
    }

    fun playMusicQueue(context: Context, tracks: List<MusicTrack>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        initialize(context) { player ->
            activeBook = null
            activeBookChapters = emptyList()

            val items = tracks.map { track ->
                val extras = Bundle().apply {
                    putString(EXTRA_MEDIA_TYPE, TYPE_MUSIC)
                    putString(EXTRA_ITEM_ID, track.id.toString())
                }
                val meta = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setExtras(extras)
                    .apply {
                        if (track.artworkUri.isNotEmpty()) {
                            setArtworkUri(Uri.parse(track.artworkUri))
                        }
                    }
                    .build()

                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setUri(Uri.parse(track.contentUri))
                    .setMediaMetadata(meta)
                    .build()
            }

            player.shuffleModeEnabled = shuffle
            player.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            player.prepare()
            player.play()
        }
    }

    fun setSleepTimerMinutes(minutes: Int) {
        if (minutes <= 0) {
            sleepTimerEndRealtimeMs = 0L
            sleepAtEndOfChapterOrTrack = false
            _sleepTimerLabel.postValue(null)
        } else {
            sleepAtEndOfChapterOrTrack = false
            sleepTimerEndRealtimeMs = System.currentTimeMillis() + minutes * 60_000L
            _sleepTimerLabel.postValue("${minutes}m")
        }
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerEndRealtimeMs = 0L
        sleepAtEndOfChapterOrTrack = true
        _sleepTimerLabel.postValue("End of track/chapter")
    }

    fun checkSleepTimer(player: Player) {
        if (sleepAtEndOfChapterOrTrack) {
            _sleepTimerLabel.postValue("End of track/chapter")
            return
        }
        val target = sleepTimerEndRealtimeMs
        if (target <= 0L) return
        val remainingMs = target - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            sleepTimerEndRealtimeMs = 0L
            _sleepTimerLabel.postValue(null)
            player.pause()
        } else {
            val mins = ((remainingMs + 59_999L) / 60_000L).toInt()
            _sleepTimerLabel.postValue("${mins}m")
        }
    }

    fun onTrackOrChapterCompleted(player: Player) {
        if (sleepAtEndOfChapterOrTrack) {
            sleepAtEndOfChapterOrTrack = false
            _sleepTimerLabel.postValue(null)
            player.pause()
        }
    }
}

