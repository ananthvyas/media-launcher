package com.greyhaze.medialauncher.podcasts

import android.content.Context
import android.util.Xml
import com.greyhaze.medialauncher.data.AppDatabase
import com.greyhaze.medialauncher.data.EpisodeEntity
import com.greyhaze.medialauncher.data.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class PodcastSearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String
)

class PodcastRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.podcastDao()

    fun observeSubscribedPodcasts() = dao.observeSubscribedPodcasts()
    fun observeDownloadedEpisodes() = dao.observeDownloadedEpisodes()
    fun observeEpisodesForPodcast(feedUrl: String) = dao.observeEpisodesForPodcast(feedUrl)

    suspend fun searchPodcasts(query: String): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "MediaLauncher/2.0")
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            val out = mutableListOf<PodcastSearchResult>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val feedUrl = obj.optString("feedUrl")
                if (feedUrl.isNotBlank()) {
                    out.add(
                        PodcastSearchResult(
                            title = obj.optString("collectionName", "Unknown Podcast"),
                            author = obj.optString("artistName", ""),
                            feedUrl = feedUrl,
                            artworkUrl = obj.optString("artworkUrl600").ifEmpty { obj.optString("artworkUrl100") }
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun subscribeAndSyncPodcast(
        feedUrl: String,
        fallbackTitle: String = "",
        fallbackAuthor: String = "",
        fallbackArt: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "MediaLauncher/2.0")
        }
        try {
            conn.inputStream.use { stream ->
                parseRssFeedAndStore(stream, feedUrl, fallbackTitle, fallbackAuthor, fallbackArt)
            }
            true
        } catch (_: Exception) {
            if (fallbackTitle.isNotBlank()) {
                dao.insertPodcast(
                    PodcastEntity(
                        feedUrl = feedUrl,
                        title = fallbackTitle,
                        author = fallbackAuthor,
                        artworkUrl = fallbackArt,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
            false
        } finally {
            conn.disconnect()
        }
    }

    suspend fun refreshAllPodcasts() = withContext(Dispatchers.IO) {
        scanLocalPodcastFolders()
        val all = dao.getSubscribedPodcasts()
        for (p in all) {
            if (p.feedUrl.startsWith("http")) {
                subscribeAndSyncPodcast(p.feedUrl, p.title, p.author, p.artworkUrl)
            }
        }
    }

    suspend fun scanLocalPodcastFolders() = withContext(Dispatchers.IO) {
        val candidateRoots = mutableListOf(File("/sdcard/Podcasts"))
        context.getExternalFilesDirs(null)?.forEach { appExtDir ->
            val volRoot = appExtDir?.parentFile?.parentFile?.parentFile?.parentFile
            if (volRoot != null && volRoot.exists()) {
                candidateRoots.add(File(volRoot, "Podcasts"))
            }
        }
        val audioExts = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac")
        for (root in candidateRoots.distinctBy { it.absolutePath }) {
            if (!root.exists() || !root.isDirectory) continue
            val subdirs = root.listFiles()?.filter { it.isDirectory && !it.name.equals("Playlists", ignoreCase = true) } ?: continue
            for (podDir in subdirs) {
                val files = podDir.listFiles()?.filter {
                    it.isFile && it.extension.lowercase(Locale.US) in audioExts
                }?.sortedBy { it.name } ?: continue
                if (files.isEmpty()) continue

                val feedKey = "local://${podDir.absolutePath}"
                val coverFile = podDir.listFiles()?.firstOrNull {
                    it.isFile && it.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png", "webp")
                }
                val artUri = coverFile?.let { android.net.Uri.fromFile(it).toString() }.orEmpty()

                dao.insertPodcast(
                    PodcastEntity(
                        feedUrl = feedKey,
                        title = podDir.name.replace("_", ":"),
                        author = "Local Folder",
                        artworkUrl = artUri,
                        description = "Offline podcast folder (${files.size} episodes)",
                        lastUpdated = System.currentTimeMillis()
                    )
                )

                val episodes = files.map { f ->
                    EpisodeEntity(
                        id = "local_ep://${f.absolutePath}",
                        podcastFeedUrl = feedKey,
                        podcastTitle = podDir.name.replace("_", ":"),
                        title = f.nameWithoutExtension,
                        pubDateMillis = f.lastModified(),
                        audioUrl = android.net.Uri.fromFile(f).toString(),
                        artworkUrl = artUri,
                        localFilePath = f.absolutePath,
                        downloadStatus = EpisodeEntity.DOWNLOAD_DONE
                    )
                }
                dao.insertEpisodesIgnore(episodes)
            }
        }
    }

    suspend fun unsubscribePodcast(podcast: PodcastEntity) = withContext(Dispatchers.IO) {
        val episodes = dao.getEpisodesForPodcast(podcast.feedUrl)
        for (ep in episodes) {
            ep.localFilePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
        dao.deletePodcast(podcast)
    }

    suspend fun importOpmlStream(inputStream: InputStream): Int = withContext(Dispatchers.IO) {
        val feeds = mutableListOf<Triple<String, String, String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("outline", ignoreCase = true)) {
                    val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                        ?: parser.getAttributeValue(null, "xmlurl")
                    if (!xmlUrl.isNullOrBlank()) {
                        val title = parser.getAttributeValue(null, "title")
                            ?: parser.getAttributeValue(null, "text")
                            ?: xmlUrl
                        feeds.add(Triple(xmlUrl.trim(), title.trim(), ""))
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }

        var importedCount = 0
        for ((feedUrl, title, _) in feeds) {
            val ok = subscribeAndSyncPodcast(feedUrl, fallbackTitle = title)
            if (ok || title.isNotBlank()) importedCount++
        }
        importedCount
    }

    suspend fun downloadEpisode(episode: EpisodeEntity): Boolean = withContext(Dispatchers.IO) {
        dao.updateEpisodeDownload(episode.id, EpisodeEntity.DOWNLOAD_ACTIVE, null)
        val podcastsDir = File(context.getExternalFilesDir(null), "podcasts").apply { mkdirs() }
        val safeName = episode.id.hashCode().toUInt().toString(16) + ".mp3"
        val destFile = File(podcastsDir, safeName)

        val conn = (URL(episode.audioUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "MediaLauncher/2.0")
        }
        try {
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
            dao.updateEpisodeDownload(episode.id, EpisodeEntity.DOWNLOAD_DONE, destFile.absolutePath)
            true
        } catch (_: Exception) {
            destFile.delete()
            dao.updateEpisodeDownload(episode.id, EpisodeEntity.DOWNLOAD_NONE, null)
            false
        } finally {
            conn.disconnect()
        }
    }

    suspend fun deleteEpisodeDownload(episode: EpisodeEntity) = withContext(Dispatchers.IO) {
        episode.localFilePath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        dao.updateEpisodeDownload(episode.id, EpisodeEntity.DOWNLOAD_NONE, null)
    }

    private suspend fun parseRssFeedAndStore(
        stream: InputStream,
        feedUrl: String,
        fallbackTitle: String,
        fallbackAuthor: String,
        fallbackArt: String
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var channelTitle = fallbackTitle
        var channelAuthor = fallbackAuthor
        var channelArt = fallbackArt
        var channelDesc = ""

        val episodes = mutableListOf<EpisodeEntity>()

        var inItem = false
        var inImageTag = false
        var itemTitle = ""
        var itemGuid = ""
        var itemDesc = ""
        var itemPubDate = 0L
        var itemDuration = 0L
        var itemAudioUrl = ""
        var itemArtUrl = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == "item") {
                        inItem = true
                        itemTitle = ""
                        itemGuid = ""
                        itemDesc = ""
                        itemPubDate = 0L
                        itemDuration = 0L
                        itemAudioUrl = ""
                        itemArtUrl = ""
                    } else if (!inItem) {
                        when (tag) {
                            "title" -> if (!inImageTag && channelTitle.isBlank()) channelTitle = parser.nextText().trim()
                            "itunes:author", "author" -> if (channelAuthor.isBlank()) channelAuthor = parser.nextText().trim()
                            "description", "itunes:summary" -> if (channelDesc.isBlank()) channelDesc = stripHtml(parser.nextText())
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) channelArt = href.trim()
                            }
                            "image" -> inImageTag = true
                            "url" -> if (inImageTag && channelArt.isBlank()) channelArt = parser.nextText().trim()
                        }
                    } else {
                        when (tag) {
                            "title" -> itemTitle = parser.nextText().trim()
                            "guid" -> itemGuid = parser.nextText().trim()
                            "description", "itunes:summary", "content:encoded" -> {
                                if (itemDesc.isBlank()) itemDesc = stripHtml(parser.nextText())
                            }
                            "pubdate" -> itemPubDate = parseRfc822Date(parser.nextText().trim())
                            "itunes:duration" -> itemDuration = parseDurationToMs(parser.nextText().trim())
                            "enclosure" -> {
                                val urlAttr = parser.getAttributeValue(null, "url")
                                if (!urlAttr.isNullOrBlank()) itemAudioUrl = urlAttr.trim()
                            }
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) itemArtUrl = href.trim()
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == "image" && !inItem) {
                        inImageTag = false
                    } else if (tag == "item") {
                        inItem = false
                        if (itemAudioUrl.isNotBlank() && episodes.size < 150) {
                            val epId = "$feedUrl|${itemGuid.ifBlank { itemAudioUrl }}"
                            episodes.add(
                                EpisodeEntity(
                                    id = epId,
                                    podcastFeedUrl = feedUrl,
                                    podcastTitle = channelTitle.ifBlank { "Podcast" },
                                    title = itemTitle.ifBlank { "Episode" },
                                    description = itemDesc,
                                    pubDateMillis = itemPubDate,
                                    durationMs = itemDuration,
                                    audioUrl = itemAudioUrl,
                                    artworkUrl = itemArtUrl.ifBlank { channelArt }
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val podcast = PodcastEntity(
            feedUrl = feedUrl,
            title = channelTitle.ifBlank { fallbackTitle.ifBlank { "Podcast" } },
            author = channelAuthor,
            artworkUrl = channelArt,
            description = channelDesc,
            lastUpdated = System.currentTimeMillis()
        )
        dao.insertPodcast(podcast)
        if (episodes.isNotEmpty()) {
            dao.insertEpisodesIgnore(episodes)
        }
    }

    private fun stripHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseDurationToMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0] * 1000L
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> 0L
        }
    }

    private fun parseRfc822Date(raw: String): Long {
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val d = sdf.parse(raw)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }
}

