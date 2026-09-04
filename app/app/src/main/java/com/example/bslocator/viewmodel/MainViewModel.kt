package com.example.bslocator.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.bslocator.algorithm.BaseStationEstimator
import com.example.bslocator.data.Measurement
import com.example.bslocator.data.MeasurementDao
import com.example.bslocator.data.MeasurementDatabase
import com.example.bslocator.data.MeasurementSession
import com.example.bslocator.data.MeasurementSessionDao
import com.example.bslocator.service.LocationCollectionService
import com.example.bslocator.util.ExportUtils
import com.example.bslocator.worker.EstimationWorker
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database: MeasurementDatabase = (application as com.example.bslocator.BsLocatorApp)
        .database
    private val dao: MeasurementDao = database.measurementDao()
    private val sessionDao: MeasurementSessionDao = database.sessionDao()

    private val workManager = WorkManager.getInstance(application)

    // WorkManager observer tracking (to prevent memory leaks)
    private var currentWorkObserver: androidx.lifecycle.Observer<WorkInfo>? = null
    private var currentWorkId: java.util.UUID? = null

    // Service binding
    private var collectionService: LocationCollectionService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationCollectionService.LocalBinder
            collectionService = binder.getService().apply {
                onMeasurementCollected = { measurement ->
                    _latestMeasurement.value = measurement
                }
                onStatusUpdate = { msg ->
                    _collectionStatus.value = msg
                }
                onSessionStarted = { sessionId, sessionName ->
                    _currentSessionId.value = sessionId
                    _currentSessionName.value = sessionName
                }
            }
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            collectionService = null
            serviceBound = false
        }
    }

    // UI States
    private val _isCollecting = mutableStateOf(false)
    val isCollecting: State<Boolean> = _isCollecting

    private val _collectionStatus = mutableStateOf<String>("")
    val collectionStatus: State<String> = _collectionStatus

    private val _latestMeasurement = mutableStateOf<Measurement?>(null)
    val latestMeasurement: State<Measurement?> = _latestMeasurement

    private val _totalCount = mutableIntStateOf(0)
    val totalCount: State<Int> = _totalCount

    private val _cellList = mutableStateListOf<com.example.bslocator.data.CellSummary>()
    val cellList: List<com.example.bslocator.data.CellSummary> = _cellList

    private val _selectedEci = mutableStateOf<Long?>(null)
    val selectedEci: State<Long?> = _selectedEci

    private val _estimationResults = mutableStateListOf<BaseStationEstimator.EstimationResult>()
    val estimationResults: List<BaseStationEstimator.EstimationResult> = _estimationResults

    // WorkManager states
    private val _workState = mutableStateOf<WorkInfo.State?>(null)
    val workState: State<WorkInfo.State?> = _workState

    private val _workError = mutableStateOf<String?>(null)
    val workError: State<String?> = _workError

    private val _workProgressMessage = mutableStateOf<String>("")
    val workProgressMessage: State<String> = _workProgressMessage

    // 批量推断进度：已完成个数 / 总个数（0/0 表示无进度信息，如单基站推断）
    private val _batchDone = mutableStateOf(0)
    val batchDone: State<Int> = _batchDone

    private val _batchTotal = mutableStateOf(0)
    val batchTotal: State<Int> = _batchTotal

    // Export states
    private val _isExporting = mutableStateOf(false)
    val isExporting: State<Boolean> = _isExporting

    private val _exportMessage = mutableStateOf<String?>(null)
    val exportMessage: State<String?> = _exportMessage

    private val _exportError = mutableStateOf<String?>(null)
    val exportError: State<String?> = _exportError

    // Import states
    private val _isImporting = mutableStateOf(false)
    val isImporting: State<Boolean> = _isImporting

    private val _importMessage = mutableStateOf<String?>(null)
    val importMessage: State<String?> = _importMessage

    private val _importError = mutableStateOf<String?>(null)
    val importError: State<String?> = _importError

    // ---- Session management ----

    private val _currentSessionId = mutableStateOf<Long>(0)
    val currentSessionId: State<Long> = _currentSessionId

    private val _currentSessionName = mutableStateOf<String>("")
    val currentSessionName: State<String> = _currentSessionName

    /** All sessions, ordered by start time descending */
    val sessions: Flow<List<MeasurementSession>> = sessionDao.getAll()

    /** Currently selected session IDs for map display (multi-select) */
    private val _selectedSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSessionIds: StateFlow<Set<Long>> = _selectedSessionIds

    /** Measurements for the currently selected sessions */
    val selectedSessionMeasurements: Flow<List<Measurement>> =
        kotlinx.coroutines.flow.combine(
            _selectedSessionIds,
            dao.getAll()
        ) { ids, all ->
            if (ids.isEmpty()) emptyList() else all.filter { it.sessionId in ids }
        }

    val measurements: Flow<List<Measurement>> = dao.getAll()

    init {
        viewModelScope.launch {
            dao.getAll().collect { list ->
                _totalCount.intValue = list.size
            }
        }
        refreshCellList()

        // 修复僵尸 session：APP 启动时，将所有异常未关闭的活跃 session 标记为已完成
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activeSession = sessionDao.getActiveSession()
                activeSession?.let { session ->
                    // 如果活跃 session 的持续时间已经超过 5 分钟，或者没有对应的测量数据，
                    // 则认为它是异常终止的，需要修复
                    val hasMeasurements = dao.getBySessionIdSync(session.id).isNotEmpty()
                    val isVeryOld = System.currentTimeMillis() - session.startTime > 5 * 60 * 1000
                    if (isVeryOld || !hasMeasurements) {
                        val fixed = session.copy(
                            endTime = session.startTime + session.durationMs.coerceAtMost(5 * 60 * 1000),
                            isActive = false
                        )
                        sessionDao.update(fixed)
                    }
                }
            }
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, LocationCollectionService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    fun startCollection(context: Context) {
        _isCollecting.value = true
        LocationCollectionService.start(context)
    }

    fun stopCollection(context: Context) {
        _isCollecting.value = false
        _currentSessionId.value = 0
        _currentSessionName.value = ""
        // Directly call the bound service instance to stop immediately
        collectionService?.stopCollection()
        LocationCollectionService.stop(context)
    }

    // ---- Session selection for map ----

    fun toggleSessionSelection(sessionId: Long) {
        val current = _selectedSessionIds.value.toMutableSet()
        if (sessionId in current) {
            current.remove(sessionId)
        } else {
            current.add(sessionId)
        }
        _selectedSessionIds.value = current
    }

    fun selectSessions(sessionIds: Set<Long>) {
        _selectedSessionIds.value = sessionIds
    }

    fun clearSessionSelection() {
        _selectedSessionIds.value = emptySet()
    }

    fun selectAllSessions(sessionIds: List<Long>) {
        _selectedSessionIds.value = sessionIds.toSet()
    }

    // ---- Session CRUD ----

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteBySessionId(sessionId)
                sessionDao.getById(sessionId)?.let { sessionDao.delete(it) }
            }
            // Remove from selection if selected
            toggleSessionSelection(sessionId)
            refreshCellList()
        }
    }

    fun renameSession(sessionId: Long, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                sessionDao.getById(sessionId)?.let { session ->
                    sessionDao.update(session.copy(name = newName))
                }
            }
        }
    }

    fun refreshCellList() {
        viewModelScope.launch {
            val cells = dao.getDistinctCells()
            _cellList.clear()
            _cellList.addAll(cells)
        }
    }

    fun selectEci(eci: Long?) {
        _selectedEci.value = eci
    }

    /** @deprecated Use refreshCellList() instead */
    fun refreshPciList() = refreshCellList()

    /** @deprecated Use selectEci() instead */
    fun selectPci(pci: Int?) {
        // Find the first cell with this PCI as a fallback
        _selectedEci.value = _cellList.firstOrNull { it.pci == pci }?.eci
    }

    /**
     * Import measurements from a CSV file URI via SAF.
     * Creates a new session for the imported data.
     */
    fun importCsvFromUri(context: Context, uri: Uri) {
        _isImporting.value = true
        _importMessage.value = null
        _importError.value = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val measurements = parseCsvFromUri(context, uri)
                    if (measurements.isEmpty()) {
                        return@withContext Result.failure<String>(Exception("CSV 文件中没有有效数据"))
                    }

                    // Create a new session for imported data
                    val sessionName = "导入 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                    val session = MeasurementSession(
                        name = sessionName,
                        startTime = measurements.minOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                        endTime = measurements.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                        isActive = false
                    )
                    val sessionId = sessionDao.insert(session)

                    // Assign sessionId to all measurements and insert
                    val withSession = measurements.map { it.copy(sessionId = sessionId) }
                    withSession.chunked(100).forEach { batch ->
                        dao.insertAll(batch)
                    }

                    Result.success("成功导入 ${withSession.size} 条记录到会话「$sessionName」")
                }

                result.onSuccess { msg ->
                    _importMessage.value = msg
                }.onFailure { e ->
                    _importError.value = "导入失败: ${e.message}"
                }
            } catch (e: Exception) {
                _importError.value = "导入失败: ${e.message}"
            }
            _isImporting.value = false
            refreshCellList()
        }
    }

    private fun parseCsvFromUri(context: Context, uri: Uri): List<Measurement> {
        val result = mutableListOf<Measurement>()
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开文件")

        inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return result

                // Parse header
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
        }

        return result
    }

    fun dismissImportMessage() {
        _importMessage.value = null
        _importError.value = null
    }

    /**
     * Trigger estimation via WorkManager (runs in background, survives app kill)
     */
    fun runEstimation(eci: Long) {
        _workState.value = WorkInfo.State.ENQUEUED
        _workError.value = null
        _batchDone.value = 0
        _batchTotal.value = 0
        _workProgressMessage.value = "任务已提交，正在排队..."

        val inputData = Data.Builder()
            .putLong(EstimationWorker.INPUT_ECI, eci)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<EstimationWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(EstimationWorker.WORK_TAG)
            .build()

        workManager.enqueue(workRequest)

        // Remove previous observer if exists
        currentWorkObserver?.let { prevObserver ->
            currentWorkId?.let { prevId ->
                workManager.getWorkInfoByIdLiveData(prevId).removeObserver(prevObserver)
            }
        }
        currentWorkId = workRequest.id

        // Observe work progress
        val observer = androidx.lifecycle.Observer<WorkInfo> { workInfo ->
            workInfo?.let { info ->
                _workState.value = info.state

                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        _workProgressMessage.value = "任务已排队，等待系统调度..."
                    }
                    WorkInfo.State.RUNNING -> {
                        _workProgressMessage.value = "正在推断中... (即使切到后台也会继续)"
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _workProgressMessage.value = "推断完成！"
                        val resultJson = info.outputData.getString(EstimationWorker.OUTPUT_RESULT_JSON)
                        resultJson?.let { json ->
                            try {
                                var result = Gson().fromJson(json, BaseStationEstimator.EstimationResult::class.java)
                                result = result.copy(eci = eci); _estimationResults.add(result)
                            } catch (e: Exception) {
                                _workError.value = "结果解析失败: ${e.message}"
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        _workProgressMessage.value = "推断失败"
                        _workError.value = info.outputData.getString(EstimationWorker.OUTPUT_ERROR)
                            ?: "未知错误"
                    }
                    WorkInfo.State.CANCELLED -> {
                        _workProgressMessage.value = "任务已取消"
                    }
                    else -> {}
                }
            }
        }
        currentWorkObserver = observer
        workManager.getWorkInfoByIdLiveData(workRequest.id).observeForever(observer)
    }

    /**
     * Legacy: run estimation inline (for small datasets / testing).
     * Prefer runEstimation() for production.
     */
    fun runEstimationInline(pci: Int) {
        viewModelScope.launch {
            _workState.value = WorkInfo.State.RUNNING
            val data = withContext(Dispatchers.IO) {
                dao.getMeasurementsForPci(pci)
            }

            val result = withContext(Dispatchers.Default) {
                BaseStationEstimator().estimate(data)
            }

            result?.let { _estimationResults.add(it) }
            _workState.value = if (result != null) WorkInfo.State.SUCCEEDED else WorkInfo.State.FAILED
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            dao.deleteAll()
            sessionDao.deleteAll()
            _cellList.clear()
            _estimationResults.clear()
            _selectedEci.value = null
            _workState.value = null
            _workError.value = null
            _selectedSessionIds.value = emptySet()
        }
    }

    fun clearEstimationResult() {
        _estimationResults.clear()
        _workState.value = null
        _workError.value = null
        _workProgressMessage.value = ""
        _batchDone.value = 0
        _batchTotal.value = 0
    }

    /**
     * 取消当前正在运行的推断任务（单基站或批量）
     */
    fun cancelEstimation() {
        currentWorkId?.let { id ->
            workManager.cancelWorkById(id)
            _workProgressMessage.value = "正在取消..."
        }
    }

    fun dismissExportMessage() {
        _exportMessage.value = null
        _exportError.value = null
    }

    fun dismissError() {
        _workError.value = null
    }

    // ------------------------------------------------------------------
    /**
     * Export all measurements to CSV/JSON via SAF URI.
     * @param format "csv" or "json"
     */
    fun exportAllMeasurements(context: Context, uri: Uri, format: String) {
        _isExporting.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getAll().first()
            }

            val result = if (format.equals("json", ignoreCase = true)) {
                ExportUtils.exportMeasurementsToJson(context, uri, data)
            } else {
                ExportUtils.exportMeasurementsToCsv(context, uri, data)
            }

            result.onSuccess { msg ->
                _exportMessage.value = msg
            }.onFailure { e ->
                _exportError.value = "导出失败: ${e.message}"
            }
            _isExporting.value = false
        }
    }

    /**
     * Export measurements for a specific session to CSV/JSON via SAF URI.
     * @param format "csv" or "json"
     */
    fun exportSessionMeasurements(context: Context, uri: Uri, sessionId: Long, format: String) {
        _isExporting.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getBySessionIdSync(sessionId)
            }

            val result = if (format.equals("json", ignoreCase = true)) {
                ExportUtils.exportMeasurementsToJson(context, uri, data)
            } else {
                ExportUtils.exportMeasurementsToCsv(context, uri, data)
            }

            result.onSuccess { msg ->
                _exportMessage.value = msg
            }.onFailure { e ->
                _exportError.value = "导出失败: ${e.message}"
            }
            _isExporting.value = false
        }
    }

    /**
     * Export measurements for multiple sessions to CSV/JSON via SAF URI.
     * @param format "csv" or "json"
     */
    fun exportMultipleSessions(context: Context, uri: Uri, sessionIds: List<Long>, format: String) {
        _isExporting.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                sessionIds.flatMap { dao.getBySessionIdSync(it) }
            }

            val result = if (format.equals("json", ignoreCase = true)) {
                ExportUtils.exportMeasurementsToJson(context, uri, data)
            } else {
                ExportUtils.exportMeasurementsToCsv(context, uri, data)
            }

            result.onSuccess { msg ->
                _exportMessage.value = msg
            }.onFailure { e ->
                _exportError.value = "导出失败: ${e.message}"
            }
            _isExporting.value = false
        }
    }

    /**
     * Export measurements for a specific PCI to CSV/JSON via SAF URI.
     * @param format "csv" or "json"
     */
    fun exportMeasurementsForPci(context: Context, uri: Uri, pci: Int, format: String) {
        _isExporting.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getMeasurementsForPci(pci)
            }

            val result = if (format.equals("json", ignoreCase = true)) {
                ExportUtils.exportMeasurementsToJson(context, uri, data)
            } else {
                ExportUtils.exportMeasurementsToCsv(context, uri, data)
            }

            result.onSuccess { msg ->
                _exportMessage.value = msg
            }.onFailure { e ->
                _exportError.value = "导出失败: ${e.message}"
            }
            _isExporting.value = false
        }
    }

    /**
     * Export estimation result + related measurements to CSV/JSON via SAF URI.
     * @param format "csv" or "json"
     */
    fun exportEstimationResult(context: Context, uri: Uri, eci: Long, format: String) {
        val result = _estimationResults.lastOrNull()
        if (result == null) {
            _exportError.value = "没有可导出的推断结果"
            return
        }
        if (result == null) {
        }

        _isExporting.value = true
        viewModelScope.launch {
            val measurements = withContext(Dispatchers.IO) {
                dao.getMeasurementsForEci(eci)
            }

            val exportResult = if (format.equals("json", ignoreCase = true)) {
                ExportUtils.exportEstimationResultToJson(context, uri, result, measurements, eci.toInt())
            } else {
                ExportUtils.exportEstimationResultToCsv(context, uri, result, measurements)
            }

            exportResult.onSuccess { msg ->
                _exportMessage.value = msg
            }.onFailure { e ->
                _exportError.value = "导出失败: ${e.message}"
            }
            _isExporting.value = false
        }
    }

    /**
     * Batch estimation: estimate all distinct cells from selected sessions.
     */
    fun runBatchEstimation(sessionIds: Set<Long>) {
        if (sessionIds.isEmpty()) {
            _workError.value = "请先选择至少一条日志"
            return
        }

        _workState.value = WorkInfo.State.ENQUEUED
        _workError.value = null
        _batchDone.value = 0
        _batchTotal.value = 0
        _workProgressMessage.value = "批量任务已提交，正在排队..."

        val inputData = androidx.work.Data.Builder()
            .putLongArray(com.example.bslocator.worker.BatchEstimationWorker.INPUT_SESSION_IDS, sessionIds.toLongArray())
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<com.example.bslocator.worker.BatchEstimationWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(com.example.bslocator.worker.BatchEstimationWorker.WORK_TAG)
            .build()

        workManager.enqueue(workRequest)

        // Remove previous observer if exists
        currentWorkObserver?.let { prevObserver ->
            currentWorkId?.let { prevId ->
                workManager.getWorkInfoByIdLiveData(prevId).removeObserver(prevObserver)
            }
        }
        currentWorkId = workRequest.id

        val observer = androidx.lifecycle.Observer<WorkInfo> { workInfo ->
            workInfo?.let { info ->
                _workState.value = info.state

                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        _workProgressMessage.value = "批量任务已排队，等待系统调度..."
                    }
                    WorkInfo.State.RUNNING -> {
                        val done = info.progress.getInt(
                            com.example.bslocator.worker.BatchEstimationWorker.PROGRESS_DONE, 0)
                        val total = info.progress.getInt(
                            com.example.bslocator.worker.BatchEstimationWorker.PROGRESS_TOTAL, 0)
                        val curEci = info.progress.getLong(
                            com.example.bslocator.worker.BatchEstimationWorker.PROGRESS_CURRENT_ECI, -1L)
                        if (total > 0) {
                            _batchDone.value = done
                            _batchTotal.value = total
                            _workProgressMessage.value =
                                "正在推断第 $done/$total 个基站 (ECI $curEci)，切到后台也会继续"
                        } else {
                            _workProgressMessage.value = "正在批量推断中... (即使切到后台也会继续)"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _workProgressMessage.value = "批量推断完成！"
                        val resultsPath = info.outputData.getString(com.example.bslocator.worker.BatchEstimationWorker.OUTPUT_RESULTS_FILE)
                        val summary = info.outputData.getString(com.example.bslocator.worker.BatchEstimationWorker.OUTPUT_SUMMARY) ?: ""
                        resultsPath?.let { p ->
                            try {
                                // 结果在缓存文件里（WorkManager Data 上限 10KB），读完即删
                                val file = java.io.File(p)
                                val json = file.readText()
                                file.delete()
                                val batchResults = com.google.gson.Gson().fromJson(
                                    json,
                                    Array<com.example.bslocator.worker.BatchEstimationWorker.BatchResult>::class.java
                                )
                                batchResults?.forEach { br ->
                                    val resultWithEci = br.result.copy(eci = br.eci)
                                    _estimationResults.add(resultWithEci)
                                }
                                _workProgressMessage.value = "批量推断完成: $summary"
                            } catch (e: Exception) {
                                _workError.value = "批量结果解析失败: ${e.message}"
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        _workProgressMessage.value = "批量推断失败"
                        _workError.value = info.outputData.getString(com.example.bslocator.worker.BatchEstimationWorker.OUTPUT_ERROR)
                            ?: "未知错误"
                    }
                    WorkInfo.State.CANCELLED -> {
                        _workProgressMessage.value = "批量任务已取消"
                    }
                    else -> {}
                }
            }
        }
        currentWorkObserver = observer
        workManager.getWorkInfoByIdLiveData(workRequest.id).observeForever(observer)
    }

    override fun onCleared() {
        super.onCleared()
        collectionService?.stopCollection()
        // Clean up WorkManager observer to prevent memory leak
        currentWorkObserver?.let { observer ->
            currentWorkId?.let { id ->
                workManager.getWorkInfoByIdLiveData(id).removeObserver(observer)
            }
        }
        currentWorkObserver = null
    }
}
