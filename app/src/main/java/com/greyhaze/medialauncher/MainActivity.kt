package com.greyhaze.medialauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.load
import com.greyhaze.medialauncher.apps.AppInfo
import com.greyhaze.medialauncher.player.FullPlayerBottomSheet
import com.greyhaze.medialauncher.player.PlayerControllerManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var nowPlayingCard: CardView? = null
    private var nowPlayingContent: View? = null
    private var nowPlayingArt: ImageView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var btnPrev: ImageView? = null
    private var btnPlayPause: ImageView? = null
    private var btnNext: ImageView? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            refreshNowPlayingBar()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNowPlayingBar()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshNowPlayingBar()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshNowPlayingBar()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionIfNeeded()
        installAPK()
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.container, HomeScreenFragment())
                .commit()
        }

        supportActionBar?.hide()
        window.decorView.setBackgroundColor(resources.getColor(R.color.app_background, null))
        window.navigationBarColor = resources.getColor(R.color.app_background, null)

        setupGlobalNowPlayingBar()
    }

    private fun requestStoragePermissionIfNeeded() {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val missing = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            ActivityCompat.requestPermissions(this, perms, 1001)
        }
    }

    private fun setupGlobalNowPlayingBar() {
        nowPlayingCard = findViewById(R.id.global_now_playing_card)
        nowPlayingContent = findViewById(R.id.global_now_playing_content)
        nowPlayingArt = findViewById(R.id.global_now_playing_art)
        nowPlayingTitle = findViewById(R.id.global_now_playing_title)
        nowPlayingArtist = findViewById(R.id.global_now_playing_artist)
        btnPrev = findViewById(R.id.btn_global_prev)
        btnPlayPause = findViewById(R.id.btn_global_play_pause)
        btnNext = findViewById(R.id.btn_global_next)

        btnPlayPause?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            if (player.isPlaying) player.pause() else player.play()
        }

        btnPrev?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            if (player.hasPreviousMediaItem()) {
                player.seekToPrevious()
            } else {
                player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
            }
        }

        btnNext?.setOnClickListener {
            val player = PlayerControllerManager.controller ?: return@setOnClickListener
            if (player.hasNextMediaItem()) {
                player.seekToNext()
            } else {
                val dur = player.duration
                val target = player.currentPosition + 30_000L
                player.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
            }
        }

        val openFullPlayer = View.OnClickListener {
            FullPlayerBottomSheet().show(supportFragmentManager, "full_player_sheet")
        }
        nowPlayingCard?.setOnClickListener(openFullPlayer)
        nowPlayingContent?.setOnClickListener(openFullPlayer)
        nowPlayingArt?.setOnClickListener(openFullPlayer)
        nowPlayingTitle?.setOnClickListener(openFullPlayer)
        nowPlayingArtist?.setOnClickListener(openFullPlayer)

        PlayerControllerManager.initialize(this) { controller ->
            controller.addListener(playerListener)
            refreshNowPlayingBar()
        }
    }

    private fun refreshNowPlayingBar() {
        val player = PlayerControllerManager.controller
        val card = nowPlayingCard ?: return

        if (player == null || player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        val metadata = player.mediaMetadata

        nowPlayingTitle?.text = metadata.title?.toString() ?: getString(R.string.app_name)
        nowPlayingTitle?.isSelected = true

        val artist = metadata.artist?.toString() ?: metadata.subtitle?.toString() ?: ""
        if (artist.isNotEmpty()) {
            nowPlayingArtist?.text = artist
            nowPlayingArtist?.visibility = View.VISIBLE
        } else {
            nowPlayingArtist?.visibility = View.GONE
        }

        val artworkUri = metadata.artworkUri
        if (artworkUri != null) {
            nowPlayingArt?.load(artworkUri) {
                placeholder(R.drawable.ic_music_note)
                error(R.drawable.ic_music_note)
            }
        } else {
            nowPlayingArt?.setImageResource(R.drawable.ic_music_note)
        }

        btnPlayPause?.setImageResource(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    override fun onResume() {
        super.onResume()
        refreshNowPlayingBar()
    }

    override fun onDestroy() {
        PlayerControllerManager.controller?.removeListener(playerListener)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
        // When at the home screen, do nothing (acts as device HOME launcher).
    }

    private fun installAPK() {
        val paths = applicationContext.assets.list("apk")
        if (paths != null) {
            for (path in paths) {
                prepareApk(path)?.let {
                    installApplication(
                        applicationContext,
                        FileProvider.getUriForFile(
                            this,
                            applicationContext.packageName + ".provider",
                            it
                        )
                    )
                }
            }
        }
    }

    private fun installApplication(context: Context, filePath: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(
            filePath,
            "application/vnd.android.package-archive"
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun prepareApk(assetName: String): File? {
        val externalPath: String? = applicationContext.getExternalFilesDir("Download")?.absolutePath
        val file = externalPath?.let { File(it) }
        val outputFile = File(file, assetName)
        try {
            val ist = assets.open("apk/$assetName")
            if (outputFile.exists()) {
                val info = applicationContext.packageManager.getPackageArchiveInfo(outputFile.path, 0)
                info?.let { apk ->
                    val app = AppInfo()
                    app.packageName = apk.packageName
                    try {
                        applicationContext.packageManager.getApplicationInfo(apk.packageName, 0)
                        return null
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
            val fout = FileOutputStream(outputFile)
            val buffer = ByteArray(1024)
            var len1: Int
            while (ist.read(buffer).also { len1 = it } != -1) {
                fout.write(buffer, 0, len1)
            }
            fout.close()
            ist.close()
        } catch (e: IOException) {
            throw e
        }
        return outputFile
    }
}
