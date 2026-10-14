package ru.booster.network.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.booster.network.core.model.GameInfo

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY isTracked DESC, lastLaunchedAt DESC, appName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<GameInfo>>

    @Query("SELECT * FROM games WHERE isTracked = 1")
    suspend fun trackedGames(): List<GameInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: GameInfo)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameInfo>)

    @Update
    suspend fun update(game: GameInfo)

    @Delete
    suspend fun delete(game: GameInfo)

    @Query("UPDATE games SET isTracked = :tracked WHERE packageName = :pkg")
    suspend fun setTracked(pkg: String, tracked: Boolean)

    @Query("UPDATE games SET pingBeforeMs = :before, pingAfterMs = :after WHERE packageName = :pkg")
    suspend fun recordPing(pkg: String, before: Int, after: Int)
}
