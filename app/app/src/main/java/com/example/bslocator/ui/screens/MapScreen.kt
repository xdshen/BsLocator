package com.example.bslocator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bslocator.data.Measurement
import com.example.bslocator.ui.components.AMapCompose
import com.example.bslocator.ui.components.extractCellColorMap
import com.example.bslocator.viewmodel.MainViewModel
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val measurements by viewModel.selectedSessionMeasurements.collectAsState(initial = emptyList())
    val selectedSessionIds by viewModel.selectedSessionIds.collectAsState()
    val estimationResults = viewModel.estimationResults

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量轨迹 - 高德地图") },
                actions = {
                    // Clear BS marker button (only when estimation exists)
                    if (estimationResults.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearEstimationResult() }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空基站标识",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (selectedSessionIds.isNotEmpty()) {
                        Text(
                            text = "已选 ${selectedSessionIds.size} 条",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Session filter hint
            if (selectedSessionIds.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "地图仅显示已选日志的数据",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButtonCompact(
                            onClick = { viewModel.clearSessionSelection() },
                            text = "清除选择"
                        )
                    }
                }
            }

            // Estimation result hint
            if (estimationResults.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFE8E0)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "📡 已推断 ${estimationResults.size} 个基站",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE65100)
                            )
                            val latest = estimationResults.last()
                            Text(
                                text = "最新: 方位角 ${latest.azimuthDeg.format(1)}°  波束宽 ${latest.beamwidthDeg.format(1)}°  RMSE ${latest.rmse.format(2)}dB",
                                fontSize = 11.sp,
                                color = Color(0xFFBF360C)
                            )
                        }
                        TextButtonCompact(
                            onClick = { viewModel.clearEstimationResult() },
                            text = "清空"
                        )
                    }
                }
            }

            // Empty state when no sessions selected
            if (selectedSessionIds.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "未选择路测日志",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "请前往「日志管理」页面勾选要显示的日志",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            // 高德地图（占满大部分空间）
            AMapCompose(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                measurements = measurements,
                bsResults = estimationResults
            )

            // 底部统计信息栏（紧凑一行）
            if (measurements.isNotEmpty()) {
                val cellColorMap = extractCellColorMap(measurements)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    elevation = CardDefaults.elevatedCardElevation(2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatItem("总点数", "${measurements.size}")
                        StatItem("基站数", "${cellColorMap.size}")
                        StatItem("覆盖距离", "${calculateCoverageDistance(measurements)}m")
                        StatItem("平均RSRP", "${measurements.map { it.rsrp }.average().toInt()} dBm")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TextButtonCompact(onClick: () -> Unit, text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(4.dp)
    )
}

private fun Double.format(digits: Int): String {
    return String.format(java.util.Locale.US, "%." + digits + "f", this)
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.Gray
        )
    }
}

/**
 * 计算测量点覆盖距离（边界框对角线，O(n)）
 */
private fun calculateCoverageDistance(measurements: List<Measurement>): Int {
    if (measurements.size < 2) return 0
    val minLat = measurements.minOf { it.latitude }
    val maxLat = measurements.maxOf { it.latitude }
    val minLng = measurements.minOf { it.longitude }
    val maxLng = measurements.maxOf { it.longitude }
    val dLat = Math.toRadians(maxLat - minLat)
    val dLng = Math.toRadians(maxLng - minLng)
    val lat1 = Math.toRadians(minLat)
    val lat2 = Math.toRadians(maxLat)
    val a = sin(dLat / 2).pow(2.0) + sin(dLng / 2).pow(2.0) * cos(lat1) * cos(lat2)
    return (2 * 6371000.0 * asin(sqrt(a))).toInt()
}
