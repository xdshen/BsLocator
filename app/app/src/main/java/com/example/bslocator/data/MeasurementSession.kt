package com.example.bslocator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次采集会话（路测日志）。
 * 每次"开始采集→停止采集"形成一个独立的 Session，
 * 命名包含日期时间，便于管理和追溯。
 */
@Entity(tableName = "measurement_sessions")
data class MeasurementSession(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 会话名称，自动命名如"路测 2026-09-03 14:30" */
    @SerializedName("name")
    @ColumnInfo(name = "name")
    val name: String = "",

    /** 采集开始时间 */
    @SerializedName("start_time")
    @ColumnInfo(name = "start_time")
    val startTime: Long = System.currentTimeMillis(),

    /** 采集结束时间（-1 表示未结束） */
    @SerializedName("end_time")
    @ColumnInfo(name = "end_time")
    val endTime: Long = -1,

    /** 备注说明 */
    @SerializedName("notes")
    @ColumnInfo(name = "notes")
    val notes: String = "",

    /** 是否正在采集中 */
    @SerializedName("is_active")
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
) {
    companion object {
        fun generateName(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return "路测 ${sdf.format(Date())}"
        }
    }

    /** 持续时间（毫秒），如果未结束则为当前时间减去开始时间 */
    val durationMs: Long
        get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
}
