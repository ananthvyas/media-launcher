package com.greyhaze.medialauncher.audiobooks

import android.content.Context
import android.media.MediaMetadataRetriever
import com.greyhaze.medialauncher.data.AppDatabase
import com.greyhaze.medialauncher.data.AudiobookChapterEntity
import com.greyhaze.medialauncher.data.AudiobookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

class AudiobookRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.audiobookDao()

    private val supportedExtensions = setOf("m4b", "m4a", "mp3", "aac", "ogg", "flac", "opus")

    fun observeAudiobooks() = dao.observeAudiobooks()

    suspend fun getChaptersForBook(bookId: String): List<AudiobookChapterEntity> = withContext(Dispatchers.IO) {
        dao.getChaptersForBook(bookId)
    }

    suspend fun scanAndSyncAudiobooks(customDirs: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        val candidateRoots = mutableListOf<File>()
        candidateRoots.add(File("/sdcard/Audiobooks"))
        candidateRoots.add(File("/sdcard/Books"))
        candidateRoots.add(File("/sdcard/Podcasts/Audiobooks"))

        // Also scan removable SD cards via getExternalFilesDirs(null)
        context.getExternalFilesDirs(null)?.forEach { appExtDir ->
            // appExtDir is e.g. /storage/3A9E-C748/Android/data/com.greyhaze.medialauncher/files
            val volRoot = appExtDir?.parentFile?.parentFile?.parentFile?.parentFile
            if (volRoot != null && volRoot.exists()) {
                candidateRoots.add(File(volRoot, "Audiobooks"))
                candidateRoots.add(File(volRoot, "Books"))
            }
        }

        // Fallback if /storage is readable
        File("/storage").listFiles()?.forEach { vol ->
            if (vol.isDirectory && vol.name != "emulated" && vol.name != "self") {
                candidateRoots.add(File(vol, "Audiobooks"))
                candidateRoots.add(File(vol, "Books"))
            }
        }

        for (custom in customDirs) {
            if (custom.isNotBlank()) candidateRoots.add(File(custom))
        }

        val existingMap = dao.getAllAudiobooks().associateBy { it.id }

        for (root in candidateRoots.distinctBy { it.absolutePath }) {
            if (!root.exists() || !root.isDirectory) continue
            val children = root.listFiles() ?: continue
            for (item in children) {
                if (item.name.startsWith("!") || item.name.startsWith(".")) continue
                if (item.isFile && isAudioFile(item)) {
                    processSingleFileBook(item, existingMap[item.absolutePath])
                } else if (item.isDirectory) {
                    processDirectoryBook(item, existingMap)
                }
            }
        }
    }

    private suspend fun processSingleFileBook(file: File, existing: AudiobookEntity?) {
        val bookId = file.absolutePath
        val mmr = MediaMetadataRetriever()
        var title = file.nameWithoutExtension
        var author = ""
        var durationMs = 0L
        var coverPath: String? = existing?.coverArtPath

        try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() && title == file.nameWithoutExtension }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { author = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)?.takeIf { it.isNotBlank() && author.isBlank() }?.let { author = it }
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            if (coverPath == null || !File(coverPath).exists()) {
                val sidecarJpg = File("${file.absolutePath}.jpg")
                val folderJpg = file.parentFile?.listFiles()?.firstOrNull {
                    it.isFile && it.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png", "webp")
                }
                if (sidecarJpg.exists()) {
                    coverPath = sidecarJpg.absolutePath
                } else if (folderJpg != null) {
                    coverPath = folderJpg.absolutePath
                } else {
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        coverPath = saveCoverBytes(bookId, artBytes)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        val parsedChapters = Mp4ChapterParser.extractChapters(file, durationMs)
        val chapters = if (parsedChapters.isNotEmpty()) {
            parsedChapters.mapIndexed { idx, ch ->
                AudiobookChapterEntity(
                    id = "$bookId#ch$idx",
                    bookId = bookId,
                    chapterIndex = idx,
                    title = ch.title,
                    filePath = file.absolutePath,
                    startOffsetMs = ch.startMs,
                    durationMs = ch.durationMs
                )
            }
        } else {
            // If a single long file has no embedded chapter markers, split into virtual 30-min chapters or 1 chapter
            listOf(
                AudiobookChapterEntity(
                    id = "$bookId#ch0",
                    bookId = bookId,
                    chapterIndex = 0,
                    title = title,
                    filePath = file.absolutePath,
                    startOffsetMs = 0L,
                    durationMs = durationMs
                )
            )
        }

        val entity = AudiobookEntity(
            id = bookId,
            title = title,
            author = author,
            rootPath = file.absolutePath,
            isSingleFile = true,
            coverArtPath = coverPath,
            totalDurationMs = durationMs,
            currentChapterIndex = existing?.currentChapterIndex ?: 0,
            currentPositionMs = existing?.currentPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L
        )
        dao.insertAudiobook(entity)
        dao.deleteChaptersForBook(bookId)
        dao.insertChapters(chapters)
    }

    private suspend fun processDirectoryBook(dir: File, existingMap: Map<String, AudiobookEntity>) {
        val audioFiles = dir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && isAudioFile(it) }
            .sortedBy { it.absolutePath.lowercase(Locale.US) }
            .toList()

        if (audioFiles.isEmpty()) return

        // If the folder contains a single .m4b file, treat it as a single-file book
        if (audioFiles.size == 1 && audioFiles[0].extension.lowercase(Locale.US) in setOf("m4b", "m4a")) {
            processSingleFileBook(audioFiles[0], existingMap[audioFiles[0].absolutePath])
            return
        }

        val bookId = dir.absolutePath
        val existing = existingMap[bookId]
        var bookTitle = dir.name
        var bookAuthor = ""
        var coverPath: String? = existing?.coverArtPath

        if (coverPath == null || !File(coverPath).exists()) {
            val folderImage = dir.listFiles()?.firstOrNull {
                it.isFile && it.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png", "webp")
            }
            if (folderImage != null) {
                coverPath = folderImage.absolutePath
            }
        }

        val chapters = mutableListOf<AudiobookChapterEntity>()
        var totalDuration = 0L

        audioFiles.forEachIndexed { idx, file ->
            val mmr = MediaMetadataRetriever()
            var chTitle = file.nameWithoutExtension
            var chDuration = 0L
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { chTitle = it }
                if (idx == 0) {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { bookTitle = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { bookAuthor = it }
                    if (coverPath == null) {
                        mmr.embeddedPicture?.let { bytes ->
                            if (bytes.isNotEmpty()) coverPath = saveCoverBytes(bookId, bytes)
                        }
                    }
                }
                chDuration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
            totalDuration += chDuration
            chapters.add(
                AudiobookChapterEntity(
                    id = "$bookId#ch$idx",
                    bookId = bookId,
                    chapterIndex = idx,
                    title = chTitle,
                    filePath = file.absolutePath,
                    startOffsetMs = 0L,
                    durationMs = chDuration
                )
            )
        }

        val entity = AudiobookEntity(
            id = bookId,
            title = bookTitle,
            author = bookAuthor,
            rootPath = dir.absolutePath,
            isSingleFile = false,
            coverArtPath = coverPath,
            totalDurationMs = totalDuration,
            currentChapterIndex = existing?.currentChapterIndex ?: 0,
            currentPositionMs = existing?.currentPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L
        )
        dao.insertAudiobook(entity)
        dao.deleteChaptersForBook(bookId)
        dao.insertChapters(chapters)
    }

    private fun saveCoverBytes(bookId: String, bytes: ByteArray): String? {
        return try {
            val coversDir = File(context.filesDir, "book_covers").apply { mkdirs() }
            val out = File(coversDir, "${bookId.hashCode().toUInt().toString(16)}.jpg")
            FileOutputStream(out).use { it.write(bytes) }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun isAudioFile(file: File): Boolean {
        return file.extension.lowercase(Locale.US) in supportedExtensions
    }
}

data class ParsedChapter(
    val title: String,
    val startMs: Long,
    val durationMs: Long
)

object Mp4ChapterParser {
    private val containerAtoms = setOf("moov", "udta", "trak", "mdia", "minf", "stbl")

    fun extractChapters(file: File, totalDurationMs: Long): List<ParsedChapter> {
        val ext = file.extension.lowercase(Locale.US)
        if (ext !in setOf("m4b", "m4a", "mp4")) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val rawList = mutableListOf<Pair<Long, String>>()
                scanAtoms(raf, 0L, raf.length(), rawList)
                if (rawList.isEmpty()) return emptyList()
                val sorted = rawList.sortedBy { it.first }
                sorted.mapIndexed { idx, (startMs, title) ->
                    val nextStart = if (idx + 1 < sorted.size) sorted[idx + 1].first else totalDurationMs.coerceAtLeast(startMs)
                    val dur = (nextStart - startMs).coerceAtLeast(1000L)
                    ParsedChapter(
                        title = title.ifBlank { "Chapter ${idx + 1}" },
                        startMs = startMs,
                        durationMs = dur
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scanAtoms(
        raf: RandomAccessFile,
        startPos: Long,
        endPos: Long,
        out: MutableList<Pair<Long, String>>
    ) {
        var pos = startPos
        while (pos + 8 <= endPos) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.US_ASCII)

            var headerSize = 8L
            val atomSize = when (size32) {
                1L -> {
                    if (pos + 16 > endPos) break
                    headerSize = 16L
                    raf.readLong()
                }
                0L -> endPos - pos
                else -> size32
            }

            if (atomSize < headerSize || pos + atomSize > endPos) break

            if (type == "chpl") {
                parseChplBox(raf, pos + headerSize, pos + atomSize, out)
                return
            } else if (type in containerAtoms) {
                scanAtoms(raf, pos + headerSize, pos + atomSize, out)
                if (out.isNotEmpty()) return
            }

            pos += atomSize
        }
    }

    private fun parseChplBox(
        raf: RandomAccessFile,
        payloadStart: Long,
        payloadEnd: Long,
        out: MutableList<Pair<Long, String>>
    ) {
        if (payloadStart + 9 > payloadEnd) return
        raf.seek(payloadStart)
        val version = raf.readUnsignedByte()
        // 3 bytes flags + 1 byte reserved
        raf.skipBytes(4)
        val count = if (version == 1) raf.readInt() else raf.readUnsignedByte()
        for (i in 0 until count) {
            if (raf.filePointer + 9 > payloadEnd) break
            val rawTimestamp = raf.readLong()
            // Nero chpl timestamps are in 100-nanosecond (10^-7 s) units -> divide by 10,000 for milliseconds
            val startMs = (rawTimestamp / 10_000L).coerceAtLeast(0L)
            val titleLen = raf.readUnsignedByte()
            if (raf.filePointer + titleLen > payloadEnd) break
            val titleBytes = ByteArray(titleLen)
            raf.readFully(titleBytes)
            val title = String(titleBytes, StandardCharsets.UTF_8).trim()
            out.add(startMs to title)
        }
    }
}

