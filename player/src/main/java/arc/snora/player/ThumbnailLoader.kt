package arc.snora.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailLoader(private val context: Context) {

    private val cache = LruCache<Long, Bitmap>(30)
    private var currentDataSource: String = ""
    private var retriever: MediaMetadataRetriever? = null

    fun setDataSource(source: String) {
        if (source != currentDataSource) {
            currentDataSource = source
            cache.evictAll()
            try {
                retriever?.release()
            } catch (t: Throwable) {
            }
            retriever = null
        }
    }

    private fun ensureRetriever(): MediaMetadataRetriever? {
        if (retriever == null && currentDataSource.isNotEmpty()) {
            try {
                val r = MediaMetadataRetriever()
                if (currentDataSource.startsWith("content://")) {
                    r.setDataSource(context, Uri.parse(currentDataSource))
                } else {
                    r.setDataSource(currentDataSource)
                }
                retriever = r
            } catch (t: Throwable) {
                retriever = null
            }
        }
        return retriever
    }

    private val lock = Any()

    suspend fun loadFrame(timeMs: Long): Bitmap? {
        val roundedSecond = (timeMs / 1000L).coerceAtLeast(0L)
        synchronized(lock) {
            val cached = cache.get(roundedSecond)
            if (cached != null) return cached
        }

        return withContext(Dispatchers.IO) {
            try {
                synchronized(lock) {
                    val r = ensureRetriever() ?: return@withContext null
                    val bmp: Bitmap? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        try {
                            r.getScaledFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108)
                        } catch (t: Throwable) {
                            null
                        }
                    } else null

                    val finalBmp = bmp ?: r.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        Bitmap.createScaledBitmap(it, 192, 108, true)
                    }

                    if (finalBmp != null) {
                        cache.put(roundedSecond, finalBmp)
                    }
                    finalBmp
                }
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun release() {
        try {
            retriever?.release()
        } catch (t: Throwable) {
        }
        retriever = null
        cache.evictAll()
    }
}
