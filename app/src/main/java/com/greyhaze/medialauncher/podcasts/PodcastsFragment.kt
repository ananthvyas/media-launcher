package com.greyhaze.medialauncher.podcasts

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.greyhaze.medialauncher.R
import com.greyhaze.medialauncher.data.EpisodeEntity
import com.greyhaze.medialauncher.data.PodcastEntity
import com.greyhaze.medialauncher.player.FullPlayerBottomSheet
import com.greyhaze.medialauncher.player.PlayerControllerManager
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PodcastsFragment : Fragment() {
    private lateinit var repo: PodcastRepository

    private var headerTitle: TextView? = null
    private var btnBack: Button? = null
    private var btnAction1: Button? = null
    private var btnAction2: Button? = null
    private var tabBar: LinearLayout? = null
    private var btnTabSubscribed: Button? = null
    private var btnTabDownloads: Button? = null
    private var btnTabSearch: Button? = null
    private var searchBar: LinearLayout? = null
    private var searchInput: EditText? = null
    private var btnSearchGo: Button? = null
    private var progressBar: ProgressBar? = null
    private var emptyText: TextView? = null
    private var recyclerView: RecyclerView? = null

    private var currentTab = 0 // 0=Subscribed, 1=Downloads, 2=Discover/Search
    private var selectedPodcast: PodcastEntity? = null

    private var subscribedList: List<PodcastEntity> = emptyList()
    private var downloadedList: List<EpisodeEntity> = emptyList()
    private var podcastEpisodesList: List<EpisodeEntity> = emptyList()
    private var searchResultsList: List<PodcastSearchResult> = emptyList()

    private val opmlPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                progressBar?.visibility = View.VISIBLE
                val count = try {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        repo.importOpmlStream(stream)
                    } ?: 0
                } catch (_: Exception) {
                    0
                }
                progressBar?.visibility = View.GONE
                Toast.makeText(requireContext(), "Imported $count podcasts from OPML", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_media_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = PodcastRepository(requireContext())

        headerTitle = view.findViewById(R.id.module_header_title)
        btnBack = view.findViewById(R.id.btn_module_back)
        btnAction1 = view.findViewById(R.id.btn_module_action1)
        btnAction2 = view.findViewById(R.id.btn_module_action2)
        tabBar = view.findViewById(R.id.module_tab_bar)
        btnTabSubscribed = view.findViewById(R.id.btn_tab_1)
        btnTabDownloads = view.findViewById(R.id.btn_tab_2)
        btnTabSearch = view.findViewById(R.id.btn_tab_3)
        searchBar = view.findViewById(R.id.module_search_bar)
        searchInput = view.findViewById(R.id.module_search_input)
        btnSearchGo = view.findViewById(R.id.btn_module_search_go)
        progressBar = view.findViewById(R.id.module_progress_bar)
        emptyText = view.findViewById(R.id.module_empty_text)
        recyclerView = view.findViewById(R.id.module_recycler_view)

        headerTitle?.text = "Podcasts"
        tabBar?.visibility = View.VISIBLE
        btnTabSubscribed?.text = "Subscribed"
        btnTabDownloads?.text = "Downloads"
        btnTabSearch?.text = "Search"

        btnAction1?.visibility = View.VISIBLE
        btnAction1?.text = "Refresh"
        btnAction2?.visibility = View.VISIBLE
        btnAction2?.text = "OPML"

        btnBack?.setOnClickListener {
            if (selectedPodcast != null) {
                selectedPodcast = null
                renderCurrentState()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        btnAction1?.setOnClickListener {
            val pod = selectedPodcast
            lifecycleScope.launch {
                progressBar?.visibility = View.VISIBLE
                if (pod != null) {
                    repo.subscribeAndSyncPodcast(pod.feedUrl, pod.title, pod.author, pod.artworkUrl)
                } else {
                    repo.refreshAllPodcasts()
                }
                progressBar?.visibility = View.GONE
            }
        }

        btnAction2?.setOnClickListener {
            showOpmlImportDialog()
        }

        btnTabSubscribed?.setOnClickListener {
            selectedPodcast = null
            currentTab = 0
            renderCurrentState()
        }

        btnTabDownloads?.setOnClickListener {
            selectedPodcast = null
            currentTab = 1
            renderCurrentState()
        }

        btnTabSearch?.setOnClickListener {
            selectedPodcast = null
            currentTab = 2
            renderCurrentState()
        }

        btnSearchGo?.setOnClickListener {
            performSearch()
        }

        searchInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        repo.observeSubscribedPodcasts().observe(viewLifecycleOwner) { list ->
            subscribedList = list
            if (currentTab == 0 && selectedPodcast == null) {
                renderCurrentState()
            }
        }

        repo.observeDownloadedEpisodes().observe(viewLifecycleOwner) { list ->
            downloadedList = list
            if (currentTab == 1 && selectedPodcast == null) {
                renderCurrentState()
            }
        }

        lifecycleScope.launch {
            repo.scanLocalPodcastFolders()
        }

        renderCurrentState()
    }

    private fun performSearch() {
        val query = searchInput?.text?.toString().orEmpty()
        if (query.isBlank()) return
        lifecycleScope.launch {
            progressBar?.visibility = View.VISIBLE
            searchResultsList = repo.searchPodcasts(query)
            progressBar?.visibility = View.GONE
            renderCurrentState()
        }
    }

    private fun openPodcastDetail(podcast: PodcastEntity) {
        selectedPodcast = podcast
        repo.observeEpisodesForPodcast(podcast.feedUrl).observe(viewLifecycleOwner) { episodes ->
            if (selectedPodcast?.feedUrl == podcast.feedUrl) {
                podcastEpisodesList = episodes
                renderCurrentState()
            }
        }
        renderCurrentState()
    }

    private fun renderCurrentState() {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.teal_200)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.now_playing_subtext)

        val pod = selectedPodcast
        if (pod != null) {
            headerTitle?.text = pod.title
            tabBar?.visibility = View.GONE
            searchBar?.visibility = View.GONE
            btnAction2?.text = "Unsubscribe"
            btnAction2?.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Unsubscribe")
                    .setMessage("Remove ${pod.title} and its downloaded episodes?")
                    .setPositiveButton("Unsubscribe") { _, _ ->
                        lifecycleScope.launch {
                            repo.unsubscribePodcast(pod)
                            selectedPodcast = null
                            renderCurrentState()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            recyclerView?.layoutManager = LinearLayoutManager(requireContext())
            recyclerView?.adapter = EpisodeAdapter(podcastEpisodesList)
            emptyText?.visibility = if (podcastEpisodesList.isEmpty()) View.VISIBLE else View.GONE
            emptyText?.text = "No episodes loaded yet. Tap Refresh above."
            return
        }

        headerTitle?.text = "Podcasts"
        tabBar?.visibility = View.VISIBLE
        btnAction2?.text = "OPML"
        btnAction2?.setOnClickListener { showOpmlImportDialog() }

        btnTabSubscribed?.setTextColor(if (currentTab == 0) activeColor else inactiveColor)
        btnTabDownloads?.setTextColor(if (currentTab == 1) activeColor else inactiveColor)
        btnTabSearch?.setTextColor(if (currentTab == 2) activeColor else inactiveColor)

        when (currentTab) {
            0 -> {
                searchBar?.visibility = View.GONE
                recyclerView?.layoutManager = GridLayoutManager(requireContext(), 2)
                recyclerView?.adapter = SubscribedPodcastAdapter(subscribedList)
                emptyText?.visibility = if (subscribedList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No subscribed podcasts yet.\nTap 'Search' to find podcasts or 'OPML' to import."
            }
            1 -> {
                searchBar?.visibility = View.GONE
                recyclerView?.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = EpisodeAdapter(downloadedList)
                emptyText?.visibility = if (downloadedList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "No downloaded episodes."
            }
            2 -> {
                searchBar?.visibility = View.VISIBLE
                recyclerView?.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = SearchResultAdapter(searchResultsList)
                emptyText?.visibility = if (searchResultsList.isEmpty()) View.VISIBLE else View.GONE
                emptyText?.text = "Search for any podcast by title or topic above."
            }
        }
    }

    private fun showOpmlImportDialog() {
        val candidateFiles = mutableListOf<File>()
        listOf(
            File("/sdcard/Download"),
            File("/sdcard"),
            File("/sdcard/Podcasts")
        ).forEach { dir ->
            dir.listFiles()?.filter {
                it.isFile && it.extension.lowercase(Locale.US) in setOf("opml", "xml")
            }?.let { candidateFiles.addAll(it) }
        }

        val options = mutableListOf<String>()
        candidateFiles.forEach { options.add("Import ${it.name}") }
        options.add("Pick OPML file from storage...")
        options.add("Add RSS feed URL manually...")

        AlertDialog.Builder(requireContext())
            .setTitle("Import Podcasts / Add RSS")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < candidateFiles.size) {
                    val file = candidateFiles[which]
                    lifecycleScope.launch {
                        progressBar?.visibility = View.VISIBLE
                        val count = try {
                            FileInputStream(file).use { repo.importOpmlStream(it) }
                        } catch (_: Exception) {
                            0
                        }
                        progressBar?.visibility = View.GONE
                        Toast.makeText(requireContext(), "Imported $count podcasts", Toast.LENGTH_SHORT).show()
                    }
                } else if (which == candidateFiles.size) {
                    opmlPickerLauncher.launch("*/*")
                } else {
                    showManualRssDialog()
                }
            }
            .show()
    }

    private fun showManualRssDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://example.com/feed.xml"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Subscribe by RSS URL")
            .setView(input)
            .setPositiveButton("Subscribe") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    lifecycleScope.launch {
                        progressBar?.visibility = View.VISIBLE
                        repo.subscribeAndSyncPodcast(url)
                        progressBar?.visibility = View.GONE
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class SubscribedPodcastAdapter(
        private val items: List<PodcastEntity>
    ) : RecyclerView.Adapter<SubscribedPodcastAdapter.VH>() {

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
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.author
            if (item.artworkUrl.isNotBlank()) {
                holder.art.load(item.artworkUrl) {
                    placeholder(R.drawable.podcast)
                    error(R.drawable.podcast)
                }
            } else {
                holder.art.setImageResource(R.drawable.podcast)
            }
            holder.itemView.setOnClickListener {
                openPodcastDetail(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class SearchResultAdapter(
        private val items: List<PodcastSearchResult>
    ) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

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
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.author
            if (item.artworkUrl.isNotBlank()) {
                holder.art.load(item.artworkUrl) {
                    placeholder(R.drawable.podcast)
                    error(R.drawable.podcast)
                }
            } else {
                holder.art.setImageResource(R.drawable.podcast)
            }

            val isSub = subscribedList.any { it.feedUrl == item.feedUrl }
            holder.actionBtn.visibility = View.VISIBLE
            holder.actionBtn.text = if (isSub) "Open" else "+ Sub"
            holder.actionBtn.setOnClickListener {
                if (isSub) {
                    val pod = subscribedList.first { it.feedUrl == item.feedUrl }
                    openPodcastDetail(pod)
                } else {
                    lifecycleScope.launch {
                        progressBar?.visibility = View.VISIBLE
                        val ok = repo.subscribeAndSyncPodcast(
                            feedUrl = item.feedUrl,
                            fallbackTitle = item.title,
                            fallbackAuthor = item.author,
                            fallbackArt = item.artworkUrl
                        )
                        progressBar?.visibility = View.GONE
                        if (ok) {
                            Toast.makeText(requireContext(), "Subscribed to ${item.title}", Toast.LENGTH_SHORT).show()
                            notifyItemChanged(position)
                        }
                    }
                }
            }
            holder.itemView.setOnClickListener {
                holder.actionBtn.performClick()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class EpisodeAdapter(
        private val items: List<EpisodeEntity>
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

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
            val ep = items[position]
            holder.title.text = if (ep.isPlayed) "✓ ${ep.title}" else ep.title

            val dateStr = if (ep.pubDateMillis > 0L) dateFormat.format(Date(ep.pubDateMillis)) else ""
            val durStr = if (ep.durationMs > 0L) FullPlayerBottomSheet.formatDuration(ep.durationMs) else ""
            val resumeStr = if (ep.lastPositionMs > 5000L && !ep.isPlayed) {
                " • Left at ${FullPlayerBottomSheet.formatDuration(ep.lastPositionMs)}"
            } else {
                ""
            }
            holder.subtitle.text = listOf(dateStr, durStr).filter { it.isNotEmpty() }.joinToString(" • ") + resumeStr

            if (ep.artworkUrl.isNotBlank()) {
                holder.art.load(ep.artworkUrl) {
                    placeholder(R.drawable.podcast)
                    error(R.drawable.podcast)
                }
            } else {
                holder.art.setImageResource(R.drawable.podcast)
            }

            holder.actionBtn.visibility = View.VISIBLE
            when (ep.downloadStatus) {
                EpisodeEntity.DOWNLOAD_NONE -> {
                    holder.actionBtn.text = "Download"
                    holder.actionBtn.isEnabled = true
                    holder.actionBtn.setOnClickListener {
                        lifecycleScope.launch {
                            repo.downloadEpisode(ep)
                        }
                    }
                }
                EpisodeEntity.DOWNLOAD_ACTIVE -> {
                    holder.actionBtn.text = "Saving..."
                    holder.actionBtn.isEnabled = false
                }
                EpisodeEntity.DOWNLOAD_DONE -> {
                    holder.actionBtn.text = "Delete"
                    holder.actionBtn.isEnabled = true
                    holder.actionBtn.setOnClickListener {
                        lifecycleScope.launch {
                            repo.deleteEpisodeDownload(ep)
                        }
                    }
                }
            }

            holder.itemView.setOnClickListener {
                PlayerControllerManager.playPodcastEpisode(requireContext(), ep)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}

