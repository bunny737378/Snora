package arc.snora.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import arc.snora.models.FolderGroup
import arc.snora.models.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class MediaRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.mediaDao()

    val historyFlow: Flow<List<MediaRecord>> = dao.getHistory()
    val favoritesFlow: Flow<List<MediaRecord>> = dao.getFavorites()

    suspend fun getResumePosition(uriString: String): Long {
        return dao.getRecord(uriString)?.lastPositionMs ?: 0L
    }

    suspend fun savePlaybackPosition(uriString: String, title: String, path: String, positionMs: Long, durationMs: Long) {
        withContext(Dispatchers.IO) {
            val existing = dao.getRecord(uriString)
            val record = MediaRecord(
                uriString = uriString,
                title = title,
                path = path,
                durationMs = durationMs,
                lastPositionMs = positionMs,
                lastPlayedTimestamp = System.currentTimeMillis(),
                isFavorite = existing?.isFavorite ?: false
            )
            dao.upsertRecord(record)
        }
    }

    suspend fun toggleFavorite(uriString: String, title: String, path: String, durationMs: Long) {
        withContext(Dispatchers.IO) {
            val existing = dao.getRecord(uriString)
            if (existing != null) {
                dao.updateFavorite(uriString, !existing.isFavorite)
            } else {
                val newRecord = MediaRecord(
                    uriString = uriString,
                    title = title,
                    path = path,
                    durationMs = durationMs,
                    lastPositionMs = 0L,
                    lastPlayedTimestamp = 0L,
                    isFavorite = true
                )
                dao.upsertRecord(newRecord)
            }
        }
    }

    suspend fun isFavorite(uriString: String): Boolean {
        return dao.getRecord(uriString)?.isFavorite ?: false
    }

    suspend fun queryDeviceVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RESOLUTION
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Video.Media.TITLE)
                val nameCol = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val durationCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val resCol = c.getColumnIndex(MediaStore.Video.Media.RESOLUTION)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val name = if (nameCol >= 0) c.getString(nameCol) else ""
                    val titleRaw = if (titleCol >= 0) c.getString(titleCol) else ""
                    val title = titleRaw.ifEmpty { name.ifEmpty { "Video $id" } }
                    val path = if (dataCol >= 0) c.getString(dataCol) ?: "" else ""
                    val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val dateModified = if (dateCol >= 0) c.getLong(dateCol) else 0L
                    val resolution = if (resCol >= 0) c.getString(resCol) ?: "" else ""

                    val folderName = if (path.isNotEmpty()) {
                        File(path).parentFile?.name ?: "Internal"
                    } else {
                        "Videos"
                    }

                    videoList.add(
                        VideoItem(
                            id = id,
                            title = title,
                            uriString = contentUri,
                            path = path,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModified = dateModified,
                            resolution = resolution,
                            folderName = folderName
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }

        videoList
    }

    suspend fun queryFolderGroups(): List<FolderGroup> = withContext(Dispatchers.IO) {
        val videos = queryDeviceVideos()
        videos.groupBy { it.folderName }
            .map { (folderName, items) ->
                val folderPath = items.firstOrNull()?.let { File(it.path).parent ?: "" } ?: ""
                FolderGroup(
                    name = folderName,
                    path = folderPath,
                    videoCount = items.size,
                    videos = items
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
