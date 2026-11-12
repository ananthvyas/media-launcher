package com.greyhaze.medialauncher.music

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.greyhaze.medialauncher.R
import com.greyhaze.medialauncher.data.PlaylistEntity
import com.greyhaze.medialauncher.data.PlaylistTrackEntity
import com.greyhaze.medialauncher.player.FullPlayerBottomSheet
import com.greyhaze.medialauncher.player.PlayerControllerManager
import kotlinx.coroutines.launch

class MusicFragment : Fragment() {
    private lateinit var repo: MusicRepository

    private var headerTitle: TextView? = null
    private var btnBack: Button? = null
    private var btnAction1: Button? = null
    private var btnAction2: Button? = null
    private var tabBar: LinearLayout? = null
    private var btnTabAlbums: Button? = null
    private var btnTabArtists: Button? = null
    private var btnTabSongs: Button? = null
    private var btnTabPlaylists: Button? = null
    private var progressBar: ProgressBar? = null
    private var emptyText: TextView? = null
    private var recyclerView: RecyclerView? = null

    private var currentTab = 0 // 0=Albums, 1=Artists, 2=Songs, 3=Playlists

    private var allTracks: List<MusicTrack> = emptyList()
    private var albumsList: List<MusicAlbum> = emptyList()
    private var artistsList: List<MusicArtist> = emptyList()
    private var playlistsList: List<PlaylistEntity> = emptyList()

    private var selectedAlbum: MusicAlbum? = null
    private var selectedArtist: MusicArtist? = null
    private var selectedPlaylist: PlaylistEntity? = null
    private var selectedPlaylistTracks: List<PlaylistTrackEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_media_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = MusicRepository(requireContext())

        headerTitle = view.findViewById(R.id.module_header_title)
        btnBack = view.findViewById(R.id.btn_module_back)
        btnAction1 = view.findViewById(R.id.btn_module_action1)
        btnAction2 = view.findViewById(R.id.btn_module_action2)
        tabBar = view.findViewById(R.id.module_tab_bar)
        btnTabAlbums = view.findViewById(R.id.btn_tab_1)
        btnTabArtists = view.findViewById(R.id.btn_tab_2)
        btnTabSongs = view.findViewById(R.id.btn_tab_3)
        btnTabPlaylists = view.findViewById(R.id.btn_tab_4)
        progressBar = view.findViewById(R.id.module_progress_bar)
        emptyText = view.findViewById(R.id.module_empty_text)
        recyclerView = view.findViewById(R.id.module_recycler_view)

        headerTitle?.text = "Music"
        tabBar?.visibility = View.VISIBLE
        btnTabAlbums?.text = "Albums"
        btnTabArtists?.text = "Artists"
        btnTabSongs?.text = "Songs"
        btnTabPlaylists?.visibility = View.VISIBLE
        btnTabPlaylists?.text = "Playlists"

        btnAction1?.visibility = View.VISIBLE
        btnAction1?.text = "Shuffle All"
        btnAction2?.visibility = View.VISIBLE
        btnAction2?.text = "+ Playlist"

        btnBack?.setOnClickListener {
            if (selectedAlbum != null || selectedArtist != null || selectedPlaylist != null) {
                selectedAlbum = null
                selectedArtist = null
                selectedPlaylist = null
                renderCurrentState()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        btnAction1?.setOnClickListener {
            val activeTracks = when {
                selectedAlbum != null -> selectedAlbum!!.tracks
                selectedArtist != null -> selectedArtist!!.tracks
                selectedPlaylist != null -> selectedPlaylistTracks.map { it.toMusicTrack() }
                else -> allTracks
            }
            if (activeTracks.isNotEmpty()) {
                PlayerControllerManager.playMusicQueue(requireContext(), activeTracks, 0, shuffle = true)
            }
        }

        btnAction2?.setOnClickListener {
            showCreatePlaylistDialog()
        }

        btnTabAlbums?.setOnClickListener { switchTab(0) }
        btnTabArtists?.setOnClickListener { switchTab(1) }
        btnTabSongs?.setOnClickListener { switchTab(2) }
        btnTabPlaylists?.setOnClickListener { switchTab(3) }

        repo.observePlaylists().observe(viewLifecycleOwner) { list ->
            playlistsList = list
            if (currentTab == 3 && selectedPlaylist == null) {
                renderCurrentState()
            }
        }

        loadMusicLibrary()
    }

    private fun switchTab(tab: Int) {
        selectedAlbum = null
        selectedArtist = null
        selectedPlaylist = null
        currentTab = tab
        renderCurrentState()
    }

    private fun loadMusicLibrary() {
        lifecycleScope.launch {
            progressBar?.visibility = View.VISIBLE
            allTracks = repo.loadAllMusicTracks()
            albumsList = repo.groupAlbums(allTracks)
            artistsList = repo.groupArtists(allTracks)
            progressBar?.visibility = View.GONE
            renderCurrentState()
        }
    }

    private fun openPlaylistDetail(playlist: PlaylistEntity) {
        selectedPlaylist = playlist
        repo.observeTracksForPlaylist(playlist.id).observe(viewLifecycleOwner) { tracks ->
            if (selectedPlaylist?.id == playlist.id) {
                selectedPlaylistTracks = tracks
                renderCurrentState()
            }
        }
        renderCurrentState()
    }

    private fun renderCurrentState() {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.teal_200)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.now_playing_subtext)

        selectedAlbum?.let { album ->
            headerTitle?.text = album.title
            tabBar?.visibility = View.GONE
            btnAction2?.visibility = View.GONE
            recyclerView?.layoutManager = LinearLayoutManager(requireContext())
            recyclerView?.adapter = TracksAdapter(album.tracks)
            emptyText?.visibility = View.GONE
            return
        }

        selectedArtist?.let { artist ->
            headerTitle?.text = artist.name
            tabBar?.visibility = View.GONE
            btnAction2?.visibility = View.GONE
            recyclerView?.layoutManager = LinearLayoutManager(requireContext())
            recyclerView?.adapter = TracksAdapter(artist.tracks)
            emptyText?.visibility = View.GONE
            return
        }

        selectedPlaylist?.let { playlist ->
            headerTitle?.text = playlist.name
            tabBar?.visibility = View.GONE
            btnAction2?.visibility = View.VISIBLE
            btnAction2?.text = "Delete"
            btnAction2?.setOnClickListener {
                lifecycleScope.launch {
                    repo.deletePlaylist(playlist)
                    selectedPlaylist = null
                    renderCurrentState()
                }
            }
            val mapped = selectedPlaylistTracks.map { it.toMusicTrack() }
            recyclerView?.layoutManager = LinearLayoutManager(requireContext())
            recyclerView?.adapter = TracksAdapter(mapped, playlistEntities = selectedPlaylistTracks)
            emptyText?.visibility = if (mapped.isEmpty()) View.VISIBLE else View.GONE
            emptyText?.text = "Playlist is empty. Tap '+ List' on any song to add it."
            return
        }

        headerTitle?.text = "Music"
        tabBar?.visibility = View.VISIBLE
        btnAction2?.visibility = View.VISIBLE
        btnAction2?.text = "+ Playlist"
        btnAction2?.setOnClickListener { showCreatePlaylistDialog() }

        btnTabAlbums?.setTextColor(if (currentTab == 0) activeColor else inactiveColor)
        btnTabArtists?.setTextColor(if (currentTab == 1) activeColor else inactiveColor)
        btnTabSongs?.setTextColor(if (currentTab == 2) activeColor else inactiveColor)
        btnTabPlaylists?.setTextColor(if (currentTab == 3) activeColor else inactiveColor)

        when (currentTab) {
            0 -> {
                recyclerView?.layoutManager = GridLayoutManager(requireContext(), 2)
                recyclerView?.adapter = AlbumsAdapter(albumsList)
                emptyText?.visibility = if (albumsList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No music albums found on storage."
            }
            1 -> {
                recyclerView?.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = ArtistsAdapter(artistsList)
                emptyText?.visibility = if (artistsList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No artists found."
            }
            2 -> {
                recyclerView?.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = TracksAdapter(allTracks)
                emptyText?.visibility = if (allTracks.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No music tracks found."
            }
            3 -> {
                recyclerView?.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = PlaylistsAdapter(playlistsList)
                emptyText?.visibility = if (playlistsList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No playlists yet. Tap '+ Playlist' above to create one."
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Playlist Name"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        repo.createPlaylist(name)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTrackToPlaylistDialog(track: MusicTrack) {
        lifecycleScope.launch {
            val playlists = repo.getAllPlaylists()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "Create a playlist first using '+ Playlist'", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Add '${track.title}' to Playlist")
                .setItems(names) { _, which ->
                    val target = playlists[which]
                    lifecycleScope.launch {
                        repo.addTrackToPlaylist(target.id, track)
                        Toast.makeText(requireContext(), "Added to ${target.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun PlaylistTrackEntity.toMusicTrack(): MusicTrack {
        return MusicTrack(
            id = mediaStoreAudioId,
            title = title,
            artist = artist,
            album = album,
            albumId = 0L,
            durationMs = durationMs,
            contentUri = contentUri,
            artworkUri = artworkUri
        )
    }

    private inner class AlbumsAdapter(
        private val items: List<MusicAlbum>
    ) : RecyclerView.Adapter<AlbumsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val art: ImageView = view.findViewById(R.id.card_grid_art)
            val title: TextView = view.findViewById(R.id.card_grid_title)
            val subtitle: TextView = view.findViewById(R.id.card_grid_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_card_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val album = items[position]
            holder.title.text = album.title
            holder.subtitle.text = "${album.artist} • ${album.tracks.size} songs"
            holder.art.load(Uri.parse(album.artworkUri)) {
                placeholder(R.drawable.music)
                error(R.drawable.music)
            }
            holder.itemView.setOnClickListener {
                selectedAlbum = album
                renderCurrentState()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class ArtistsAdapter(
        private val items: List<MusicArtist>
    ) : RecyclerView.Adapter<ArtistsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val art: ImageView = view.findViewById(R.id.row_item_art)
            val title: TextView = view.findViewById(R.id.row_item_title)
            val subtitle: TextView = view.findViewById(R.id.row_item_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_list_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val artist = items[position]
            holder.title.text = artist.name
            holder.subtitle.text = "${artist.tracks.size} tracks"
            val firstArt = artist.tracks.firstOrNull()?.artworkUri.orEmpty()
            if (firstArt.isNotEmpty()) {
                holder.art.load(Uri.parse(firstArt)) {
                    placeholder(R.drawable.music)
                    error(R.drawable.music)
                }
            } else {
                holder.art.setImageResource(R.drawable.music)
            }
            holder.itemView.setOnClickListener {
                selectedArtist = artist
                renderCurrentState()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class PlaylistsAdapter(
        private val items: List<PlaylistEntity>
    ) : RecyclerView.Adapter<PlaylistsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val art: ImageView = view.findViewById(R.id.row_item_art)
            val title: TextView = view.findViewById(R.id.row_item_title)
            val subtitle: TextView = view.findViewById(R.id.row_item_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_list_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pl = items[position]
            holder.title.text = pl.name
            holder.subtitle.text = "Tap to view tracks"
            holder.art.setImageResource(R.drawable.music)
            holder.itemView.setOnClickListener {
                openPlaylistDetail(pl)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class TracksAdapter(
        private val items: List<MusicTrack>,
        private val playlistEntities: List<PlaylistTrackEntity>? = null
    ) : RecyclerView.Adapter<TracksAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val art: ImageView = view.findViewById(R.id.row_item_art)
            val title: TextView = view.findViewById(R.id.row_item_title)
            val subtitle: TextView = view.findViewById(R.id.row_item_subtitle)
            val actionBtn: Button = view.findViewById(R.id.row_item_action_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_list_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val track = items[position]
            holder.title.text = track.title
            holder.subtitle.text = "${track.artist} • ${FullPlayerBottomSheet.formatDuration(track.durationMs)}"

            if (track.artworkUri.isNotEmpty()) {
                holder.art.load(Uri.parse(track.artworkUri)) {
                    placeholder(R.drawable.music)
                    error(R.drawable.music)
                }
            } else {
                holder.art.setImageResource(R.drawable.music)
            }

            holder.actionBtn.visibility = View.VISIBLE
            if (playlistEntities != null && position < playlistEntities.size) {
                holder.actionBtn.text = "Remove"
                holder.actionBtn.setOnClickListener {
                    val entity = playlistEntities[position]
                    lifecycleScope.launch {
                        repo.removeTrackFromPlaylist(entity)
                    }
                }
            } else {
                holder.actionBtn.text = "+ List"
                holder.actionBtn.setOnClickListener {
                    showAddTrackToPlaylistDialog(track)
                }
            }

            holder.itemView.setOnClickListener {
                PlayerControllerManager.playMusicQueue(requireContext(), items, position, shuffle = false)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}

