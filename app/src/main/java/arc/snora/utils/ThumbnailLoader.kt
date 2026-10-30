package arc.snora.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
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

        if (bitmap == null) {
            try {
                val retriever = MediaMetadataRetriever()
                if (videoPath.isNotBlank()) {
                    retriever.setDataSource(videoPath)
                } else {
                    retriever.setDataSource(context, Uri.parse(uriString))
                }
                bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
            } catch (e: Exception) {
                bitmap = null
            }
        }

        bitmap?.let {
            memoryCache.put(uriString, it)
        }
        bitmap
    }
}
