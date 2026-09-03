package com.example.bslocator.util

import android.content.Context
import android.util.Log
import com.example.bslocator.data.Measurement
import com.example.bslocator.data.MeasurementDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从CSV备份文件自动恢复测量数据。
 * 在APP首次启动时检查下载目录中的 bslocator_*.csv 文件并导入。
 */
object CsvImporter {

    private const val TAG = "CsvImporter"
    private const val IMPORT_FLAG = "csv_import_done.flag"

    fun importIfNeeded(context: Context) {
        val flagFile = File(context.filesDir, IMPORT_FLAG)
        if (flagFile.exists()) {
            Log.d(TAG, "CSV already imported, skip")
            return
        }

        val downloadDir = File("/storage/emulated/0/Download")
        if (!downloadDir.exists()) {
            Log.d(TAG, "Download dir not found")
            return
        }

        val csvFile = downloadDir.listFiles { _, name ->
            name.startsWith("bslocator_") && name.endsWith(".csv")
        }?.maxByOrNull { it.lastModified() }

        if (csvFile == null || !csvFile.exists()) {
            Log.d(TAG, "No CSV backup found")
            return
        }

        Log.i(TAG, "Found CSV backup: ${csvFile.absolutePath}, size=${csvFile.length()}")

        runBlocking(Dispatchers.IO) {
            try {
                val dao = MeasurementDatabase.getDatabase(context).measurementDao()
                val measurements = parseCsv(csvFile)

                if (measurements.isEmpty()) {
                    Log.w(TAG, "CSV parsed but no valid records")
                    return@runBlocking
                }

                // Insert in batches
                measurements.chunked(50).forEach { batch ->
                    dao.insertAll(batch)
                }

                flagFile.writeText("imported_${System.currentTimeMillis()}_count_${measurements.size}")
                Log.i(TAG, "Successfully imported ${measurements.size} records from CSV")
            } catch (e: Exception) {
                Log.e(TAG, "CSV import failed", e)
            }
        }
    }

    private fun parseCsv(file: File): List<Measurement> {
        val result = mutableListOf<Measurement>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        file.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return result

            // Skip header
            val header = iterator.next()
            val headerMap = header.split(",").mapIndexed { i, name -> name.trim() to i }.toMap()

            fun getValue(parts: List<String>, name: String): String? {
                val idx = headerMap[name] ?: return null
                return parts.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }
            }

            for (line in iterator) {
                val parts = line.split(",")
                if (parts.size < 10) continue

                try {
                    val sessionId = getValue(parts, "session_id")?.toLongOrNull() ?: 0
                    val ts = getValue(parts, "timestamp")?.toLongOrNull() ?: continue
                    val eci = getValue(parts, "eci")?.toLongOrNull() ?: -1
                    val pci = getValue(parts, "pci")?.toIntOrNull() ?: -1
                    val earfcn = getValue(parts, "earfcn")?.toIntOrNull() ?: -1
                    val tac = getValue(parts, "tac")?.toIntOrNull() ?: -1
                    val mcc = getValue(parts, "mcc")?.toIntOrNull() ?: -1
                    val mnc = getValue(parts, "mnc")?.toIntOrNull() ?: -1
                    val rsrp = getValue(parts, "rsrp")?.toIntOrNull() ?: -140
                    val rsrq = getValue(parts, "rsrq")?.toIntOrNull() ?: -30
                    val rssnr = getValue(parts, "rssnr")?.toIntOrNull() ?: -20
                    val cqi = getValue(parts, "cqi")?.toIntOrNull() ?: -1
                    val lat = getValue(parts, "latitude")?.toDoubleOrNull() ?: continue
                    val lng = getValue(parts, "longitude")?.toDoubleOrNull() ?: continue
                    val alt = getValue(parts, "altitude")?.toDoubleOrNull() ?: 0.0
                    val acc = getValue(parts, "gps_accuracy")?.toFloatOrNull() ?: 999f
                    val spd = getValue(parts, "speed")?.toFloatOrNull() ?: 0f
                    val brg = getValue(parts, "bearing")?.toFloatOrNull() ?: 0f

                    result.add(
                        Measurement(
                            sessionId = sessionId,
                            timestamp = ts,
                            eci = eci,
                            pci = pci,
                            earfcn = earfcn,
                            tac = tac,
                            mcc = mcc,
                            mnc = mnc,
                            rsrp = rsrp,
                            rsrq = rsrq,
                            rssnr = rssnr,
                            cqi = cqi,
                            latitude = lat,
                            longitude = lng,
                            altitude = alt,
                            gpsAccuracy = acc,
                            speed = spd,
                            bearing = brg
                        )
                    )
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }

        return result
    }
}
