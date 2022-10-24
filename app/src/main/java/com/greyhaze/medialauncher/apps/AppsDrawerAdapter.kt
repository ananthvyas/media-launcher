package com.greyhaze.medialauncher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.greyhaze.medialauncher.R
import java.io.File


data class AppsDrawerAdapter(val context: Context) : RecyclerView.Adapter<AppsDrawerAdapter.ViewHolder>() {
    private var appsList: MutableList<AppInfo>? = null
    init {
        val pManager: PackageManager = context.packageManager
        appsList = mutableListOf()
        appsList?.let {
            val i = Intent(Intent.ACTION_MAIN, null)
            i.addCategory(Intent.CATEGORY_LAUNCHER)

            val paths = context.assets.list("apk")
            if (paths != null) {
                for (path in paths) {
                    val PATH: String? = context.getExternalFilesDir("Download")?.absolutePath
                    val file = PATH?.let { pth -> File(pth) }
                    val outputFile = File(file, path)
                    val info = pManager.getPackageArchiveInfo(outputFile.path, 0)
                    info?.let { apk ->
                        val app = AppInfo()
                        app.packageName = apk.packageName
                        try {
                            when(path) {
                                "audiobook.apk" -> {
                                    app.label = "Audiobook"
                                    app.icon = AppCompatResources.getDrawable(context, R.drawable.audiobook)
                                }
                                "library.apk" -> {
                                    app.label = "Library"
                                    app.icon = AppCompatResources.getDrawable(context, R.drawable.library)
                                }
                                "music.apk" -> {
                                    app.label = "Music"
                                    app.icon = AppCompatResources.getDrawable(context, R.drawable.music)
                                }
                                "podcast.apk" -> {
                                    app.label = "Podcast"
                                    app.icon = AppCompatResources.getDrawable(context, R.drawable.podcast)
                                }
                            }
                            it.add(app)
                        } catch (e: NameNotFoundException) {

                        }
                    }
                }
            }
        }
    }
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var textView: TextView
        var img: ImageView
        init {
            //Finds the views from our row.xml
            textView = itemView.findViewById(R.id.tv_app_name)
            img = itemView.findViewById(R.id.app_icon)
        }
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
        appsList?.let {
            val appLabel = it[position].label.toString()
            val appPackage = it[position].packageName.toString()
            val appIcon = it[position].icon

            holder.textView.text = appLabel
            holder.img.setImageDrawable(appIcon)
            holder.img.setOnClickListener {
                val intent = context.packageManager.getLaunchIntentForPackage(appPackage)
                context.startActivity(intent)
            }
        }
    }

    override fun getItemCount(): Int {
        return appsList?.size ?: 0
    }
}