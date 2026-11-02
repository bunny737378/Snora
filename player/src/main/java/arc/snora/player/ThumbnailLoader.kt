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
    @Volatile private var currentDataSource: String = ""
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverSource: String = ""
    @Volatile private var timelineFrames: List<Pair<Long, Bitmap>> = emptyList()

    fun setDataSource(source: String) {
        if (source == currentDataSource) return

        // Only invalidate state on the caller (normally the main thread). Closing
        // MediaMetadataRetriever can wait for an in-flight codec read, so stale
        // retriever cleanup is deliberately deferred to ensureRetriever() on IO.
        currentDataSource = source
        cache.evictAll()
        timelineFrames = emptyList()
    }

    /** Must be called while holding [lock], from a background dispatcher. */
    private fun ensureRetriever(): MediaMetadataRetriever? {
        val source = currentDataSource
        if (retriever != null && retrieverSource != source) {
            try {
                retriever?.release()
            } catch (t: Throwable) {
            }
            retriever = null
            retrieverSource = ""
        }

        if (retriever == null && source.isNotEmpty()) {
            try {
                val r = MediaMetadataRetriever()
                if (source.startsWith("content://")) {
                    r.setDataSource(context, Uri.parse(source))
                } else {
                    r.setDataSource(source)
                }
                retriever = r
                retrieverSource = source
            } catch (t: Throwable) {
                retriever = null
                retrieverSource = ""
            }
        }
        return retriever
    }

    private val lock = Any()

    /** Pre-extract a compact set of frames once per media item for responsive seek previews. */
    suspend fun prepareTimeline(durationMs: Long) {
        val source = currentDataSource
        if (durationMs <= 0L || source.isEmpty() || timelineFrames.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            if (timelineFrames.isNotEmpty()) return@withContext
            val samples = 24
            val frames = ArrayList<Pair<Long, Bitmap>>(samples)
            for (index in 0 until samples) {
                if (currentDataSource != source) return@withContext
                val atMs = (durationMs * index) / (samples - 1)
                val bitmap = synchronized(lock) {
                    if (currentDataSource != source) return@synchronized null
                    try {
                        val r = ensureRetriever() ?: return@synchronized null
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            r.getScaledFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108)
                        } else {
                            r.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                                Bitmap.createScaledBitmap(it, 192, 108, true)
                            }
                        }
                    } catch (_: Throwable) {
                        // Some network streams and protected media don't support frame extraction.
                        null
                    }
                }
                if (bitmap != null) {
                    frames.add(atMs to bitmap)
                    synchronized(lock) {
                        if (currentDataSource == source) {
                            cache.put(atMs / 1000L, bitmap)
                            timelineFrames = frames.toList()
                        }
                    }
                }
            }
        }
    }

    suspend fun loadFrame(timeMs: Long): Bitmap? {
        val roundedSecond = (timeMs / 1000L).coerceAtLeast(0L)
        synchronized(lock) {
            val cached = cache.get(roundedSecond)
            if (cached != null) return cached
            val frames = timelineFrames
            if (frames.isNotEmpty()) {
                return frames.minByOrNull { kotlin.math.abs(it.first - timeMs) }?.second
            }
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
        synchronized(lock) {
            try {
                retriever?.release()
            } catch (t: Throwable) {
            }
            retriever = null
            retrieverSource = ""
            timelineFrames = emptyList()
            cache.evictAll()
        }
    }
}
