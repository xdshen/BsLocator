package com.example.bslocator.algorithm

import android.util.Log
import com.example.bslocator.data.Measurement
import kotlin.math.*

/**
 * Base station position and antenna pattern estimator.
 *
 * Optimizations applied:
 * 1. Analytical gradient (replaces numerical differentiation)
 * 2. Huber robust loss (replaces L2 least squares)
 * 3. Multi-start search (4 candidate initial guesses)
 * 4. 3GPP TR 38.901 antenna pattern model
 * 5. BS height optimization (bsHeight added to parameter vector)
 * 6. GPS accuracy filtering (discard points with accuracy > 15m)
 */
class BaseStationEstimator {

    data class EstimationResult(
        val bsLatitude: Double,
        val bsLongitude: Double,
        val azimuthDeg: Double,      // main lobe direction
        val beamwidthDeg: Double,    // 3dB horizontal beamwidth
        val tiltDeg: Double,         // electrical tilt
        val bsHeightM: Double,       // base station height (m)
        val pathLossExponent: Double,
        val referenceRssi: Double,   // RSSI at 1m
        val rmse: Double,            // fit quality
        val iterations: Int,
        val eci: Long = -1L          // associated cell ECI (for display)
    )

    companion object {
        private const val TAG = "BsEstimator"
        private const val EARTH_RADIUS = 6371000.0  // meters
        private const val UE_HEIGHT = 1.5           // UE height (m)
        private const val HUBER_DELTA = 5.0         // Huber threshold (dB)
        internal const val MAX_GPS_ACCURACY = 15.0f  // GPS accuracy filter (m)
        private const val V_BEAMWIDTH = 10.0        // vertical 3dB beamwidth (deg)
        private const val MAX_ATTEN = 30.0          // 3GPP max attenuation (dB)
        private const val FBR = 25.0                // front-to-back ratio (dB)
        private const val GRAD_TOL = 1e-6
        private const val MAX_ITER = 500
        private const val MIN_MEASUREMENTS = 10
    }

    /**
     * Main entry: estimate BS position and pattern from measurements.
     */
    fun estimate(measurements: List<Measurement>): EstimationResult? {
        // 6. GPS accuracy filtering
        val filtered = measurements.filter { it.gpsAccuracy < MAX_GPS_ACCURACY }
        if (filtered.size < MIN_MEASUREMENTS) {
            Log.w(TAG, "Need at least $MIN_MEASUREMENTS valid measurements, got ${filtered.size}")
            return null
        }
        Log.i(TAG, "Using ${filtered.size}/${measurements.size} measurements (accuracy < ${MAX_GPS_ACCURACY}m)")

        // Convert all lat/lng to local ENU coordinates (meters)
        val origin = filtered[0]
        val localPoints = filtered.map { m ->
            LocalPoint(
                east = lngToEast(m.longitude, origin.longitude, origin.latitude),
                north = latToNorth(m.latitude, origin.latitude),
                rssi = m.rsrp.toDouble()
            )
        }

        // 3. Multi-start: generate 4 candidate initial guesses
        val candidates = generateInitialGuesses(localPoints)
        Log.d(TAG, "Multi-start: evaluating ${candidates.size} candidates")

        // Optimize from each candidate, pick the one with lowest RMSE
        var bestResult: OptResult? = null
        var bestRmse = Double.MAX_VALUE

        for ((idx, initParams) in candidates.withIndex()) {
            val result = optimize(localPoints, initParams)
            Log.d(TAG, "Candidate $idx: RMSE=${result.rmse.format(2)} dB, " +
                    "pos=(${result.x.format(1)}, ${result.y.format(1)}), " +
                    "az=${result.azimuth.format(1)}\u00b0, h=${result.bsHeight.format(1)}m")
            if (result.rmse < bestRmse) {
                bestRmse = result.rmse
                bestResult = result
            }
        }

        if (bestResult == null) return null

        Log.i(TAG, "Best result: RMSE=${bestResult.rmse.format(2)} dB after ${bestResult.iterations} iterations")

        // Convert back to lat/lng
        val bsLat = origin.latitude + northToLat(bestResult.y)
        val bsLng = origin.longitude + eastToLng(bestResult.x, origin.latitude)

        return EstimationResult(
            bsLatitude = bsLat,
            bsLongitude = bsLng,
            azimuthDeg = normalizeAngle360(bestResult.azimuth),
            beamwidthDeg = bestResult.beamwidth.coerceIn(30.0, 120.0),
            tiltDeg = bestResult.tilt.coerceIn(0.0, 15.0),
            bsHeightM = bestResult.bsHeight.coerceIn(5.0, 50.0),
            pathLossExponent = bestResult.n.coerceIn(1.5, 5.0),
            referenceRssi = bestResult.p0.coerceIn(-80.0, -20.0),
            rmse = bestResult.rmse,
            iterations = bestResult.iterations
        )
    }

    /**
     * Generate 4 candidate initial guesses for multi-start optimization.
     */
    private fun generateInitialGuesses(points: List<LocalPoint>): List<DoubleArray> {
        val candidates = mutableListOf<DoubleArray>()

        // Candidate 1: signal-strength weighted centroid
        val weights = points.map { dbToLinear(it.rssi) }
        val totalWeight = weights.sum()
        val wX = points.zip(weights) { p, w -> p.east * w }.sum() / totalWeight
        val wY = points.zip(weights) { p, w -> p.north * w }.sum() / totalWeight
        candidates.add(buildParams(wX, wY, guessAzimuth(points, wX, wY)))

        // Candidate 2: strongest signal point
        val strongest = points.maxByOrNull { it.rssi } ?: points[0]
        candidates.add(buildParams(strongest.east, strongest.north, guessAzimuth(points, strongest.east, strongest.north)))

        // Candidate 3: geometric center of bounding box
        val minE = points.minOf { it.east }
        val maxE = points.maxOf { it.east }
        val minN = points.minOf { it.north }
        val maxN = points.maxOf { it.north }
        val cX = (minE + maxE) / 2.0
        val cY = (minN + maxN) / 2.0
        candidates.add(buildParams(cX, cY, guessAzimuth(points, cX, cY)))

        // Candidate 4: inverse-distance weighted centroid (weak signal = far = higher weight)
        val invWeights = points.map { 1.0 / (distTo(it, wX, wY) + 1.0) }
        val invTotal = invWeights.sum()
        val invX = points.zip(invWeights) { p, w -> p.east * w }.sum() / invTotal
        val invY = points.zip(invWeights) { p, w -> p.north * w }.sum() / invTotal
        candidates.add(buildParams(invX, invY, guessAzimuth(points, invX, invY)))

        return candidates
    }

    private fun buildParams(x: Double, y: Double, azimuth: Double): DoubleArray {
        // [bsX, bsY, azimuth, beamwidth, tilt, bsHeight, n, p0]
        return doubleArrayOf(x, y, azimuth, 65.0, 6.0, 30.0, 3.0, -40.0)
    }

    private fun distTo(p: LocalPoint, x: Double, y: Double): Double {
        val dx = p.east - x
        val dy = p.north - y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Guess initial azimuth: direction from candidate position to weakest-signal point.
     */
    private fun guessAzimuth(points: List<LocalPoint>, fromX: Double, fromY: Double): Double {
        val weakest = points.minByOrNull { it.rssi } ?: return 0.0
        val dx = weakest.east - fromX
        val dy = weakest.north - fromY
        return Math.toDegrees(atan2(dx, dy))
    }

    /**
     * Optimizer with backtracking line search and analytical gradient.
     */
    private fun optimize(points: List<LocalPoint>, initParams: DoubleArray): OptResult {
        val lower = doubleArrayOf(
            points.minOf { it.east } - 500,
            points.minOf { it.north } - 500,
            0.0, 30.0, 0.0, 5.0, 1.5, -80.0
        )
        val upper = doubleArrayOf(
            points.maxOf { it.east } + 500,
            points.maxOf { it.north } + 500,
            360.0, 120.0, 15.0, 50.0, 5.0, -20.0
        )

        var params = initParams.copyOf()
        var bestLoss = Double.MAX_VALUE
        var bestParams = params.copyOf()
        var totalIter = 0

        for (iter in 0 until MAX_ITER) {
            val (loss, grad) = computeLossAndGrad(points, params)
            totalIter++

            if (loss < bestLoss) {
                bestLoss = loss
                bestParams = params.copyOf()
            }

            // Projected gradient norm for convergence check
            var pgNormSq = 0.0
            for (i in grad.indices) {
                val pg = when {
                    params[i] <= lower[i] && grad[i] > 0 -> 0.0
                    params[i] >= upper[i] && grad[i] < 0 -> 0.0
                    else -> grad[i]
                }
                pgNormSq += pg * pg
            }
            if (sqrt(pgNormSq) < GRAD_TOL) {
                Log.d(TAG, "Converged at iteration $iter (proj-grad norm < $GRAD_TOL)")
                break
            }

            // Backtracking line search (Armijo condition)
            var alpha = 1.0
            val dir = DoubleArray(grad.size) { -grad[it] }
            val directionalDeriv = grad.zip(dir) { g, d -> g * d }.sum()
            var found = false

            for (_ls in 0 until 20) {
                val trial = DoubleArray(params.size) { i ->
                    (params[i] + alpha * dir[i]).coerceIn(lower[i], upper[i])
                }
                val trialLoss = computeLoss(points, trial)
                totalIter++

                if (trialLoss <= loss + 1e-4 * alpha * directionalDeriv) {
                    params = trial
                    found = true
                    break
                }
                alpha *= 0.5
            }

            if (!found) {
                // Fallback: small fixed step
                params = DoubleArray(params.size) { i ->
                    (params[i] - 0.001 * grad[i]).coerceIn(lower[i], upper[i])
                }
            }
        }

        // Final evaluation on best params
        val finalRmse = sqrt(bestLoss / points.size)
        return OptResult(
            x = bestParams[0],
            y = bestParams[1],
            azimuth = bestParams[2],
            beamwidth = bestParams[3],
            tilt = bestParams[4],
            bsHeight = bestParams[5],
            n = bestParams[6],
            p0 = bestParams[7],
            rmse = finalRmse,
            iterations = totalIter
        )
    }

    /**
     * Compute predicted RSSI for a single measurement point.
     */
    private fun predictedRssi(p: LocalPoint, params: DoubleArray): Double {
        val bsX = params[0]
        val bsY = params[1]
        val azimuth = params[2]
        val beamwidth = params[3]
        val tilt = params[4]
        val bsHeight = params[5]
        val n = params[6]
        val p0 = params[7]

        val dx = p.east - bsX
        val dy = p.north - bsY
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)

        // Path loss
        val pathLoss = p0 - 10.0 * n * log10(dist)

        // 4. 3GPP antenna pattern gain
        val bearing = Math.toDegrees(atan2(dx, dy))
        val gain = antennaGain3GPP(bearing, azimuth, beamwidth, tilt, bsHeight, dist)

        return pathLoss + gain
    }

    /**
     * 4. Antenna pattern model based on 3GPP TR 38.901, with one deliberate
     * modification: the hard 30 dB attenuation cap (min[raw, 30]) is replaced by
     * a smooth tanh saturation approaching the same 30 dB floor.
     *
     * Why: the hard cap has zero gradient beyond the knee, so all points in that
     * region (e.g. measurements taken very close to a site, at high elevation)
     * carry no information about tilt/beamwidth/height. tanh keeps the 3GPP
     * shape near boresight, approaches the same floor (empirically ~28-30 dB in
     * our field data), and preserves gradient information everywhere.
     * A/B on 44 real cells: no cost overall, clear gains for near-site geometry.
     *
     * A(raw) = -A_m * tanh(raw / A_m), raw = 12(theta/theta_3dB)^2
     */
    private fun antennaGain3GPP(
        bearing: Double,
        azimuth: Double,
        beamwidth: Double,
        tilt: Double,
        bsHeight: Double,
        dist: Double
    ): Double {
        val dAz = normalizeAngle180(bearing - azimuth)

        // Horizontal pattern (smooth saturation)
        val hGainRaw = 12.0 * (dAz / beamwidth).pow(2)
        val hGain = -MAX_ATTEN * tanh(hGainRaw / MAX_ATTEN)

        // Vertical pattern (smooth saturation)
        val hDiff = bsHeight - UE_HEIGHT
        val elevation = Math.toDegrees(atan2(hDiff, dist.coerceAtLeast(1.0)))
        val dEl = elevation + tilt
        val vGainRaw = 12.0 * (dEl / V_BEAMWIDTH).pow(2)
        val vGain = -MAX_ATTEN * tanh(vGainRaw / MAX_ATTEN)

        // Combined pattern with front-to-back ratio
        val fbr = if (abs(dAz) > 90.0) -FBR else 0.0

        return hGain + vGain + fbr
    }

    /**
     * Compute total Huber loss (for line search).
     */
    private fun computeLoss(points: List<LocalPoint>, params: DoubleArray): Double {
        var total = 0.0
        for (p in points) {
            val pred = predictedRssi(p, params)
            val error = pred - p.rssi
            total += huberLoss(error, HUBER_DELTA)
        }
        return total
    }

    /**
     * 1. Analytical gradient + 2. Huber robust loss.
     *
     * Computes total Huber loss and its analytical gradient w.r.t. 8 parameters:
     * [bsX, bsY, azimuth, beamwidth, tilt, bsHeight, n, p0]
     */
    private fun computeLossAndGrad(points: List<LocalPoint>, params: DoubleArray): Pair<Double, DoubleArray> {
        val bsX = params[0]
        val bsY = params[1]
        val azimuth = params[2]
        val beamwidth = params[3]
        val tilt = params[4]
        val bsHeight = params[5]
        val n = params[6]
        val p0 = params[7]

        val grad = DoubleArray(8) { 0.0 }
        var totalLoss = 0.0

        for (p in points) {
            val dx = p.east - bsX
            val dy = p.north - bsY
            val distSq = dx * dx + dy * dy
            val dist = sqrt(distSq).coerceAtLeast(1.0)

            // Bearing and elevation
            val bearing = Math.toDegrees(atan2(dx, dy))
            val dAz = normalizeAngle180(bearing - azimuth)
            val hDiff = bsHeight - UE_HEIGHT
            val elevation = Math.toDegrees(atan2(hDiff, dist.coerceAtLeast(1.0)))
            val dEl = elevation + tilt

            // Predicted RSSI (smooth tanh saturation, see antennaGain3GPP)
            val pathLoss = p0 - 10.0 * n * log10(dist)
            val hGainRaw = 12.0 * (dAz / beamwidth).pow(2)
            val hTanh = tanh(hGainRaw / MAX_ATTEN)
            val hGain = -MAX_ATTEN * hTanh
            val vGainRaw = 12.0 * (dEl / V_BEAMWIDTH).pow(2)
            val vTanh = tanh(vGainRaw / MAX_ATTEN)
            val vGain = -MAX_ATTEN * vTanh
            val fbr = if (abs(dAz) > 90.0) -FBR else 0.0
            val gain = hGain + vGain + fbr
            val pred = pathLoss + gain

            // Error and Huber derivative
            val error = pred - p.rssi
            totalLoss += huberLoss(error, HUBER_DELTA)
            val de = huberDeriv(error, HUBER_DELTA)

            // Common intermediate values for gradient
            val invDist = 1.0 / dist
            val invDistSq = 1.0 / distSq
            val degPerRad = 180.0 / PI

            // \u2202bearing/\u2202bsX = -dy * degPerRad / dist^2
            // \u2202bearing/\u2202bsY =  dx * degPerRad / dist^2
            val dbearing_dbsX = -dy * degPerRad * invDistSq
            val dbearing_dbsY =  dx * degPerRad * invDistSq

            // \u2202dAz/\u2202azimuth = -1
            val ddAz_dazimuth = -1.0

            // \u2202elevation/\u2202bsHeight = dist * degPerRad / (dist^2 + hDiff^2)
            val delev_dh = dist * degPerRad / (distSq + hDiff * hDiff)

            // \u2202elevation/\u2202bsX = \u2202elevation/\u2202dist * \u2202dist/\u2202bsX
            // \u2202elevation/\u2202dist = -hDiff * degPerRad / (dist^2 + hDiff^2)
            // \u2202dist/\u2202bsX = -dx/dist
            val delev_ddist = -hDiff * degPerRad / (distSq + hDiff * hDiff)
            val ddist_dbsX = -dx * invDist
            val ddist_dbsY = -dy * invDist
            val delev_dbsX = delev_ddist * ddist_dbsX
            val delev_dbsY = delev_ddist * ddist_dbsY

            // Horizontal gain derivatives.
            // A = -A_m*tanh(raw/A_m) => dA/draw = -sech^2(raw/A_m), never zero —
            // points past the knee keep gradient information.
            val hSech2 = 1.0 - hTanh * hTanh
            val dhGain_ddAz = -hSech2 * 24.0 * dAz / (beamwidth * beamwidth)
            val dhGain_dbsX = dhGain_ddAz * dbearing_dbsX
            val dhGain_dbsY = dhGain_ddAz * dbearing_dbsY
            val dhGain_dazimuth = dhGain_ddAz * ddAz_dazimuth
            val dhGain_dbw = hSech2 * 24.0 * dAz * dAz / (beamwidth * beamwidth * beamwidth)

            // Vertical gain derivatives (same smooth form)
            val vSech2 = 1.0 - vTanh * vTanh
            val dvGain_ddEl = -vSech2 * 24.0 * dEl / (V_BEAMWIDTH * V_BEAMWIDTH)
            val dvGain_dbsX = dvGain_ddEl * delev_dbsX
            val dvGain_dbsY = dvGain_ddEl * delev_dbsY
            val dvGain_dtilt = dvGain_ddEl
            val dvGain_dh = dvGain_ddEl * delev_dh

            // Path loss derivatives
            val dPL_ddist = -10.0 * n / (dist * ln(10.0))
            val dPL_dbsX = dPL_ddist * ddist_dbsX
            val dPL_dbsY = dPL_ddist * ddist_dbsY
            val dPL_dn = -10.0 * log10(dist)
            val dPL_dp0 = 1.0

            // Total derivatives of predicted RSSI
            val dpred_dbsX = dPL_dbsX + dhGain_dbsX + dvGain_dbsX
            val dpred_dbsY = dPL_dbsY + dhGain_dbsY + dvGain_dbsY
            val dpred_dazimuth = dhGain_dazimuth
            val dpred_dbw = dhGain_dbw
            val dpred_dtilt = dvGain_dtilt
            val dpred_dh = dvGain_dh
            val dpred_dn = dPL_dn
            val dpred_dp0 = dPL_dp0

            // Accumulate gradient (chain rule: \u2202L/\u2202\u03b8 = \u03a3 de * \u2202pred/\u2202\u03b8)
            grad[0] += de * dpred_dbsX
            grad[1] += de * dpred_dbsY
            grad[2] += de * dpred_dazimuth
            grad[3] += de * dpred_dbw
            grad[4] += de * dpred_dtilt
            grad[5] += de * dpred_dh
            grad[6] += de * dpred_dn
            grad[7] += de * dpred_dp0
        }

        return totalLoss to grad
    }

    /**
     * 2. Huber loss function.
     */
    private fun huberLoss(error: Double, delta: Double): Double {
        val absE = abs(error)
        return if (absE <= delta) {
            0.5 * error * error
        } else {
            delta * (absE - 0.5 * delta)
        }
    }

    /**
     * 2. Huber loss derivative w.r.t. error.
     */
    private fun huberDeriv(error: Double, delta: Double): Double {
        return when {
            error > delta -> delta
            error < -delta -> -delta
            else -> error
        }
    }

    private fun normalizeAngle180(angle: Double): Double {
        var a = angle
        while (a > 180.0) a -= 360.0
        while (a <= -180.0) a += 360.0
        return a
    }

    private fun normalizeAngle360(angle: Double): Double {
        var a = angle
        while (a < 0.0) a += 360.0
        while (a >= 360.0) a -= 360.0
        return a
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 10.0)

    // Coordinate conversions
    private fun latToNorth(lat: Double, refLat: Double): Double =
        (lat - refLat) * PI / 180.0 * EARTH_RADIUS

    private fun lngToEast(lng: Double, refLng: Double, refLat: Double): Double =
        (lng - refLng) * PI / 180.0 * EARTH_RADIUS * cos(Math.toRadians(refLat))

    private fun northToLat(north: Double): Double =
        north / EARTH_RADIUS * 180.0 / PI

    private fun eastToLng(east: Double, lat: Double): Double =
        east / (EARTH_RADIUS * cos(Math.toRadians(lat))) * 180.0 / PI

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)

    private data class LocalPoint(
        val east: Double,
        val north: Double,
        val rssi: Double
    )

    private data class OptResult(
        val x: Double, val y: Double,
        val azimuth: Double, val beamwidth: Double, val tilt: Double,
        val bsHeight: Double,
        val n: Double, val p0: Double,
        val rmse: Double, val iterations: Int,
        val eci: Long = -1L          // associated cell ECI (for display)
    )
}
