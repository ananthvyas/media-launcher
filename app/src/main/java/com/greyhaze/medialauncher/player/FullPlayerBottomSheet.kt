package com.greyhaze.medialauncher.player

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.greyhaze.medialauncher.R
import java.util.Locale

class FullPlayerBottomSheet : BottomSheetDialogFragment() {
    private var artView: ImageView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var seekBar: SeekBar? = null
    private var elapsedView: TextView? = null
    private var durationView: TextView? = null
    private var btnPrev: ImageView? = null
    private var btnRew15: Button? = null
    private var btnPlayPause: ImageView? = null
    private var btnFf30: Button? = null
    private var btnNext: ImageView? = null
    private var btnChapters: Button? = null
    private var btnSleepTimer: Button? = null

    private var userIsSeeking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgressUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            refreshPlayerUi()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshPlayerUi()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlayerUi()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshPlayerUi()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_full_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        artView = view.findViewById(R.id.full_player_art)
        titleView = view.findViewById(R.id.full_player_title)
        subtitleView = view.findViewById(R.id.full_player_subtitle)
        seekBar = view.findViewById(R.id.full_player_seekbar)
        elapsedView = view.findViewById(R.id.full_player_elapsed)
        durationView = view.findViewById(R.id.full_player_duration)
        btnPrev = view.findViewById(R.id.btn_full_prev)
        btnRew15 = view.findViewById(R.id.btn_full_rew15)
        btnPlayPause = view.findViewById(R.id.btn_full_play_pause)
        btnFf30 = view.findViewById(R.id.btn_full_ff30)
        btnNext = view.findViewById(R.id.btn_full_next)
        btnChapters = view.findViewById(R.id.btn_full_chapters)
        btnSleepTimer = view.findViewById(R.id.btn_full_sleep_timer)

        btnPlayPause?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            if (player.isPlaying) player.pause() else player.play()
        }

        btnPrev?.setOnClickListener {
            PlayerControllerManager.controller?.seekToPrevious()
        }

        btnNext?.setOnClickListener {
            PlayerControllerManager.controller?.seekToNext()
        }

        btnRew15?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
        }

        btnFf30?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            val dur = player.duration
            val target = player.currentPosition + 30_000L
            player.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
        }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    elapsedView?.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                userIsSeeking = false
                val targetMs = bar?.progress?.toLong() ?: return
                PlayerControllerManager.controller?.seekTo(targetMs)
            }
        })

        btnChapters?.setOnClickListener {
            showChaptersDialog()
        }

        btnSleepTimer?.setOnClickListener {
            showSleepTimerDialog()
        }

        PlayerControllerManager.sleepTimerLabel.observe(viewLifecycleOwner) { label ->
            btnSleepTimer?.text = if (label.isNullOrEmpty()) "Sleep Timer: Off" else "Sleep Timer: $label"
        }

        PlayerControllerManager.initialize(requireContext()) { controller ->
            controller.addListener(playerListener)
            refreshPlayerUi()
        }
    }

    private fun refreshPlayerUi() {
        val player = PlayerControllerManager.controller ?: return
        val metadata = player.mediaMetadata

        titleView?.text = metadata.title?.toString() ?: getString(R.string.app_name)
        subtitleView?.text = metadata.artist?.toString() ?: metadata.subtitle?.toString() ?: ""

        val artworkUri = metadata.artworkUri
        if (artworkUri != null) {
            artView?.load(artworkUri) {
                placeholder(R.drawable.ic_music_note)
                error(R.drawable.ic_music_note)
            }
        } else {
            artView?.setImageResource(R.drawable.ic_music_note)
        }

        btnPlayPause?.setImageResource(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )

        val chapters = PlayerControllerManager.activeBookChapters
        if (chapters.size > 1) {
            btnChapters?.visibility = View.VISIBLE
            btnChapters?.text = "Chapters (${player.currentMediaItemIndex + 1}/${chapters.size})"
        } else {
            btnChapters?.visibility = View.GONE
        }

        updateProgressUi()
    }

    private fun updateProgressUi() {
        if (userIsSeeking) return
        val player = PlayerControllerManager.controller ?: return
        val dur = player.duration.coerceAtLeast(0L)
        val pos = player.currentPosition.coerceAtLeast(0L)

        seekBar?.max = dur.toInt().coerceAtLeast(1)
        seekBar?.progress = pos.toInt().coerceIn(0, dur.toInt().coerceAtLeast(1))
        elapsedView?.text = formatDuration(pos)
        durationView?.text = formatDuration(dur)
    }

    private fun showChaptersDialog() {
        val chapters = PlayerControllerManager.activeBookChapters
        if (chapters.isEmpty()) return
        val labels = chapters.mapIndexed { idx, ch ->
            "${idx + 1}. ${ch.title} (${formatDuration(ch.durationMs)})"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Chapter")
            .setItems(labels) { _, which ->
                PlayerControllerManager.controller?.seekToDefaultPosition(which)
                PlayerControllerManager.controller?.play()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            "Off",
            "15 minutes",
            "30 minutes",
            "45 minutes",
            "60 minutes",
            "End of current chapter / episode"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> PlayerControllerManager.setSleepTimerMinutes(0)
                    1 -> PlayerControllerManager.setSleepTimerMinutes(15)
                    2 -> PlayerControllerManager.setSleepTimerMinutes(30)
                    3 -> PlayerControllerManager.setSleepTimerMinutes(45)
                    4 -> PlayerControllerManager.setSleepTimerMinutes(60)
                    5 -> PlayerControllerManager.setSleepTimerEndOfTrack()
                }
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(progressUpdater)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(progressUpdater)
        super.onStop()
    }

    override fun onDestroyView() {
        PlayerControllerManager.controller?.removeListener(playerListener)
        super.onDestroyView()
    }

    companion object {
        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}

