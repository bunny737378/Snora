package arc.snora.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import arc.snora.player.FFmpegEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun loadThumbnail(
        context: Context,
        uriString: String,
        videoId: Long,
        videoPath: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(uriString)?.let { return@withContext it }

        var bitmap: Bitmap? = null

        val isMatroska = videoPath.lowercase().endsWith(".mkv") ||
                videoPath.lowercase().endsWith(".webm") ||
                uriString.lowercase().contains(".mkv") ||
                uriString.lowercase().contains(".webm")

        if (!isMatroska) {
            try {
                bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(uriString),
                        Size(240, 160),
                        null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        videoId,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: Exception) {
                bitmap = null
            }
        }

        if (bitmap == null && videoPath.isNotBlank()) {
            bitmap = FFmpegEngine.extractThumbnail(context, videoPath)
        }

        if (bitmap == null && isMatroska && uriString.isNotBlank()) {
            val resolvedPath = if (uriString.startsWith("file://")) {
                Uri.parse(uriString).path ?: ""
            } else {
                videoPath
            }
            if (resolvedPath.isNotBlank()) {
                bitmap = FFmpegEngine.extractThumbnail(context, resolvedPath)
            }
        }

        bitmap?.let {
            memoryCache.put(uriString, it)
        }
        bitmap
    }
}
