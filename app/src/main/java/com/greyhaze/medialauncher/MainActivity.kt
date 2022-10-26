package com.greyhaze.medialauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.greyhaze.medialauncher.apps.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {
    lateinit var mainHandler: Handler
    lateinit var audioManager: AudioManager

    var lastActive: Long = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAPK()
        setContentView(R.layout.activity_main)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, HomeScreenFragment())
            .commit()
        supportActionBar?.hide()
        window.decorView.setBackgroundColor(resources.getColor(R.color.app_background, null))
        window.navigationBarColor = resources.getColor(R.color.app_background, null)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post(updateTextTask)
        registerScreenEventReceiver()
    }

    private fun registerScreenEventReceiver() {
        val broadCastReceiver = object : BroadcastReceiver() {
            override fun onReceive(contxt: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SCREEN_OFF, ACTION_SCREEN_ON -> {
                        Log.i("MusicActiveCheckerService", "Screen is on/off now")
                        lastActive = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        this.registerReceiver(broadCastReceiver, IntentFilter(ACTION_SCREEN_OFF))
        this.registerReceiver(broadCastReceiver, IntentFilter(ACTION_SCREEN_ON))
    }

    private val updateTextTask = object : Runnable {
        override fun run() {
            if (audioManager.isMusicActive) {
                Log.i("MusicActiveCheckerService", "Music active, resetting clock")
                lastActive = SystemClock.elapsedRealtime()
            } else {
                lastActive.let {
                    if (SystemClock.elapsedRealtime() - it >= TimeUnit.MINUTES.toMillis(30)) {
                        Log.i("MusicActiveCheckerService", "Music inactive, powering down")
                        val i = Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN")
                        startActivity(i)
                    }
                }
            }
            mainHandler.postDelayed(this, 15000)
        }
    }
    override fun onBackPressed() {
        // Do nothing.
    }

    private fun installAPK() {
        val paths = applicationContext.assets.list("apk")
        if (paths != null) {
            for (path in paths) {
                prepareApk(path)?.let {
                    installApplication(applicationContext, //Uri.fromFile(it))
                    FileProvider.getUriForFile(
                        this,
                        applicationContext.packageName + ".provider",
                        it
                    ))
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
                    } catch (e: PackageManager.NameNotFoundException) {

                    }
                }
            }
            val fout = FileOutputStream(outputFile)
            val buffer = ByteArray(1024)
            var len1: Int
            var total: Long = 0
            while (ist.read(buffer).also { len1 = it } != -1) {
                total += len1.toLong()
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