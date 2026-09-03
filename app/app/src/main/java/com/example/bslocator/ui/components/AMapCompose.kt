package com.example.bslocator.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.withSave
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.PolylineOptions
import kotlin.math.*
import com.example.bslocator.algorithm.BaseStationEstimator
import com.example.bslocator.data.Measurement
import com.example.bslocator.util.CoordinateTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 追踪上一次绘制的数据，避免不必要的重绘
private var lastDrawnMeasurements: List<Measurement>? = null
private var lastDrawnBsResults: List<BaseStationEstimator.EstimationResult>? = null

private var activeInfoWindowMarker: Marker? = null

private val BsResultColors = listOf(
    0xFFFF5722, // 0 橙红
    0xFF2196F3, // 1 蓝
    0xFF4CAF50, // 2 绿
    0xFF9C27B0, // 3 紫
    0xFFFF9800, // 4 橙
    0xFF00BCD4, // 5 青
    0xFFE91E63, // 6 粉
    0xFF3F51B5, // 7 靛蓝
)

private val CellColors = listOf(
    android.graphics.Color.parseColor("#E53935"), // 0  红
    android.graphics.Color.parseColor("#1E88E5"), // 1  蓝
    android.graphics.Color.parseColor("#43A047"), // 2  绿
    android.graphics.Color.parseColor("#FB8C00"), // 3  橙
    android.graphics.Color.parseColor("#8E24AA"), // 4  紫
    android.graphics.Color.parseColor("#00ACC1"), // 5  青
    android.graphics.Color.parseColor("#FDD835"), // 6  黄
    android.graphics.Color.parseColor("#3949AB"), // 7  靛蓝
    android.graphics.Color.parseColor("#D81B60"), // 8  玫红
    android.graphics.Color.parseColor("#00897B"), // 9  水鸭
    android.graphics.Color.parseColor("#6D4C41"), // 10 棕
    android.graphics.Color.parseColor("#7CB342"), // 11 浅绿
)

fun getCellColor(eci: Long): Int {
    if (eci < 0) return CellColors[0]
    return CellColors[(eci % CellColors.size).toInt()]
}

fun getSignalBrightness(rsrp: Int): Float {
    return when {
        rsrp >= -80 -> 1.00f
        rsrp >= -90 -> 0.88f
        rsrp >= -100 -> 0.74f
        rsrp >= -110 -> 0.56f
        else -> 0.38f
    }
}

fun composeColor(baseColor: Int, brightness: Float): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor, hsv)
    hsv[2] = hsv[2] * brightness
    hsv[1] = hsv[1] * (0.6f + 0.4f * brightness)
    return android.graphics.Color.HSVToColor(hsv)
}

fun extractCellColorMap(measurements: List<Measurement>): Map<Long, Int> {
    return measurements
        .map { it.eci }
        .distinct()
        .associateWith { getCellColor(it) }
}

@Composable
fun AMapCompose(
    modifier: Modifier = Modifier,
    measurements: List<Measurement> = emptyList(),
    bsResults: List<BaseStationEstimator.EstimationResult> = emptyList(),
    onMapReady: ((AMap) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    ) { view ->
        view.map?.let { aMap ->
            configureMap(aMap, context)

            val measurementsChanged = lastDrawnMeasurements !== measurements
            val bsResultsChanged = lastDrawnBsResults !== bsResults

            if (measurementsChanged || bsResultsChanged) {
                drawAllOverlays(aMap, measurements, bsResults, context)
                lastDrawnMeasurements = measurements
                lastDrawnBsResults = bsResults
            }

            onMapReady?.invoke(aMap)
        }
    }
}

private fun configureMap(aMap: AMap, context: Context) {
    val myLocationStyle = MyLocationStyle()
        .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
        .interval(1000)
        .showMyLocation(true)
    aMap.myLocationStyle = myLocationStyle
    aMap.isMyLocationEnabled = true

    aMap.uiSettings.apply {
        isZoomControlsEnabled = true
        isCompassEnabled = true
        isScaleControlsEnabled = true
    }

    aMap.mapType = AMap.MAP_TYPE_NORMAL

    // 设置自定义 InfoWindow 适配器
    aMap.setInfoWindowAdapter(MeasurementInfoWindowAdapter(context))

    // 点击 Marker：切换 InfoWindow 显示/隐藏
    aMap.setOnMarkerClickListener { marker ->
        if (marker.isInfoWindowShown) {
            marker.hideInfoWindow()
            activeInfoWindowMarker = null
        } else {
            activeInfoWindowMarker?.hideInfoWindow()
            marker.showInfoWindow()
            activeInfoWindowMarker = marker
        }
        true
    }

    // 点击地图空白处：隐藏 InfoWindow
    aMap.setOnMapClickListener {
        activeInfoWindowMarker?.hideInfoWindow()
        activeInfoWindowMarker = null
    }

    // 点击 InfoWindow 本身也隐藏
    aMap.setOnInfoWindowClickListener { marker ->
        marker.hideInfoWindow()
        activeInfoWindowMarker = null
    }
}

/**
 * 自定义 InfoWindow：点击 Marker 时弹出详细测量信息
 */
private class MeasurementInfoWindowAdapter(private val context: Context) : AMap.InfoWindowAdapter {

    override fun getInfoWindow(marker: Marker): View? {
        return createInfoView(marker)
    }

    override fun getInfoContents(marker: Marker): View? {
        return null
    }

    private fun createInfoView(marker: Marker): View {
        val objectStr = marker.`object` as? String ?: ""
        val infoMap = parseInfo(objectStr)

        // 解析颜色值（从字符串转 Int）
        val colorStr = infoMap["color"] ?: ""
        val accentColor = colorStr.toIntOrNull() ?: 0xFF444444.toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = roundedBackground(accentColor)
        }

        // 标题
        val titleView = TextView(context).apply {
            text = marker.title
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 0)
        }
        container.addView(titleView)

        // 分隔线
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, 0, 0, dp(1)) }
            setBackgroundColor(0x40FFFFFF)
        }
        container.addView(divider)

        // 信息行
        fun addRow(label: String, value: String) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
            }
            val labelTv = TextView(context).apply {
                text = label
                textSize = 10f
                includeFontPadding = false
                setTextColor(0xFFB0B0B0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            }
            val valueTv = TextView(context).apply {
                text = value
                textSize = 10f
                includeFontPadding = false
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            row.addView(labelTv)
            row.addView(valueTv)
            container.addView(row)
        }

        // BS marker shows different info than measurement markers
        if (infoMap.containsKey("bs_info")) {
            addRow("纬度", infoMap["lat"] ?: "--")
            addRow("经度", infoMap["lng"] ?: "--")
            addRow("方位角", infoMap["azimuth"] ?: "--")
            addRow("波束宽度", infoMap["beamwidth"] ?: "--")
            addRow("下倾角", infoMap["tilt"] ?: "--")
            addRow("基站高度", infoMap["height"] ?: "--")
            addRow("RMSE", infoMap["rmse"] ?: "--")
        } else {
            addRow("RSRP", infoMap["rsrp"] ?: "--")
            addRow("RSRQ", infoMap["rsrq"] ?: "--")
            addRow("SINR", infoMap["sinr"] ?: "--")
            addRow("CQI", infoMap["cqi"] ?: "--")
            addRow("EARFCN", infoMap["earfcn"] ?: "--")
            addRow("TAC", infoMap["tac"] ?: "--")
            addRow("坐标", infoMap["coord"] ?: "--")
            addRow("GPS精度", infoMap["accuracy"] ?: "--")
            addRow("速度", infoMap["speed"] ?: "--")
            addRow("时间", infoMap["time"] ?: "--")
        }

        return container
    }

    private fun parseInfo(infoStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (infoStr.isEmpty()) return result
        infoStr.split("|").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }

    private fun dp(px: Int): Int {
        return (px * context.resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(0xEE2A2A2A.toInt())
            setStroke(dp(1), accentColor)
        }
    }
}

/**
 * 统一绘制所有覆盖物（测量点 + 基站推断结果），仅在数据变化时调用
 */
private fun drawAllOverlays(
    aMap: AMap,
    measurements: List<Measurement>,
    bsResults: List<BaseStationEstimator.EstimationResult>,
    context: Context
) {
    aMap.clear()
    activeInfoWindowMarker = null

    if (measurements.isEmpty() && bsResults.isEmpty()) return

    // ---- 1. 绘制轨迹线（原始点或简化后）----
    if (measurements.size >= 2) {
        val sorted = measurements.sortedBy { it.timestamp }
        val linePoints = if (sorted.size > 600) {
            // 轨迹线简化：超过 600 点时均匀采样到 500
            val step = sorted.size / 500.0
            (0 until 500).map { i -> sorted[(i * step).toInt().coerceAtMost(sorted.lastIndex)] }
        } else sorted

        val polylinePoints = linePoints.map { m ->
            val gcj = CoordinateTransform.wgs84ToGcj02(m.latitude, m.longitude)
            LatLng(gcj.lat, gcj.lng)
        }
        aMap.addPolyline(
            PolylineOptions()
                .addAll(polylinePoints)
                .width(4f)
                .color(0xFF448AFF.toInt())
                .geodesic(true)
        )
    }

    // ---- 2. 绘制测量点标记（全部真实点，小尺寸）----
    if (measurements.isNotEmpty()) {
        val builder = LatLngBounds.Builder()
        val markerBitmapCache = mutableMapOf<Int, Bitmap>()

        measurements.forEach { m ->
            val gcj = CoordinateTransform.wgs84ToGcj02(m.latitude, m.longitude)
            val latLng = LatLng(gcj.lat, gcj.lng)
            builder.include(latLng)

            val baseColor = getCellColor(m.eci)
            val brightness = getSignalBrightness(m.rsrp)
            val finalColor = composeColor(baseColor, brightness)

            val bitmap = markerBitmapCache.getOrPut(finalColor) {
                createCircleMarkerBitmap(context, finalColor, 8)
            }

            val eciLabel = if (m.eci >= 0) "ECI ${m.eci}" else "未知基站"
            val title = "$eciLabel   PCI ${m.pci}"

            val infoStr = buildString {
                append("rsrp=${m.rsrp} dBm|")
                append("rsrq=${m.rsrq} dB|")
                append("sinr=${m.rssnr} dB|")
                append("cqi=${m.cqi}|")
                append("earfcn=${m.earfcn}|")
                append("tac=${m.tac}|")
                append("coord=${m.latitude.format(5)}, ${m.longitude.format(5)}|")
                append("accuracy=±${m.gpsAccuracy}m|")
                append("speed=${m.speed} m/s|")
                append("time=${formatTime(m.timestamp)}|")
                append("color=$finalColor")
            }

            aMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                    .title(title)
                    .draggable(false)
            )?.apply { `object` = infoStr }
        }

        // 自动缩放（仅在首次加载时执行，避免打断用户手动缩放）
        if (aMap.cameraPosition == null || aMap.cameraPosition.zoom < 3f) {
            try {
                val bounds = builder.build()
                aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            } catch (_: Exception) {
                val firstGcj = CoordinateTransform.wgs84ToGcj02(
                    measurements.first().latitude,
                    measurements.first().longitude
                )
                aMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(firstGcj.lat, firstGcj.lng), 17f)
                )
            }
        }
    }


    // ---- 3. 绘制基站推断结果 ----
    bsResults.forEachIndexed { index, result ->
        drawBsResult(aMap, result, measurements, context, index)
    }
}

private fun drawBsResult(
    aMap: AMap,
    result: BaseStationEstimator.EstimationResult,
    measurements: List<Measurement>,
    context: Context,
    index: Int = 0
) {
    val colorInt = BsResultColors[index % BsResultColors.size]
    val gcj = CoordinateTransform.wgs84ToGcj02(result.bsLatitude, result.bsLongitude)
    val bsLatLng = LatLng(gcj.lat, gcj.lng)

    // ---- 1. 绘制天线主瓣覆盖扇区 ----
    val sectorRadiusM = calculateSectorRadius(result, measurements)
    val sectorPoints = generateSectorPoints(
        result.bsLatitude, result.bsLongitude,
        result.azimuthDeg, result.beamwidthDeg, sectorRadiusM
    )
    if (sectorPoints.size >= 3) {
        val gcjSector = sectorPoints.map { pt ->
            val c = CoordinateTransform.wgs84ToGcj02(pt.first, pt.second)
            LatLng(c.lat, c.lng)
        }
        aMap.addPolygon(
            PolygonOptions()
                .addAll(gcjSector)
                .fillColor((colorInt and 0x00FFFFFF or 0x40000000).toInt()) // 半透明
                .strokeColor(colorInt.toInt())
                .strokeWidth(2f)
        )
    }

    // ---- 2. 绘制基站位置标记（大号彩色星标）----
    val bsBitmap = createBsMarkerBitmap(context, colorInt.toInt())
    val title = "📡 推断基站 #${index + 1}"
    val infoStr = buildString {
        append("bs_info=true|")
        append("lat=${result.bsLatitude.format(6)}°|")
        append("lng=${result.bsLongitude.format(6)}°|")
        append("azimuth=${result.azimuthDeg.format(1)}°|")
        append("beamwidth=${result.beamwidthDeg.format(1)}°|")
        append("tilt=${result.tiltDeg.format(1)}°|")
        append("height=${result.bsHeightM.format(1)}m|")
        append("rmse=${result.rmse.format(2)} dB|")
        append("color=${colorInt.toInt()}")
    }

    aMap.addMarker(
        MarkerOptions()
            .position(bsLatLng)
            .icon(BitmapDescriptorFactory.fromBitmap(bsBitmap))
            .title(title)
            .snippet("点击查看推断详情")
            .zIndex(10f)
            .anchor(0.5f, 0.5f)
    )?.apply {
        `object` = infoStr
    }

    // ---- 3. 绘制方向指示线（从基站沿方位角向外）----
    val lineLengthM = sectorRadiusM * 1.2
    val lineEnd = latLngAtBearing(result.bsLatitude, result.bsLongitude, result.azimuthDeg, lineLengthM)
    val gcjLineEnd = CoordinateTransform.wgs84ToGcj02(lineEnd.first, lineEnd.second)
    aMap.addPolyline(
        PolylineOptions()
            .add(bsLatLng)
            .add(LatLng(gcjLineEnd.lat, gcjLineEnd.lng))
            .width(3f)
            .color(colorInt.toInt())
            .geodesic(true)
    )
}
/**
 * 计算扇区半径：以主瓣覆盖范围内测量点距离的 90 分位数为基准（×1.2），
 * 钳制在 100m ~ 2000m，避免个别远距离点把扇区画得远超实际覆盖范围
 */
private fun calculateSectorRadius(
    result: BaseStationEstimator.EstimationResult,
    measurements: List<Measurement>
): Double {
    if (measurements.isEmpty()) return 200.0

    fun bearingTo(m: Measurement): Double {
        val lat1 = Math.toRadians(result.bsLatitude)
        val lat2 = Math.toRadians(m.latitude)
        val dLng = Math.toRadians(m.longitude - result.bsLongitude)
        val y = Math.sin(dLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    val halfBw = result.beamwidthDeg / 2.0
    val all = measurements.map { m ->
        val d = haversineDistance(result.bsLatitude, result.bsLongitude, m.latitude, m.longitude)
        var dAz = Math.abs(bearingTo(m) - result.azimuthDeg) % 360.0
        if (dAz > 180.0) dAz = 360.0 - dAz
        d to dAz
    }
    val mainLobe = all.filter { (_, dAz) -> dAz <= halfBw }.map { it.first }
    val dists = (if (mainLobe.isNotEmpty()) mainLobe else all.map { it.first }).sorted()

    val p90 = dists[(dists.size * 0.9).toInt().coerceAtMost(dists.size - 1)]
    return (p90 * 1.2).coerceIn(100.0, 2000.0)
}

/**
 * 生成扇形多边形点列表（WGS84）
 */
private fun generateSectorPoints(
    centerLat: Double, centerLng: Double,
    azimuthDeg: Double, beamwidthDeg: Double, radiusM: Double
): List<Pair<Double, Double>> {
    val points = mutableListOf<Pair<Double, Double>>()
    points.add(centerLat to centerLng) // 扇形中心点

    val halfBw = beamwidthDeg / 2.0
    val startAz = azimuthDeg - halfBw
    val endAz = azimuthDeg + halfBw
    val steps = 30 // 弧线上的点数

    for (i in 0..steps) {
        val frac = i / steps.toDouble()
        val angle = startAz + (endAz - startAz) * frac
        val pt = latLngAtBearing(centerLat, centerLng, angle, radiusM)
        points.add(pt)
    }
    return points
}

/**
 * 给定起点、方位角（0°=北，顺时针）、距离（米），计算终点 WGS84
 */
private fun latLngAtBearing(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
    val R = 6371000.0
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lng)
    val brng = Math.toRadians(bearingDeg)
    val d = distanceM / R

    val lat2 = Math.toDegrees(
        asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
    )
    val lon2 = Math.toDegrees(
        lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(Math.toRadians(lat2)))
    )
    return lat2 to lon2
}

private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return 2 * R * kotlin.math.asin(kotlin.math.sqrt(a))
}

private fun createCircleMarkerBitmap(context: Context, color: Int, sizeDp: Int): Bitmap {
    val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    val center = sizePx / 2f
    val radius = sizePx * 0.45f

    canvas.withSave {
        canvas.drawCircle(center, center, radius, paint)
    }

    return bitmap
}

/**
 * 创建基站位置标记 Bitmap（红色星形，较大尺寸）
 */
private fun createBsMarkerBitmap(context: Context, color: Int): Bitmap {
    val sizeDp = 32
    val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = sizePx / 2f
    val centerY = sizePx / 2f
    val outerR = sizePx * 0.45f
    val innerR = sizePx * 0.18f

    // 五角星路径
    val path = android.graphics.Path()
    val starPoints = 5
    for (i in 0 until starPoints * 2) {
        val angle = Math.toRadians(i * 36.0 - 90) // 从顶部开始
        val r = if (i % 2 == 0) outerR else innerR
        val x = centerX + (r * cos(angle)).toFloat()
        val y = centerY + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    // 填充
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawPath(path, fillPaint)

    // 描边
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.06f
    }
    canvas.drawPath(path, strokePaint)

    return bitmap
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

private fun Double.format(digits: Int): String {
    return String.format(java.util.Locale.US, "%.${digits}f", this)
}
