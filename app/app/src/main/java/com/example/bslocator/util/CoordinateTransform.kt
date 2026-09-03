package com.example.bslocator.util

import kotlin.math.*

/**
 * WGS-84 ↔ GCJ-02（国测局坐标/火星坐标）转换工具
 *
 * 中国境内地图（高德、百度、腾讯）使用 GCJ-02 坐标系，
 * 而 GPS 获取的是 WGS-84 坐标，两者存在固定偏移。
 *
 * 本工具提供双向转换，确保：
 * - 数据库存储：原始 WGS-84（保持精度，便于科学计算）
 * - 地图显示：GCJ-02（与高德地图对齐）
 * - 基站推断：WGS-84（算法使用原始坐标）
 */
object CoordinateTransform {

    data class LatLng(val lat: Double, val lng: Double)

    /**
     * 判断坐标是否在中国境内（大致范围，用于决定是否需要转换）
     */
    fun isInChina(lat: Double, lng: Double): Boolean {
        return lat in 0.83..56.0 && lng in 72.0..138.0
    }

    /**
     * WGS-84 → GCJ-02（用于在地图上显示）
     * @param wgsLat WGS-84 纬度
     * @param wgsLng WGS-84 经度
     * @return GCJ-02 坐标
     */
    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): LatLng {
        if (!isInChina(wgsLat, wgsLng)) {
            return LatLng(wgsLat, wgsLng)
        }
        val d = delta(wgsLat, wgsLng)
        return LatLng(wgsLat + d.lat, wgsLng + d.lng)
    }

    /**
     * GCJ-02 → WGS-84（逆向转换，极少使用）
     * @param gcjLat GCJ-02 纬度
     * @param gcjLng GCJ-02 经度
     * @return WGS-84 坐标
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): LatLng {
        if (!isInChina(gcjLat, gcjLng)) {
            return LatLng(gcjLat, gcjLng)
        }
        val d = delta(gcjLat, gcjLng)
        return LatLng(gcjLat - d.lat, gcjLng - d.lng)
    }

    /**
     * GCJ-02 → WGS-84（精确迭代版本，误差 < 1e-6）
     */
    fun gcj02ToWgs84Exact(gcjLat: Double, gcjLng: Double): LatLng {
        if (!isInChina(gcjLat, gcjLng)) {
            return LatLng(gcjLat, gcjLng)
        }
        var wgsLat = gcjLat
        var wgsLng = gcjLng
        var dLat: Double
        var dLng: Double
        repeat(10) {
            val d = delta(wgsLat, wgsLng)
            dLat = gcjLat - wgsLat - d.lat
            dLng = gcjLng - wgsLng - d.lng
            wgsLat += dLat
            wgsLng += dLng
            if (abs(dLat) < 1e-6 && abs(dLng) < 1e-6) return@repeat
        }
        return LatLng(wgsLat, wgsLng)
    }

    // ----- 核心算法（国测局坐标偏移公式） -----

    private fun delta(lat: Double, lng: Double): LatLng {
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - 0.00669342162296594323 * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLatOut = (dLat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * PI)
        val dLngOut = (dLng * 180.0) / (6378245.0 / sqrtMagic * cos(radLat) * PI)
        return LatLng(dLatOut, dLngOut)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / PI * 30.0)) * 2.0 / 3.0
        return ret
    }
}
