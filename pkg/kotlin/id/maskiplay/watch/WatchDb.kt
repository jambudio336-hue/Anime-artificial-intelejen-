package id.maskiplay.watch

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val cover: String?,
    val addedAt: Long
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val seriesId: String,
    val source: String,
    val title: String,
    val cover: String?,
    val updatedAt: Long
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val key: String,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Dao
interface WatchDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getWatchlist(): List<WatchlistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putWatchlist(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE id = :id")
    fun removeWatchlist(id: String)

    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun getHistory(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putHistory(item: HistoryEntity)

    @Query("DELETE FROM history WHERE videoId = :videoId")
    fun removeHistory(videoId: String)

    @Query("DELETE FROM history WHERE updatedAt < :before")
    fun pruneHistory(before: Long)

    @Query("SELECT * FROM progress WHERE key = :key LIMIT 1")
    fun getProgress(key: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putProgress(item: ProgressEntity)
}

@Database(
    entities = [WatchlistEntity::class, HistoryEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WatchDb : RoomDatabase() {
    abstract fun watchDao(): WatchDao

    companion object {
        @Volatile private var instance: WatchDb? = null
        fun get(context: Context): WatchDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WatchDb::class.java,
                "maskiplay-watch.db"
            ).build().also { instance = it }
        }
    }
}
