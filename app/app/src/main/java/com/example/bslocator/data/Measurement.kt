package com.example.bslocator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Single measurement record combining GPS location + cellular signal.
 *
 * **Cell identification strategy:**
 * - `eci` (Long) is the primary unique cell identifier:
 *   - LTE:  ECI = `CellIdentityLte.ci`  (28-bit, 0..268435455)
 *   - NR:   NCI = `CellIdentityNr.nci`  (36-bit, 0..68719476735)
 * - `pci` is kept for display/reference only (can repeat across cells).
 * - `earfcn` helps distinguish cells with same PCI on different carriers.
 *
 * For grouping/filtering, always use `eci` (or the composite `eci + earfcn` if
 * the same physical cell appears on multiple bands).
 */
@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 所属采集会话 ID */
    @SerializedName("session_id")
    @ColumnInfo(name = "session_id")
    val sessionId: Long = 0,

    @SerializedName("timestamp")
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    // ---- Cell info ----

    /** Primary unique cell ID (ECI for LTE, NCI for NR). -1 = unknown. */
    @SerializedName("eci")
    @ColumnInfo(name = "eci")
    val eci: Long = -1,

    /** PCI (display only, NOT unique). */
    @SerializedName("pci")
    @ColumnInfo(name = "pci")
    val pci: Int = -1,

    @SerializedName("earfcn")
    @ColumnInfo(name = "earfcn")
    val earfcn: Int = -1,

    @SerializedName("tac")
    @ColumnInfo(name = "tac")
    val tac: Int = -1,

    @SerializedName("mcc")
    @ColumnInfo(name = "mcc")
    val mcc: Int = -1,

    @SerializedName("mnc")
    @ColumnInfo(name = "mnc")
    val mnc: Int = -1,

    // ---- Signal strength ----
    @SerializedName("rsrp")
    @ColumnInfo(name = "rsrp")
    val rsrp: Int = -140,      // dBm
    @SerializedName("rsrq")
    @ColumnInfo(name = "rsrq")
    val rsrq: Int = -30,       // dB
    @SerializedName("rssnr")
    @ColumnInfo(name = "rssnr")
    val rssnr: Int = -20,      // dB
    @SerializedName("cqi")
    @ColumnInfo(name = "cqi")
    val cqi: Int = -1,

    // ---- Location ----
    @SerializedName("latitude")
    @ColumnInfo(name = "latitude")
    val latitude: Double = 0.0,
    @SerializedName("longitude")
    @ColumnInfo(name = "longitude")
    val longitude: Double = 0.0,
    @SerializedName("altitude")
    @ColumnInfo(name = "altitude")
    val altitude: Double = 0.0,
    @SerializedName("gps_accuracy")
    @ColumnInfo(name = "gps_accuracy")
    val gpsAccuracy: Float = 999f,  // meters
    @SerializedName("speed")
    @ColumnInfo(name = "speed")
    val speed: Float = 0f,          // m/s
    @SerializedName("bearing")
    @ColumnInfo(name = "bearing")
    val bearing: Float = 0f,        // degrees

    // Derived: distance from estimated BS (filled after estimation)
    @SerializedName("distance_from_bs")
    @ColumnInfo(name = "distance_from_bs")
    val distanceFromBs: Double? = null
) {
    companion object {
        const val CSV_HEADER = "timestamp,session_id,eci,pci,earfcn,rsrp,rsrq,latitude,longitude,gps_accuracy"

        fun toCsv(measurement: Measurement): String {
            return "${measurement.timestamp},${measurement.sessionId},${measurement.eci},${measurement.pci},${measurement.earfcn}," +
                   "${measurement.rsrp},${measurement.rsrq}," +
                   "${measurement.latitude},${measurement.longitude},${measurement.gpsAccuracy}"
        }
    }
}
