package com.example.bslocator.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: Measurement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurements: List<Measurement>)

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Measurement>>

    // ---- Session-based queries ----

    @Query("SELECT * FROM measurements WHERE session_id = :sessionId ORDER BY timestamp DESC")
    fun getBySessionId(sessionId: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE session_id IN (:sessionIds) ORDER BY timestamp DESC")
    fun getAllBySessionIds(sessionIds: List<Long>): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE session_id = :sessionId ORDER BY timestamp DESC")
    suspend fun getBySessionIdSync(sessionId: Long): List<Measurement>

    @Query("SELECT COUNT(*) FROM measurements WHERE session_id = :sessionId")
    suspend fun getCountBySessionId(sessionId: Long): Int

    @Query("DELETE FROM measurements WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    // ---- Legacy PCI-based queries (kept for backward compat) ----

    @Query("SELECT * FROM measurements WHERE pci = :pci ORDER BY timestamp DESC")
    fun getByPci(pci: Int): Flow<List<Measurement>>

    @Query("SELECT DISTINCT pci FROM measurements ORDER BY pci")
    suspend fun getDistinctPcis(): List<Int>

    @Query("SELECT * FROM measurements WHERE pci = :pci")
    suspend fun getMeasurementsForPci(pci: Int): List<Measurement>

    // ---- ECI-based queries (preferred for unique cell identification) ----

    /** Get all measurements for a unique cell (ECI). */
    @Query("SELECT * FROM measurements WHERE eci = :eci ORDER BY timestamp DESC")
    suspend fun getMeasurementsForEci(eci: Long): List<Measurement>

    /** Get distinct ECI values that have been measured. */
    @Query("SELECT DISTINCT eci FROM measurements WHERE eci != -1 ORDER BY eci")
    suspend fun getDistinctEcis(): List<Long>

    /** Get distinct ECI values together with their PCI for display. */
    @Query("SELECT DISTINCT eci, pci, earfcn FROM measurements WHERE eci != -1")
    suspend fun getDistinctCells(): List<CellSummary>

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun getCount(): Int

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(measurement: Measurement)

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Measurement>
}

/**
 * Lightweight projection for displaying distinct cells in dropdowns.
 */
data class CellSummary(
    val eci: Long,
    val pci: Int,
    val earfcn: Int
)
