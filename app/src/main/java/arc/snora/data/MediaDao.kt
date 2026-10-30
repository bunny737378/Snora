package arc.snora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_records WHERE lastPlayedTimestamp > 0 ORDER BY lastPlayedTimestamp DESC")
    fun getHistory(): Flow<List<MediaRecord>>

    @Query("SELECT * FROM media_records WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavorites(): Flow<List<MediaRecord>>

    @Query("SELECT * FROM media_records WHERE uriString = :uriString LIMIT 1")
    suspend fun getRecord(uriString: String): MediaRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: MediaRecord)

    @Query("UPDATE media_records SET lastPositionMs = :positionMs, lastPlayedTimestamp = :timestamp WHERE uriString = :uriString")
    suspend fun updatePlaybackPosition(uriString: String, positionMs: Long, timestamp: Long)

    @Query("UPDATE media_records SET isFavorite = :isFavorite WHERE uriString = :uriString")
    suspend fun updateFavorite(uriString: String, isFavorite: Boolean)

    @Query("DELETE FROM media_records WHERE uriString = :uriString")
    suspend fun deleteRecord(uriString: String)

    @Query("UPDATE media_records SET lastPlayedTimestamp = 0 WHERE uriString = :uriString")
    suspend fun clearFromHistory(uriString: String)
}
