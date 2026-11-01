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

    suspend fun loadFrame(timeMs: Long): Bitmap? {
        val roundedSecond = (timeMs / 1000L).coerceAtLeast(0L)
        val cached = cache.get(roundedSecond)
        if (cached != null) return cached

        return withContext(Dispatchers.IO) {
            try {
                val r = ensureRetriever()
                val frame = r?.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, 160, 90, true)
                    cache.put(roundedSecond, scaled)
                    scaled
                } else {
                    null
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
