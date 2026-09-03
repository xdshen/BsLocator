package com.example.bslocator.util

import android.content.Context
import android.net.Uri
import com.example.bslocator.algorithm.BaseStationEstimator
import com.example.bslocator.data.Measurement
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

/**
 * Export utilities for measurements and estimation results.
 * Uses Storage Access Framework (SAF) via URI — no WRITE_EXTERNAL_STORAGE needed on API 29+.
 */
object ExportUtils {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeSpecialFloatingPointValues()
        .create()

    /** Full CSV header matching all Measurement fields */
    private const val FULL_CSV_HEADER = "timestamp,eci,pci,earfcn,tac,mcc,mnc,rsrp,rsrq,rssnr,cqi," +
            "latitude,longitude,altitude,gps_accuracy,speed,bearing,distance_from_bs"

    /**
     * Export a list of measurements to CSV via SAF URI.
     */
    suspend fun exportMeasurementsToCsv(
        context: Context,
        uri: Uri,
        measurements: List<Measurement>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { writer ->
                    writer.write(FULL_CSV_HEADER)
                    writer.write("\n")
                    measurements.forEach { m ->
                        writer.write(measurementToCsvLine(m))
                        writer.write("\n")
                    }
                }
            } ?: throw IllegalStateException("无法打开输出流")
            "已导出 ${measurements.size} 条记录"
        }
    }

    /**
     * Export a list of measurements to pretty-printed JSON via SAF URI.
     */
    suspend fun exportMeasurementsToJson(
        context: Context,
        uri: Uri,
        measurements: List<Measurement>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { writer ->
                    writer.write(gson.toJson(measurements))
                }
            } ?: throw IllegalStateException("无法打开输出流")
            "已导出 ${measurements.size} 条记录"
        }
    }

    /**
     * Export estimation result + related measurements to JSON.
     */
    suspend fun exportEstimationResultToJson(
        context: Context,
        uri: Uri,
        result: BaseStationEstimator.EstimationResult,
        measurements: List<Measurement>,
        pci: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val exportData = EstimationExportData(
                pci = pci,
                estimation = result,
                measurementCount = measurements.size,
                measurements = measurements
            )
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { writer ->
                    writer.write(gson.toJson(exportData))
                }
            } ?: throw IllegalStateException("无法打开输出流")
            "推断结果 + ${measurements.size} 条测量数据已导出"
        }
    }

    /**
     * Export estimation result + related measurements to CSV.
     */
    suspend fun exportEstimationResultToCsv(
        context: Context,
        uri: Uri,
        result: BaseStationEstimator.EstimationResult,
        measurements: List<Measurement>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { writer ->
                    // Write estimation result as header comment
                    writer.write("# Estimation Result\n")
                    writer.write("# bsLatitude,${result.bsLatitude}\n")
                    writer.write("# bsLongitude,${result.bsLongitude}\n")
                    writer.write("# azimuthDeg,${result.azimuthDeg}\n")
                    writer.write("# beamwidthDeg,${result.beamwidthDeg}\n")
                    writer.write("# tiltDeg,${result.tiltDeg}\n")
                    writer.write("# bsHeightM,${result.bsHeightM}\n")
                    writer.write("# pathLossExponent,${result.pathLossExponent}\n")
                    writer.write("# referenceRssi,${result.referenceRssi}\n")
                    writer.write("# rmse,${result.rmse}\n")
                    writer.write("# iterations,${result.iterations}\n")
                    writer.write("\n")
                    // Write measurements
                    writer.write(FULL_CSV_HEADER)
                    writer.write("\n")
                    measurements.forEach { m ->
                        writer.write(measurementToCsvLine(m))
                        writer.write("\n")
                    }
                }
            } ?: throw IllegalStateException("无法打开输出流")
            "推断结果 + ${measurements.size} 条测量数据已导出"
        }
    }

    private fun measurementToCsvLine(m: Measurement): String {
        return "${m.timestamp},${m.eci},${m.pci},${m.earfcn},${m.tac},${m.mcc},${m.mnc}," +
                "${m.rsrp},${m.rsrq},${m.rssnr},${m.cqi}," +
                "${m.latitude},${m.longitude},${m.altitude},${m.gpsAccuracy}," +
                "${m.speed},${m.bearing},${m.distanceFromBs ?: ""}"
    }

    /**
     * Wrapper for JSON export of estimation result with measurements.
     */
    private data class EstimationExportData(
        val pci: Int,
        val estimation: BaseStationEstimator.EstimationResult,
        val measurementCount: Int,
        val measurements: List<Measurement>
    )
}
