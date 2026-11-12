package fr.reelmind.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Manual video download. Never invokes an AI provider. */
object VideoDownload {
    fun save(context: Context, url: String): String {
        val verified = ExtractionCoordinator.validateInstagramUrl(url)
        val folder = File(context.cacheDir, "video_dl_${System.nanoTime()}").apply { mkdirs() }
        try {
            LocalExtractor.initialize(context)
            val request = YoutubeDLRequest(verified)
            request.addOption("--no-playlist")
            request.addOption("-f", "best[ext=mp4]/best")
            request.addOption("--max-filesize", "500M")
            request.addOption("-o", File(folder, "reel.%(ext)s").absolutePath)
            YoutubeDL.getInstance().execute(request)
            val downloaded = folder.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
                ?: error("yt-dlp n'a pas créé de vidéo")
            val mime = if (downloaded.extension.equals("mp4", true)) "video/mp4" else "video/${downloaded.extension}"
            val filename = "ReelMind_${System.currentTimeMillis()}.${downloaded.extension}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReelMind")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Impossible de créer le fichier dans Téléchargements")
                try {
                    resolver.openOutputStream(destination)?.use { out -> downloaded.inputStream().use { it.copyTo(out) } }
                        ?: error("Impossible d'écrire dans Téléchargements")
                    resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { resolver.delete(destination, null, null); throw e }
            } else {
                // No broad storage permission needed on Android 8/9.
                val destFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("Stockage externe indisponible")
                destFolder.mkdirs()
                downloaded.copyTo(File(destFolder, filename))
            }
            return filename
        } finally { folder.deleteRecursively() }
    }
}
