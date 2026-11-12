package com.greyhaze.medialauncher.audiobooks

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.greyhaze.medialauncher.R
import com.greyhaze.medialauncher.data.AudiobookChapterEntity
import com.greyhaze.medialauncher.data.AudiobookEntity
import com.greyhaze.medialauncher.player.FullPlayerBottomSheet
import com.greyhaze.medialauncher.player.PlayerControllerManager
import kotlinx.coroutines.launch
import java.io.File

class AudiobooksFragment : Fragment() {
    private lateinit var repo: AudiobookRepository

    private var headerTitle: TextView? = null
    private var btnBack: Button? = null
    private var btnAction1: Button? = null
    private var btnAction2: Button? = null
    private var progressBar: ProgressBar? = null
    private var emptyText: TextView? = null
    private var recyclerView: RecyclerView? = null

    private var booksList: List<AudiobookEntity> = emptyList()
    private var selectedBook: AudiobookEntity? = null
    private var selectedBookChapters: List<AudiobookChapterEntity> = emptyList()

    companion object {
        private const val PREFS_NAME = "audiobook_prefs"
        private const val KEY_CUSTOM_DIRS = "custom_audiobook_dirs"
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
        repo = AudiobookRepository(requireContext())

        headerTitle = view.findViewById(R.id.module_header_title)
        btnBack = view.findViewById(R.id.btn_module_back)
        btnAction1 = view.findViewById(R.id.btn_module_action1)
        btnAction2 = view.findViewById(R.id.btn_module_action2)
        progressBar = view.findViewById(R.id.module_progress_bar)
        emptyText = view.findViewById(R.id.module_empty_text)
        recyclerView = view.findViewById(R.id.module_recycler_view)

        headerTitle?.text = "Audiobooks"
        btnAction1?.visibility = View.VISIBLE
        btnAction1?.text = "Scan"
        btnAction2?.visibility = View.VISIBLE
        btnAction2?.text = "+ Folder"

        btnBack?.setOnClickListener {
            if (selectedBook != null) {
                selectedBook = null
                renderCurrentState()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        btnAction1?.setOnClickListener {
            triggerScan()
        }

        btnAction2?.setOnClickListener {
            showAddFolderDialog()
        }

        repo.observeAudiobooks().observe(viewLifecycleOwner) { list ->
            booksList = list
            if (selectedBook == null) {
                renderCurrentState()
            }
        }

        triggerScan()
    }

    private fun triggerScan() {
        lifecycleScope.launch {
            progressBar?.visibility = View.VISIBLE
            repo.scanAndSyncAudiobooks(getCustomDirs())
            progressBar?.visibility = View.GONE
        }
    }

    private fun getCustomDirs(): List<String> {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CUSTOM_DIRS, emptySet())?.toList().orEmpty()
    }

    private fun showAddFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = "/sdcard/Audiobooks"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add Audiobook Folder")
            .setMessage("Standard folders (/sdcard/Audiobooks, /sdcard/Books, SD card) are scanned automatically. Add an extra folder path if needed:")
            .setView(input)
            .setPositiveButton("Add & Scan") { _, _ ->
                val path = input.text?.toString()?.trim().orEmpty()
                if (path.isNotEmpty()) {
                    val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val updated = prefs.getStringSet(KEY_CUSTOM_DIRS, emptySet()).orEmpty().toMutableSet()
                    updated.add(path)
                    prefs.edit().putStringSet(KEY_CUSTOM_DIRS, updated).apply()
                    triggerScan()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openBookDetail(book: AudiobookEntity) {
        selectedBook = book
        lifecycleScope.launch {
            selectedBookChapters = repo.getChaptersForBook(book.id)
            renderCurrentState()
        }
    }

    private fun renderCurrentState() {
        val book = selectedBook
        if (book != null) {
            headerTitle?.text = book.title
            btnAction1?.text = "Resume"
            btnAction1?.setOnClickListener {
                PlayerControllerManager.playAudiobook(
                    requireContext(),
                    book,
                    selectedBookChapters,
                    book.currentChapterIndex,
                    book.currentPositionMs
                )
            }
            btnAction2?.visibility = View.GONE

            recyclerView?.layoutManager = LinearLayoutManager(requireContext())
            recyclerView?.adapter = ChapterAdapter(book, selectedBookChapters)
            emptyText?.visibility = if (selectedBookChapters.isEmpty()) View.VISIBLE else View.GONE
            emptyText?.text = "No chapters found."
            return
        }

        headerTitle?.text = "Audiobooks"
        btnAction1?.text = "Scan"
        btnAction1?.setOnClickListener { triggerScan() }
        btnAction2?.visibility = View.VISIBLE
        btnAction2?.text = "+ Folder"

        recyclerView?.layoutManager = GridLayoutManager(requireContext(), 2)
        recyclerView?.adapter = BooksAdapter(booksList)
        emptyText?.visibility = if (booksList.isEmpty()) View.VISIBLE else View.GONE
        emptyText?.text = "No audiobooks found in /sdcard/Audiobooks or /sdcard/Books.\nPlace .m4b/.mp3 books there or tap '+ Folder'."
    }

    private inner class BooksAdapter(
        private val items: List<AudiobookEntity>
    ) : RecyclerView.Adapter<BooksAdapter.VH>() {

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
            val book = items[position]
            holder.title.text = book.title
            val durStr = FullPlayerBottomSheet.formatDuration(book.totalDurationMs)
            val resumeInfo = if (book.currentPositionMs > 0L || book.currentChapterIndex > 0) {
                "Ch ${book.currentChapterIndex + 1} (${FullPlayerBottomSheet.formatDuration(book.currentPositionMs)})"
            } else {
                durStr
            }
            holder.subtitle.text = if (book.author.isNotBlank()) "${book.author} • $resumeInfo" else resumeInfo

            val coverPath = book.coverArtPath
            if (!coverPath.isNullOrBlank() && File(coverPath).exists()) {
                holder.art.load(File(coverPath)) {
                    placeholder(R.drawable.audiobook)
                    error(R.drawable.audiobook)
                }
            } else {
                holder.art.setImageResource(R.drawable.audiobook)
            }

            holder.itemView.setOnClickListener {
                openBookDetail(book)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class ChapterAdapter(
        private val book: AudiobookEntity,
        private val chapters: List<AudiobookChapterEntity>
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

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
            val ch = chapters[position]
            val isCurrent = ch.chapterIndex == book.currentChapterIndex
            holder.title.text = if (isCurrent) "▶ ${ch.chapterIndex + 1}. ${ch.title}" else "${ch.chapterIndex + 1}. ${ch.title}"

            val durText = FullPlayerBottomSheet.formatDuration(ch.durationMs)
            holder.subtitle.text = if (isCurrent && book.currentPositionMs > 0L) {
                "$durText • Resumes at ${FullPlayerBottomSheet.formatDuration(book.currentPositionMs)}"
            } else {
                durText
            }

            val coverPath = book.coverArtPath
            if (!coverPath.isNullOrBlank() && File(coverPath).exists()) {
                holder.art.load(File(coverPath)) {
                    placeholder(R.drawable.audiobook)
                    error(R.drawable.audiobook)
                }
            } else {
                holder.art.setImageResource(R.drawable.audiobook)
            }

            holder.actionBtn.visibility = View.VISIBLE
            holder.actionBtn.text = if (isCurrent && book.currentPositionMs > 0L) "Resume" else "Play"
            holder.actionBtn.setOnClickListener {
                val startPos = if (isCurrent) book.currentPositionMs else 0L
                PlayerControllerManager.playAudiobook(
                    requireContext(),
                    book,
                    chapters,
                    ch.chapterIndex,
                    startPos
                )
            }
            holder.itemView.setOnClickListener {
                holder.actionBtn.performClick()
            }
        }

        override fun getItemCount(): Int = chapters.size
    }
}

