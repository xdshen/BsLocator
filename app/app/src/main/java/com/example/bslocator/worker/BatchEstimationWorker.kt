package com.example.bslocator.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bslocator.MainActivity
import com.example.bslocator.R
import com.example.bslocator.algorithm.BaseStationEstimator
import com.example.bslocator.data.MeasurementDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background Worker for batch base station estimation.
 * Estimates all distinct cells (ECI) from selected session measurements.
 */
class BatchEstimationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "BatchEstimationWorker"
        const val INPUT_SESSION_IDS = "session_ids"
        const val OUTPUT_RESULTS_JSON = "results_json"
        const val OUTPUT_SUMMARY = "summary"
        const val OUTPUT_ERROR = "error"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_CURRENT_ECI = "progress_current_eci"

        private const val NOTIFICATION_CHANNEL_ID = "bslocator_batch_estimation"
        private const val FOREGROUND_NOTIFICATION_ID = 3001
        private const val COMPLETION_NOTIFICATION_ID = 3002
        private const val TAG = "BatchEstWorker"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val sessionIds = inputData.getLongArray(INPUT_SESSION_IDS) ?: longArrayOf()
        if (sessionIds.isEmpty()) {
            return Result.failure(
                androidx.work.workDataOf(OUTPUT_ERROR to "未选择任何日志")
            )
        }

        createNotificationChannel()

        val foregroundInfo = androidx.work.ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            buildProgressNotification("正在加载测量数据...").build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        setForeground(foregroundInfo)

        return try {
            // 1. Load all measurements from selected sessions
            updateNotification("正在加载 ${sessionIds.size} 条日志的数据...")
            val allMeasurements = loadMeasurements(sessionIds)

            if (allMeasurements.isEmpty()) {
                val msg = "所选日志中没有测量数据"
                updateNotification(msg, isError = true)
                return Result.failure(androidx.work.workDataOf(OUTPUT_ERROR to msg))
            }

            // 2. Group by ECI (exclude unknown ECI = -1)
            val grouped = allMeasurements.filter { it.eci != -1L }.groupBy { it.eci }
            val validGroups = grouped.filter { (_, measurements) ->
                measurements.count { it.gpsAccuracy < BaseStationEstimator.MAX_GPS_ACCURACY } >= 10
            }

            if (validGroups.isEmpty()) {
                val msg = "没有符合条件的基站：各基站有效数据不足（需≥10条，GPS精度<${BaseStationEstimator.MAX_GPS_ACCURACY}m）"
                updateNotification(msg, isError = true)
                return Result.failure(androidx.work.workDataOf(OUTPUT_ERROR to msg))
            }

            // 3. Run estimation for each ECI (cooperative cancellation between cells)
            val results = mutableListOf<BatchResult>()
            val errors = mutableListOf<String>()
            val estimator = BaseStationEstimator()
            var processed = 0
            val total = validGroups.size
            var cancelled = false

            for ((eci, measurements) in validGroups) {
                if (isStopped) {
                    cancelled = true
                    Log.i(TAG, "Batch estimation cancelled by user after $processed/$total")
                    break
                }
                processed++
                // 上报进度：App 内进度条 + 通知栏确定性进度
                setProgress(
                    androidx.work.workDataOf(
                        PROGRESS_DONE to processed,
                        PROGRESS_TOTAL to total,
                        PROGRESS_CURRENT_ECI to eci
                    )
                )
                updateNotification("正在推断第 $processed/$total 个基站 (ECI $eci)...", processed, total)

                val result = withContext(Dispatchers.Default) {
                    estimator.estimate(measurements)
                }

                if (result != null) {
                    results.add(BatchResult(eci, result))
                    Log.i(TAG, "ECI $eci estimation succeeded: RMSE=${result.rmse.format(2)} dB")
                } else {
                    errors.add("ECI $eci: 数据不足或算法未收敛")
                    Log.w(TAG, "ECI $eci estimation failed")
                }
            }

            // 4. Build output
            val summary = buildString {
                if (cancelled) {
                    append("已取消：完成 $processed/$total，成功 ${results.size} 个")
                } else {
                    append("成功推断 ${results.size} 个基站")
                    if (errors.isNotEmpty()) {
                        append("，${errors.size} 个失败")
                    }
                }
            }

            if (results.isNotEmpty()) {
                if (!cancelled) showCompletionNotification(results)
                val resultsJson = Gson().toJson(results)
                Result.success(
                    androidx.work.workDataOf(
                        OUTPUT_RESULTS_JSON to resultsJson,
                        OUTPUT_SUMMARY to summary
                    )
                )
            } else {
                val msg = if (cancelled) "批量推断已取消，无完成结果"
                          else "所有基站推断均失败: ${errors.joinToString(", ")}"
                updateNotification(msg, isError = true)
                Result.failure(androidx.work.workDataOf(OUTPUT_ERROR to msg))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Batch estimation failed", e)
            val msg = "批量推断异常: ${e.message}"
            updateNotification(msg, isError = true)
            Result.failure(androidx.work.workDataOf(OUTPUT_ERROR to msg))
        }
    }

    private suspend fun loadMeasurements(sessionIds: LongArray): List<com.example.bslocator.data.Measurement> {
        return withContext(Dispatchers.IO) {
            val dao = MeasurementDatabase.getDatabase(applicationContext).measurementDao()
            sessionIds.toList().flatMap { dao.getBySessionIdSync(it) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "批量基站推断",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "批量推断多个基站位置的后台任务"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        message: String,
        done: Int = 0,
        total: Int = 0
    ): NotificationCompat.Builder {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(
                if (total > 0) "批量基站推断中 ($done/$total)" else "批量基站推断中..."
            )
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .apply {
                if (total > 0) setProgress(total, done, false)
                else setProgress(0, 0, true)
            }
    }

    private suspend fun updateNotification(
        message: String,
        done: Int = 0,
        total: Int = 0,
        isError: Boolean = false
    ) {
        val builder = buildProgressNotification(message, done, total).apply {
            if (isError) {
                setOngoing(false)
                setProgress(0, 0, false)
            }
        }
        setForeground(
            androidx.work.ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )
    }

    private fun showCompletionNotification(results: List<BatchResult>) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "estimate")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bigText = buildString {
            append("✅ 成功推断 ${results.size} 个基站\\n\\n")
            results.forEach { r ->
                append("📡 ECI ${r.eci}\\n")
                append("   位置: (${r.result.bsLatitude.format(4)}, ${r.result.bsLongitude.format(4)})\\n")
                append("   方位角: ${r.result.azimuthDeg.format(1)}°  RMSE: ${r.result.rmse.format(2)} dB\\n\\n")
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("✅ 批量基站推断完成")
            .setContentText("成功推断 ${results.size} 个基站位置")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)

    /**
     * Serializable result wrapper that includes ECI.
     */
    data class BatchResult(
        val eci: Long,
        val result: BaseStationEstimator.EstimationResult
    )
}
