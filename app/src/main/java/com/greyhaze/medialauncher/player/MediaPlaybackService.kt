package com.greyhaze.medialauncher.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.greyhaze.medialauncher.MainActivity
import com.greyhaze.medialauncher.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MediaPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var db: AppDatabase

    private val progressSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentPlaybackProgress()
            PlayerControllerManager.checkSleepTimer(player)
            mainHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveCurrentPlaybackProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveCurrentPlaybackProgress()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    PlayerControllerManager.onTrackOrChapterCompleted(player)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    markCurrentFinishedIfPodcast()
                    PlayerControllerManager.onTrackOrChapterCompleted(player)
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        mainHandler.post(progressSaveRunnable)
    }

    private fun saveCurrentPlaybackProgress() {
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: return
        val mediaType = extras.getString(PlayerControllerManager.EXTRA_MEDIA_TYPE) ?: return
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val durMs = player.duration.coerceAtLeast(0L)
        val currentIdx = player.currentMediaItemIndex

        serviceScope.launch {
            when (mediaType) {
                PlayerControllerManager.TYPE_PODCAST -> {
                    val episodeId = extras.getString(PlayerControllerManager.EXTRA_ITEM_ID) ?: return@launch
                    db.podcastDao().updateEpisodePosition(episodeId, posMs, durMs)
                    if (durMs > 0 && posMs >= durMs - 15_000L) {
                        db.podcastDao().markEpisodePlayed(episodeId, true)
                    }
                }
                PlayerControllerManager.TYPE_AUDIOBOOK -> {
                    val bookId = extras.getString(PlayerControllerManager.EXTRA_ITEM_ID) ?: return@launch
                    val chapterIdx = extras.getInt(PlayerControllerManager.EXTRA_CHAPTER_INDEX, currentIdx)
                    val chapterStartOffset = extras.getLong(PlayerControllerManager.EXTRA_CHAPTER_START_MS, 0L)
                    val relativePos = (posMs - chapterStartOffset).coerceAtLeast(0L)
                    db.audiobookDao().updateAudiobookProgress(
                        bookId = bookId,
                        chapterIndex = chapterIdx,
                        positionMs = relativePos,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun markCurrentFinishedIfPodcast() {
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: return
        val mediaType = extras.getString(PlayerControllerManager.EXTRA_MEDIA_TYPE) ?: return
        if (mediaType == PlayerControllerManager.TYPE_PODCAST) {
            val episodeId = extras.getString(PlayerControllerManager.EXTRA_ITEM_ID) ?: return
            serviceScope.launch {
                db.podcastDao().markEpisodePlayed(episodeId, true)
                db.podcastDao().updateEpisodePosition(episodeId, 0L, 0L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressSaveRunnable)
        saveCurrentPlaybackProgress()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}

