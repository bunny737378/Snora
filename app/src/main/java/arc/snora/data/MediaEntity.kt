package arc.snora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_records")
data class MediaRecord(
    @PrimaryKey val uriString: String,
    val title: String,
    val path: String,
    val durationMs: Long,
    val lastPositionMs: Long,
    val lastPlayedTimestamp: Long,
    val isFavorite: Boolean
)
