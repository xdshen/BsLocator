package com.example.bslocator.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: MeasurementSession): Long

    @Update
    suspend fun update(session: MeasurementSession)

    @Delete
    suspend fun delete(session: MeasurementSession)

    /** 获取所有会话，按时间倒序 */
    @Query("SELECT * FROM measurement_sessions ORDER BY start_time DESC")
    fun getAll(): Flow<List<MeasurementSession>>

    @Query("SELECT * FROM measurement_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): MeasurementSession?

    /** 获取当前活跃的会话 */
    @Query("SELECT * FROM measurement_sessions WHERE is_active = 1 ORDER BY start_time DESC LIMIT 1")
    suspend fun getActiveSession(): MeasurementSession?

    @Query("SELECT COUNT(*) FROM measurement_sessions")
    suspend fun getCount(): Int

    @Query("DELETE FROM measurement_sessions")
    suspend fun deleteAll()

    // ---- 关联统计 ----

    /** 获取某个会话的测量点数量 */
    @Query("SELECT COUNT(*) FROM measurements WHERE session_id = :sessionId")
    suspend fun getMeasurementCount(sessionId: Long): Int

    /** 获取某个会话覆盖的基站数 */
    @Query("SELECT COUNT(DISTINCT eci) FROM measurements WHERE session_id = :sessionId AND eci != -1")
    suspend fun getCellCount(sessionId: Long): Int
}
