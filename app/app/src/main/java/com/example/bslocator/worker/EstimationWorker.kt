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
import com.example.bslocator.data.Measurement
import com.example.bslocator.data.MeasurementDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background Worker for base station estimation.
 * Runs even when the app is in background or killed by the system.
 * Shows a foreground notification during computation, and a completion notification when done.
 */
class EstimationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "EstimationWorker"
        const val INPUT_ECI = "eci"
        const val INPUT_USE_TANH = "use_tanh"
        @Deprecated("Use INPUT_ECI instead")
        const val INPUT_PCI = "pci"
        const val OUTPUT_RESULT_JSON = "result_json"
        const val OUTPUT_SUCCESS = "success"
        const val OUTPUT_ERROR = "error"
        
        private const val NOTIFICATION_CHANNEL_ID = "bslocator_estimation"
        private const val FOREGROUND_NOTIFICATION_ID = 2001
        private const val COMPLETION_NOTIFICATION_ID = 2002
        private const val TAG = "EstimationWorker"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        // Support both new ECI input and legacy PCI input
        val eci = inputData.getLong(INPUT_ECI, -1)
        val pci = inputData.getInt(INPUT_PCI, -1)
        if (eci == -1L && pci == -1) {
            return Result.failure(
                androidx.work.workDataOf(OUTPUT_ERROR to "Invalid cell identifier")
            )
        }

        createNotificationChannel()

        // Promote to foreground service (required for long-running workers on Android 14+)
        val foregroundInfo = androidx.work.ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            buildProgressNotification("正在加载数据...").build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        setForeground(foregroundInfo)

        return try {
            // 1. Load measurements from DB
            val cellLabel = if (eci >= 0) "ECI=$eci" else "PCI=$pci"
            updateNotification("正在加载 $cellLabel 的测量数据...")
            val measurements = if (eci >= 0) loadMeasurementsByEci(eci) else loadMeasurementsByPci(pci)
            
            if (measurements.size < 10) {
                val msg = "数据不足: 仅 ${measurements.size} 条记录，需要至少 10 条"
                updateNotification(msg, isError = true)
                return Result.failure(
                    androidx.work.workDataOf(OUTPUT_ERROR to msg)
                )
            }

            // 2. Run estimation (pattern-cap model selectable)
            updateNotification("正在推断基站位置和方向图参数... (共 ${measurements.size} 条数据)")
            val cap = if (inputData.getBoolean(INPUT_USE_TANH, false))
                BaseStationEstimator.PatternCap.TANH_SMOOTH
            else
                BaseStationEstimator.PatternCap.HARD_CLIP
            val estimator = BaseStationEstimator(cap)
            val result = withContext(Dispatchers.Default) {
                estimator.estimate(measurements)
            }

            // 3. Handle result
            if (result != null) {
                val resultJson = Gson().toJson(result)
                showCompletionNotification(result, if (eci >= 0) eci else pci.toLong())
                Log.i(TAG, "Estimation completed for PCI=$pci: " +
                    "BS=(${result.bsLatitude.format(6)}, ${result.bsLongitude.format(6)}), " +
                    "Azimuth=${result.azimuthDeg.format(1)}°, RMSE=${result.rmse.format(2)} dB")
                
                Result.success(
                    androidx.work.workDataOf(
                        OUTPUT_SUCCESS to true,
                        OUTPUT_RESULT_JSON to resultJson
                    )
                )
            } else {
                val msg = "推断失败: 算法未收敛，请尝试采集更多数据（建议绕基站 360° 覆盖）"
                updateNotification(msg, isError = true)
                Result.failure(
                    androidx.work.workDataOf(OUTPUT_ERROR to msg)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Estimation failed", e)
            val msg = "推断异常: ${e.message}"
            updateNotification(msg, isError = true)
            Result.failure(
                androidx.work.workDataOf(OUTPUT_ERROR to msg)
            )
        }
    }

    private suspend fun loadMeasurementsByEci(eci: Long): List<Measurement> {
        return withContext(Dispatchers.IO) {
            val dao = MeasurementDatabase.getDatabase(applicationContext).measurementDao()
            dao.getMeasurementsForEci(eci)
        }
    }

    @Deprecated("Use loadMeasurementsByEci instead")
    private suspend fun loadMeasurementsByPci(pci: Int): List<Measurement> {
        return withContext(Dispatchers.IO) {
            val dao = MeasurementDatabase.getDatabase(applicationContext).measurementDao()
            dao.getMeasurementsForPci(pci)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "基站推断任务",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "后台基站位置推断进度和结果通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(message: String): NotificationCompat.Builder {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("基站推断中...")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate progress
    }

    private suspend fun updateNotification(message: String, isError: Boolean = false) {
        val builder = buildProgressNotification(message).apply {
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

    private fun showCompletionNotification(result: BaseStationEstimator.EstimationResult, eci: Long) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "estimate")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("✅ 基站推断完成 - ECI $eci")
            .setContentText(
                "位置: (${result.bsLatitude.format(4)}, ${result.bsLongitude.format(4)}) | " +
                "方位角: ${result.azimuthDeg.format(1)}° | 误差: ${result.rmse.format(2)} dB"
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "📍 推断基站位置\n" +
                        "纬度: ${result.bsLatitude.format(6)}°\n" +
                        "经度: ${result.bsLongitude.format(6)}°\n\n" +
                        "📡 天线方向图参数\n" +
                        "方位角: ${result.azimuthDeg.format(1)}°\n" +
                        "波束宽度: ${result.beamwidthDeg.format(1)}°\n" +
                        "下倾角: ${result.tiltDeg.format(1)}°\n\n" +
                        "📊 拟合质量: RMSE ${result.rmse.format(2)} dB"
                    )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)
}
