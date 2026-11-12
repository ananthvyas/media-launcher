package com.greyhaze.medialauncher.apps

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.greyhaze.medialauncher.R
import java.io.File

class AppsDrawerAdapter(
    val context: Context,
    private val onTileSelected: (String) -> Unit
) : RecyclerView.Adapter<AppsDrawerAdapter.ViewHolder>() {

    companion object {
        const val TILE_AUDIOBOOK = "native://audiobook"
        const val TILE_MUSIC = "native://music"
        const val TILE_PODCAST = "native://podcast"
    }

    private val appsList: MutableList<AppInfo> = mutableListOf()

    init {
        val pManager: PackageManager = context.packageManager

        // 1. Native Audiobook Tile
        appsList.add(
            AppInfo().apply {
                label = "Audiobook"
                packageName = TILE_AUDIOBOOK
                icon = AppCompatResources.getDrawable(context, R.drawable.audiobook)
                priority = 1
            }
        )

        // 2. External Library Tile (from assets/apk/library.apk if present)
        var libraryPkg: String? = null
        val absolutePath: String? = context.getExternalFilesDir("Download")?.absolutePath
        if (absolutePath != null) {
            val outputFile = File(File(absolutePath), "library.apk")
            if (outputFile.exists()) {
                val info = pManager.getPackageArchiveInfo(outputFile.path, 0)
                libraryPkg = info?.packageName
            }
        }
        appsList.add(
            AppInfo().apply {
                label = "Library"
                packageName = libraryPkg ?: "org.Onleihe"
                icon = AppCompatResources.getDrawable(context, R.drawable.library)
                priority = 2
            }
        )

        // 3. Native Music Tile
        appsList.add(
            AppInfo().apply {
                label = "Music"
                packageName = TILE_MUSIC
                icon = AppCompatResources.getDrawable(context, R.drawable.music)
                priority = 3
            }
        )

        // 4. Native Podcast Tile
        appsList.add(
            AppInfo().apply {
                label = "Podcast"
                packageName = TILE_PODCAST
                icon = AppCompatResources.getDrawable(context, R.drawable.podcast)
                priority = 4
            }
        )

        appsList.sortBy { it.priority }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var textView: TextView = itemView.findViewById(R.id.tv_app_name)
        var img: ImageView = itemView.findViewById(R.id.app_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = LayoutInflater.from(parent.context).inflate(
            R.layout.item_row_view_list,
            parent,
            false
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = appsList[position]
        val appLabel = item.label.toString()
        val target = item.packageName.toString()
        val appIcon = item.icon

        holder.textView.text = appLabel
        holder.img.setImageDrawable(appIcon)

        val clickListener = View.OnClickListener {
            onTileSelected(target)
        }
        holder.img.setOnClickListener(clickListener)
        holder.textView.setOnClickListener(clickListener)
        holder.itemView.setOnClickListener(clickListener)
    }

    override fun getItemCount(): Int {
        return appsList.size
    }
}
