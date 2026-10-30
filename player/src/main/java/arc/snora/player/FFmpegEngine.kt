package arc.snora.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object FFmpegEngine {

    private var isBinaryReady = false
    private var ffmpegFile: File? = null
    private var ffprobeFile: File? = null

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (isBinaryReady && ffmpegFile?.exists() == true && ffprobeFile?.exists() == true) return@withContext
        try {
            val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) {
                if (Build.SUPPORTED_ABIS[0].contains("arm64")) "arm64-v8a" else "armeabi-v7a"
            } else {
                "arm64-v8a"
            }

            val binDir = File(context.filesDir, "bin").apply { mkdirs() }
            val ffmpeg = File(binDir, "ffmpeg")
            val ffprobe = File(binDir, "ffprobe")

            if (!ffmpeg.exists() || ffmpeg.length() == 0L) {
                copyAssetBinary(context, "bin/$abi/ffmpeg", ffmpeg)
            }
            if (!ffprobe.exists() || ffprobe.length() == 0L) {
                copyAssetBinary(context, "bin/$abi/ffprobe", ffprobe)
            }

            ffmpeg.setExecutable(true, false)
            ffprobe.setExecutable(true, false)

            ffmpegFile = ffmpeg
            ffprobeFile = ffprobe
            isBinaryReady = ffmpeg.exists() && ffprobe.exists()
        } catch (e: Exception) {
            isBinaryReady = false
        }
    }

    private fun copyAssetBinary(context: Context, assetPath: String, dest: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
        }
    }

    suspend fun probeMedia(context: Context, path: String): List<TrackItem> = withContext(Dispatchers.IO) {
        initialize(context)
        val tracks = mutableListOf<TrackItem>()
        val probe = ffprobeFile ?: return@withContext tracks
        if (!probe.exists() || path.isBlank()) return@withContext tracks

        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val builder = ProcessBuilder(
                probe.absolutePath,
                "-v", "error",
                "-show_entries", "stream=index,codec_type,codec_name:stream_tags=language,title",
                "-of", "csv=p=0",
                path
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            val process = builder.redirectErrorStream(true).start()

            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)

            lines.forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val index = parts[0].toIntOrNull() ?: tracks.size
                    val typeStr = parts[1].trim()
                    val codec = if (parts.size > 2) parts[2].trim() else ""
                    val lang = if (parts.size > 3) parts[3].trim() else "und"
                    val title = if (parts.size > 4) parts[4].trim() else "Track $index"

                    val type = when (typeStr) {
                        "video" -> TrackType.VIDEO
                        "audio" -> TrackType.AUDIO
                        "subtitle" -> TrackType.SUBTITLE
                        else -> null
                    }

                    if (type != null) {
                        tracks.add(
                            TrackItem(
                                id = index,
                                type = type,
                                title = title.ifEmpty { "Track $index ($lang)" },
                                lang = lang,
                                codec = codec,
                                isSelected = (type == TrackType.AUDIO && tracks.none { it.type == TrackType.AUDIO })
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
        }
        tracks
    }

    suspend fun extractThumbnail(context: Context, videoPath: String): Bitmap? = withContext(Dispatchers.IO) {
        initialize(context)
        val ffmpeg = ffmpegFile ?: return@withContext null
        if (!ffmpeg.exists() || videoPath.isBlank()) return@withContext null

        val cacheFile = File(context.cacheDir, "thumb_${videoPath.hashCode()}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext BitmapFactory.decodeFile(cacheFile.absolutePath)
        }

        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val builder = ProcessBuilder(
                ffmpeg.absolutePath,
                "-y",
                "-ss", "00:00:01",
                "-i", videoPath,
                "-vframes", "1",
                "-vf", "scale=240:-1",
                "-f", "image2",
                cacheFile.absolutePath
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            val process = builder.redirectErrorStream(true).start()
            process.waitFor(4, TimeUnit.SECONDS)

            if (cacheFile.exists() && cacheFile.length() > 0) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun createStreamServer(
        context: Context,
        sourcePath: String,
        startSec: Long = 0L,
        forceTranscode: Boolean = false
    ): FFmpegStreamServer? {
        val ffmpeg = ffmpegFile ?: return null
        if (!ffmpeg.exists()) return null
        return FFmpegStreamServer(context, ffmpeg, sourcePath, startSec, forceTranscode)
    }

    fun isReady(): Boolean = isBinaryReady
}
