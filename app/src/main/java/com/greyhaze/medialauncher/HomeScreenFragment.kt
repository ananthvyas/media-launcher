package com.greyhaze.medialauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.greyhaze.medialauncher.apps.AppsDrawerAdapter

/**
 * Fragment to display the home page for media launcher with Now Playing overlay.
 */
class HomeScreenFragment : Fragment() {
    private var recyclerView: RecyclerView? = null
    private var adapter: RecyclerView.Adapter<*>? = null
    private var layoutManager: RecyclerView.LayoutManager? = null

    // Now Playing views
    private var nowPlayingCard: CardView? = null
    private var nowPlayingContent: View? = null
    private var nowPlayingArt: ImageView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var btnNowPlayingPrev: ImageView? = null
    private var btnNowPlayingPlayPause: ImageView? = null
    private var btnNowPlayingNext: ImageView? = null

    // Permission views
    private var permissionPromptCard: CardView? = null
    private var btnDismissPermission: ImageView? = null

    private var mediaSessionManager: MediaSessionManager? = null
    private var notificationListenerComponent: ComponentName? = null
    private var currentController: MediaController? = null
    private var isSessionsListenerRegistered = false

    companion object {
        private const val TAG = "HomeScreenFragment"
        private const val PREFS_NAME = "media_launcher_prefs"
        private const val PREF_DISMISSED_PERMISSION = "dismiss_permission_prompt"
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            refreshActiveSessions()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.appDrawer_recylerView)
        adapter = AppsDrawerAdapter(requireContext())
        layoutManager = GridLayoutManager(context, 2)
        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter

        view.findViewById<ImageView>(R.id.settings_button).setOnClickListener {
            context?.startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        // Initialize Now Playing overlay views
        nowPlayingCard = view.findViewById(R.id.now_playing_card)
        nowPlayingContent = view.findViewById(R.id.now_playing_content)
        nowPlayingArt = view.findViewById(R.id.now_playing_art)
        nowPlayingTitle = view.findViewById(R.id.now_playing_title)
        nowPlayingArtist = view.findViewById(R.id.now_playing_artist)
        btnNowPlayingPrev = view.findViewById(R.id.btn_now_playing_prev)
        btnNowPlayingPlayPause = view.findViewById(R.id.btn_now_playing_play_pause)
        btnNowPlayingNext = view.findViewById(R.id.btn_now_playing_next)

        // Initialize Permission prompt views
        permissionPromptCard = view.findViewById(R.id.permission_prompt_card)
        btnDismissPermission = view.findViewById(R.id.btn_dismiss_permission)

        notificationListenerComponent = ComponentName(requireContext(), MediaNotificationListenerService::class.java)
        mediaSessionManager = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

        setupClickListeners()
    }

    private fun setupClickListeners() {
        permissionPromptCard?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnDismissPermission?.setOnClickListener {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_DISMISSED_PERMISSION, true)
                ?.apply()
            permissionPromptCard?.visibility = View.GONE
        }

        btnNowPlayingPlayPause?.setOnClickListener {
            val controller = currentController ?: return@setOnClickListener
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }

        btnNowPlayingPrev?.setOnClickListener {
            currentController?.transportControls?.skipToPrevious()
        }

        btnNowPlayingNext?.setOnClickListener {
            currentController?.transportControls?.skipToNext()
        }

        val openAppListener = View.OnClickListener {
            openNowPlayingApp()
        }
        nowPlayingCard?.setOnClickListener(openAppListener)
        nowPlayingContent?.setOnClickListener(openAppListener)
        nowPlayingArt?.setOnClickListener(openAppListener)
        nowPlayingTitle?.setOnClickListener(openAppListener)
        nowPlayingArtist?.setOnClickListener(openAppListener)
    }

    private fun openNowPlayingApp() {
        val controller = currentController ?: return
        val sessionIntent = controller.sessionActivity
        if (sessionIntent != null) {
            try {
                sessionIntent.send()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error sending sessionActivity intent", e)
            }
        }

        try {
            val launchIntent = context?.packageManager?.getLaunchIntentForPackage(controller.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app for package: ${controller.packageName}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndSetupMedia()
    }

    override fun onPause() {
        super.onPause()
        tearDownMediaListeners()
    }

    private fun checkPermissionAndSetupMedia() {
        val context = context ?: return
        val isGranted = isNotificationAccessGranted()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean(PREF_DISMISSED_PERMISSION, false)

        if (!isGranted) {
            tearDownMediaListeners()
            nowPlayingCard?.visibility = View.GONE
            permissionPromptCard?.visibility = if (isDismissed) View.GONE else View.VISIBLE
        } else {
            permissionPromptCard?.visibility = View.GONE
            setupMediaSessionListener()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val context = context ?: return false
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabledPackages.contains(context.packageName)) {
            return true
        }
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(context.packageName)
    }

    private fun setupMediaSessionListener() {
        val manager = mediaSessionManager ?: return
        val component = notificationListenerComponent ?: return

        if (!isSessionsListenerRegistered) {
            try {
                manager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    component,
                    Handler(Looper.getMainLooper())
                )
                isSessionsListenerRegistered = true
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification listener access not granted yet", e)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error adding sessions listener", e)
                return
            }
        }
        refreshActiveSessions()
    }

    private fun refreshActiveSessions() {
        val manager = mediaSessionManager ?: return
        val component = notificationListenerComponent ?: return
        if (!isNotificationAccessGranted()) return

        try {
            val controllers = manager.getActiveSessions(component)
            updateControllers(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active sessions", e)
        }
    }

    private fun updateControllers(controllers: List<MediaController>?) {
        val newController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (newController == null) {
            currentController?.unregisterCallback(mediaCallback)
            currentController = null
            nowPlayingCard?.visibility = View.GONE
            return
        }

        if (currentController?.sessionToken != newController.sessionToken) {
            currentController?.unregisterCallback(mediaCallback)
            currentController = newController
            currentController?.registerCallback(mediaCallback, Handler(Looper.getMainLooper()))
        }

        updateMetadata(currentController?.metadata)
        updatePlaybackState(currentController?.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        val titleView = nowPlayingTitle ?: return
        val artistView = nowPlayingArtist ?: return
        val artView = nowPlayingArt ?: return

        val description = metadata?.description
        val title = description?.title?.toString()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: currentController?.packageName?.let { getAppLabel(it) }
            ?: getString(R.string.app_name)

        val artist = description?.subtitle?.toString()
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        titleView.text = title
        titleView.isSelected = true

        if (artist.isNotEmpty()) {
            artistView.text = artist
            artistView.visibility = View.VISIBLE
        } else {
            artistView.visibility = View.GONE
        }

        val bitmap = description?.iconBitmap
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (bitmap != null) {
            artView.setImageBitmap(bitmap)
        } else {
            val uri = description?.iconUri
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let { Uri.parse(it) }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { Uri.parse(it) }

            if (uri != null) {
                loadArtUri(uri, artView)
            } else {
                artView.setImageResource(R.drawable.ic_music_note)
            }
        }
    }

    private fun loadArtUri(uri: Uri, imageView: ImageView) {
        val resolver = context?.contentResolver ?: return
        Thread {
            try {
                val bitmap = resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (bitmap != null) {
                    imageView.post { imageView.setImageBitmap(bitmap) }
                } else {
                    imageView.post { imageView.setImageResource(R.drawable.ic_music_note) }
                }
            } catch (e: Exception) {
                imageView.post { imageView.setImageResource(R.drawable.ic_music_note) }
            }
        }.start()
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        val card = nowPlayingCard ?: return
        val playPauseBtn = btnNowPlayingPlayPause ?: return

        if (state == null || state.state == PlaybackState.STATE_NONE || state.state == PlaybackState.STATE_STOPPED) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        playPauseBtn.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun tearDownMediaListeners() {
        if (isSessionsListenerRegistered) {
            try {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing sessions listener", e)
            }
            isSessionsListenerRegistered = false
        }
        currentController?.unregisterCallback(mediaCallback)
        currentController = null
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = context?.packageManager ?: return null
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tearDownMediaListeners()
        recyclerView = null
        adapter = null
        layoutManager = null
        nowPlayingCard = null
        nowPlayingContent = null
        nowPlayingArt = null
        nowPlayingTitle = null
        nowPlayingArtist = null
        btnNowPlayingPrev = null
        btnNowPlayingPlayPause = null
        btnNowPlayingNext = null
        permissionPromptCard = null
        btnDismissPermission = null
    }
}
