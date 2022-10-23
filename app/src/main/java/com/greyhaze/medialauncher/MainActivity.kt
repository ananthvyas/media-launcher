package com.greyhaze.medialauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.greyhaze.medialauncher.apps.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAPK()
        setContentView(R.layout.activity_main)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, HomeScreenFragment())
            .commit()
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
        val buffer = ByteArray(1024)
        var ist: InputStream? = null
        var fout: FileOutputStream? = null
        val PATH: String? = applicationContext.getExternalFilesDir("Download")?.absolutePath
        val file = PATH?.let { File(it) }
        val outputFile = File(file, assetName)
        try {
            ist = assets.open("apk/$assetName")
            if (outputFile.exists()) {
                val info = applicationContext.packageManager.getPackageArchiveInfo(outputFile.path, 0)
                info?.let { apk ->
                    val app = AppInfo()
                    app.packageName = apk.packageName
                    try {
                        val appInfo = applicationContext.packageManager.getApplicationInfo(apk.packageName, 0)
                        return null
                    } catch (e: PackageManager.NameNotFoundException) {

                    }
                }
            }
            fout = FileOutputStream(outputFile)
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

    private fun uriFromFile(context: Context, file: File): Uri? {
        return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file)
    }
}