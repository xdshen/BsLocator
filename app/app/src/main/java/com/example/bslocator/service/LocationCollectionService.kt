package com.example.bslocator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.bslocator.MainActivity
import com.example.bslocator.R
import com.example.bslocator.data.Measurement
import com.example.bslocator.data.MeasurementDao
import com.example.bslocator.data.MeasurementSession
import com.example.bslocator.data.MeasurementSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationCollectionService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private var measurementDao: MeasurementDao? = null
    private var sessionDao: MeasurementSessionDao? = null

    // Current active session
    private var currentSessionId: Long = 0
    private var currentSessionName: String = ""

    // Latest known location (continuously updated)
    private var latestLocation: Location? = null
    private var locationAvailable = false

    // Collection state
    private var isCollecting = false
    private var _totalCollected = 0
    val totalCollected: Int get() = _totalCollected

    // Callback for UI updates
    var onMeasurementCollected: ((Measurement) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onSessionStarted: ((Long, String) -> Unit)? = null

    // Keep PhoneStateListener as member to prevent GC
    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            Log.d(TAG, "PhoneStateListener: onSignalStrengthsChanged")
            collectMeasurement("signalStrengthChanged")
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            Log.d(TAG, "PhoneStateListener: onCellInfoChanged, size=${cellInfo.size}")
            collectMeasurement("cellInfoChanged")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationCollectionService = this@LocationCollectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(FOREGROUND_SERVICE_ID, notification)

        val app = application as com.example.bslocator.BsLocatorApp
        measurementDao = app.database.measurementDao()
        sessionDao = app.database.sessionDao()

        startCollection()
        return START_STICKY
    }

    fun startCollection() {
        if (isCollecting) {
            Log.d(TAG, "Already collecting, skip")
            return
        }
        isCollecting = true
        _totalCollected = 0
        Log.i(TAG, "=== Collection started ===")
        postStatus("开始采集...")

        // Create a new session
        serviceScope.launch {
            val session = MeasurementSession(
                name = MeasurementSession.generateName(),
                startTime = System.currentTimeMillis(),
                endTime = -1,
                isActive = true
            )
            val sid = sessionDao?.insert(session) ?: 0L
            currentSessionId = sid
            currentSessionName = session.name
            Log.i(TAG, "Created session id=$sid, name=${session.name}")
            mainHandler.post {
                onSessionStarted?.invoke(sid, session.name)
            }
        }

        // 1. Request GPS updates (try multiple providers)
        // FUSED_PROVIDER only available on Android 12+ (API 31)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { locationManager.isProviderEnabled(it) }

        Log.i(TAG, "Available location providers: $providers")

        for (provider in providers) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    500,   // min time ms
                    0.5f,  // min distance m
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.i(TAG, "Registered location updates for provider: $provider")
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission missing for $provider", e)
                postStatus("错误: 缺少位置权限")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register $provider", e)
            }
        }

        // 2. Listen for signal strength changes
        try {
            telephonyManager.listen(
                signalListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        PhoneStateListener.LISTEN_CELL_INFO
            )
            Log.i(TAG, "PhoneStateListener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneStateListener", e)
        }

        // 3. Periodic fallback (every 2 seconds)
        serviceScope.launch {
            var attempts = 0
            while (isCollecting) {
                delay(2000)
                if (!isCollecting) break
                attempts++
                collectMeasurement("periodic_${attempts}")
            }
            Log.i(TAG, "Periodic collection loop ended after $attempts attempts")
        }

        // 4. Also try to get last known location immediately
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNetwork
            if (best != null && latestLocation == null) {
                latestLocation = best
                Log.i(TAG, "Using last known location: (${best.latitude}, ${best.longitude}) ±${best.accuracy}m")
            }
        } catch (_: SecurityException) {}
    }

    fun stopCollection() {
        Log.i(TAG, "=== Collection stopped ===")
        isCollecting = false
        try {
            locationManager.removeUpdates(locationCallback)
        } catch (_: Exception) {}
        try {
            telephonyManager.listen(signalListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}

        // Close the current session
        if (currentSessionId > 0) {
            serviceScope.launch {
                val session = sessionDao?.getById(currentSessionId)
                if (session != null) {
                    val updated = session.copy(
                        endTime = System.currentTimeMillis(),
                        isActive = false
                    )
                    sessionDao?.update(updated)
                    Log.i(TAG, "Closed session id=$currentSessionId, duration=${updated.durationMs}ms")
                }
                currentSessionId = 0
                currentSessionName = ""
            }
        }
    }

    /**
     * Core collection: grab cell info + sync with latest GPS
     */
    private fun collectMeasurement(source: String = "unknown") {
        if (!isCollecting) {
            Log.d(TAG, "collectMeasurement($source) skipped: not collecting")
            return
        }

        val timestamp = System.currentTimeMillis()
        Log.v(TAG, "collectMeasurement($source) called at $timestamp")

        // --- Step 1: Get cell info ---
        val cellInfos = try {
            telephonyManager.allCellInfo
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_PHONE_STATE permission missing - cannot read cell info", e)
            postStatus("错误: 缺少电话权限，无法读取基站信息")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cell info", e)
            null
        }

        if (cellInfos.isNullOrEmpty()) {
            Log.w(TAG, "cellInfos is empty/null - trying signalStrength fallback")
            // Fallback: try to collect using signalStrength API
            tryCollectFromSignalStrength(timestamp)
            return
        }

        Log.d(TAG, "cellInfos size=${cellInfos.size}, sources: ${cellInfos.map { it::class.simpleName }}")

        // --- Step 2: Find serving cell (LTE or NR) ---
        val servingCell = cellInfos.firstOrNull { it.isRegistered }

        if (servingCell == null) {
            Log.w(TAG, "No registered cell found in ${cellInfos.size} cells")
            return
        }

        Log.i(TAG, "Serving cell type: ${servingCell::class.simpleName}")

        // Extract cell identity and signal based on type
        val cellData = when (servingCell) {
            is CellInfoLte -> {
                val id = servingCell.cellIdentity
                val sig = servingCell.cellSignalStrength
                val eci = id.ci.toLong().coerceAtLeast(0)
                Log.i(TAG, "LTE cell - ECI=$eci, PCI=${id.pci}, TAC=${id.tac}, EARFCN=${id.earfcn}, RSRP=${sig.rsrp}")
                CellData(
                    eci = eci,
                    pci = id.pci,
                    earfcn = id.earfcn,
                    tac = id.tac,
                    mcc = id.mccString?.toIntOrNull() ?: -1,
                    mnc = id.mncString?.toIntOrNull() ?: -1,
                    rsrp = sig.rsrp,
                    rsrq = sig.rsrq,
                    rssnr = sig.rssnr,
                    cqi = sig.cqiTableIndex
                )
            }
            is CellInfoNr -> {
                val id = servingCell.cellIdentity as? CellIdentityNr
                val sig = servingCell.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                if (sig == null) {
                    Log.w(TAG, "NR cell but signalStrength is not CellSignalStrengthNr")
                    return
                }
                val nci = id?.nci?.coerceAtLeast(0) ?: -1
                val nrRsrp = sig.getNrField("ssRsrp") ?: sig.dbm
                val nrRsrq = sig.getNrField("ssRsrq") ?: Int.MAX_VALUE
                val nrSinr = sig.getNrField("ssSinr") ?: Int.MAX_VALUE
                Log.i(TAG, "NR cell - NCI=$nci, PCI=${id?.pci}, TAC=${id?.tac}, NARFCN=${id?.nrarfcn}, ssRsrp=$nrRsrp")
                CellData(
                    eci = nci,
                    pci = id?.pci ?: -1,
                    earfcn = id?.nrarfcn ?: -1,
                    tac = id?.tac ?: -1,
                    mcc = id?.mccString?.toIntOrNull() ?: -1,
                    mnc = id?.mncString?.toIntOrNull() ?: -1,
                    rsrp = nrRsrp,
                    rsrq = nrRsrq,
                    rssnr = nrSinr,
                    cqi = -1
                )
            }
            else -> {
                Log.w(TAG, "Unknown cell type: ${servingCell::class.simpleName}")
                return
            }
        }

        // --- Step 3: Get GPS location ---
        val gps = latestLocation
        if (gps == null) {
            Log.w(TAG, "GPS not available yet, skipping sample (wait for first fix)")
            if (!locationAvailable) {
                postStatus("等待 GPS 定位...")
            }
            return
        }

        // GPS accuracy filter: discard poor fixes
        if (gps.accuracy > 20.0f) {
            Log.w(TAG, "GPS accuracy too poor (${gps.accuracy}m > 15m), skipping sample")
            postStatus("GPS 精度不足: ${gps.accuracy.toInt()}m, 等待更好信号...")
            return
        }

        locationAvailable = true

        // GPS too old (> 10 seconds) -> skip
        if (timestamp - gps.time > 10000) {
            Log.w(TAG, "GPS too old (${(timestamp - gps.time) / 1000}s), skipping sample")
            return
        }

        // --- Step 4: Build and store measurement ---
        val measurement = Measurement(
            sessionId = currentSessionId,
            timestamp = timestamp,
            eci = cellData.eci,
            pci = cellData.pci,
            earfcn = cellData.earfcn,
            tac = cellData.tac,
            mcc = cellData.mcc,
            mnc = cellData.mnc,
            rsrp = cellData.rsrp,
            rsrq = cellData.rsrq,
            rssnr = cellData.rssnr,
            cqi = cellData.cqi,
            latitude = gps.latitude,
            longitude = gps.longitude,
            altitude = gps.altitude,
            gpsAccuracy = gps.accuracy,
            speed = gps.speed,
            bearing = gps.bearing
        )

        _totalCollected++

        // Update foreground notification every 10 samples
        if (_totalCollected % 10 == 1) {
            val notification = buildNotification()
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }

        // Persist to DB
        serviceScope.launch {
            measurementDao?.insert(measurement)
        }

        // Notify UI
        onMeasurementCollected?.invoke(measurement)

        val eciStr = if (cellData.eci >= 0) "ECI=${cellData.eci} " else ""
        val msg = "#${_totalCollected} ${eciStr}PCI=${cellData.pci} RSRP=${cellData.rsrp}dBm GPS=(${gps.latitude.format(6)}, ${gps.longitude.format(6)}) ±${gps.accuracy}m"
        Log.i(TAG, msg)
        postStatus(msg)
    }

    /**
     * Fallback: try to get signal info from TelephonyManager.getSignalStrength()
     * when allCellInfo is empty (common on some Android 10+ devices)
     */
    private fun tryCollectFromSignalStrength(timestamp: Long) {
        if (!isCollecting) return

        val gps = latestLocation
        if (gps == null) {
            Log.w(TAG, "Fallback: GPS not available")
            return
        }

        // Get signal strength via reflection/deprecated API as fallback
        val signalStrength = try {
            telephonyManager.signalStrength
        } catch (e: SecurityException) {
            Log.e(TAG, "Fallback: READ_PHONE_STATE missing for signalStrength", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Fallback: Failed to get signalStrength", e)
            null
        }

        if (signalStrength == null) {
            Log.w(TAG, "Fallback: signalStrength is null")
            return
        }

        // Try to extract LTE cell info from SignalStrength
        val cellSignalList = signalStrength.cellSignalStrengths
        val lteSignal = cellSignalList.firstOrNull { it is android.telephony.CellSignalStrengthLte }
            as? android.telephony.CellSignalStrengthLte

        if (lteSignal == null) {
            Log.w(TAG, "Fallback: No LTE signal in signalStrength")
            return
        }

        // For fallback, we can't get PCI/earfcn/tac from signalStrength alone
        // We'll use -1 as placeholder
        val measurement = Measurement(
            sessionId = currentSessionId,
            timestamp = timestamp,
            eci = -1,
            pci = -1,
            earfcn = -1,
            tac = -1,
            rsrp = lteSignal.rsrp,
            rsrq = lteSignal.rsrq,
            rssnr = lteSignal.rssnr,
            cqi = lteSignal.cqiTableIndex,
            latitude = gps.latitude,
            longitude = gps.longitude,
            altitude = gps.altitude,
            gpsAccuracy = gps.accuracy,
            speed = gps.speed,
            bearing = gps.bearing
        )

        _totalCollected++
        serviceScope.launch { measurementDao?.insert(measurement) }
        onMeasurementCollected?.invoke(measurement)

        Log.i(TAG, "Fallback collected #${_totalCollected} RSRP=${lteSignal.rsrp}dBm (PCI unknown)")
        postStatus("已采集 ${_totalCollected} 条 (PCI未知，需电话权限)")
    }

    private fun postStatus(msg: String) {
        mainHandler.post {
            onStatusUpdate?.invoke(msg)
        }
    }

    private val locationCallback = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
            if (!locationAvailable) {
                locationAvailable = true
                Log.i(TAG, "First location fix: (${location.latitude}, ${location.longitude}) ±${location.accuracy}m")
                postStatus("GPS 已定位: ±${location.accuracy.toInt()}m")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "基站路测采集",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台持续采集 GPS 与基站信号数据"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionInfo = if (currentSessionName.isNotEmpty()) " [$currentSessionName]" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("基站路测采集中$sessionInfo")
            .setContentText("已采集 $_totalCollected 条记录")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopCollection()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "BsLocatorSvc"
        private const val CHANNEL_ID = "bslocator_collection"
        private const val FOREGROUND_SERVICE_ID = 1001

        fun start(context: Context) {
            Log.d(TAG, "start() called from ${context::class.simpleName}")
            val intent = Intent(context, LocationCollectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Log.d(TAG, "stop() called")
            val intent = Intent(context, LocationCollectionService::class.java)
            context.stopService(intent)
        }
    }
}

// Helper data class for cell extraction
private data class CellData(
    val eci: Long,        // LTE: CI, NR: NCI — primary unique cell ID
    val pci: Int,
    val earfcn: Int,
    val tac: Int,
    val mcc: Int,
    val mnc: Int,
    val rsrp: Int,
    val rsrq: Int,
    val rssnr: Int,
    val cqi: Int
)

/**
 * Reflection helper to access NR signal fields that may not be available
 * as Kotlin properties in all SDK versions.
 */
private fun android.telephony.CellSignalStrengthNr.getNrField(name: String): Int? {
    return try {
        val method = this::class.java.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
        method.invoke(this) as? Int
    } catch (_: Exception) {
        // Try direct field access (Kotlin property)
        try {
            val field = this::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.get(this) as? Int
        } catch (_: Exception) {
            null
        }
    }
}

private fun Double.format(digits: Int) = java.lang.String.format("%.${digits}f", this)
