package com.example.bslocator.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import com.example.bslocator.algorithm.BaseStationEstimator
import com.example.bslocator.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EstimateScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val cellList = viewModel.cellList
    val selectedEci by viewModel.selectedEci
    val workState by viewModel.workState
    val workError by viewModel.workError
    val workProgress by viewModel.workProgressMessage
    val results = viewModel.estimationResults
    val result = results.lastOrNull()
    val isExporting by viewModel.isExporting
    val exportMessage by viewModel.exportMessage
    val exportError by viewModel.exportError
    var expanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error snackbar
    LaunchedEffect(workError) {
        workError?.let { error ->
            snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Long)
            viewModel.dismissError()
        }
    }

    // Show export result snackbar
    LaunchedEffect(exportMessage, exportError) {
        exportMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissExportMessage()
        }
        exportError?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.dismissExportMessage()
        }
    }

    // SAF launcher for exporting (uses wildcard MIME type; actual type enforced by file extension)
    var pendingExportAction by remember { mutableStateOf<(Uri) -> Unit>({}) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { pendingExportAction(it) }
    }

    // Export dialog state
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("基站推断") },
                actions = {
                    OutlinedButton(onClick = { viewModel.refreshCellList() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // PCI Selection
            Text(
                text = "选择目标基站 (PCI)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                val selectedLabel = selectedEci?.let { eci ->
                    val cell = cellList.find { it.eci == eci }
                    if (cell != null) "ECI ${cell.eci} (PCI ${cell.pci}, EARFCN ${cell.earfcn})" else "ECI $eci"
                } ?: "请选择基站 (ECI)"
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("目标基站") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    cellList.forEach { cell ->
                        DropdownMenuItem(
                            text = { Text("ECI ${cell.eci} — PCI ${cell.pci}, EARFCN ${cell.earfcn}") },
                            onClick = {
                                viewModel.selectEci(cell.eci)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Run button
            val isRunning = workState == WorkInfo.State.RUNNING || workState == WorkInfo.State.ENQUEUED
            Button(
                onClick = {
                    selectedEci?.let { viewModel.runEstimation(it) }
                },
                enabled = selectedEci != null && !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("推断中...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始推断（后台运行）")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Batch estimation button
            val selectedSessionIds by viewModel.selectedSessionIds.collectAsState()
            val hasSelectedSessions = selectedSessionIds.isNotEmpty()
            OutlinedButton(
                onClick = {
                    viewModel.runBatchEstimation(selectedSessionIds)
                },
                enabled = hasSelectedSessions && !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (hasSelectedSessions)
                        "推断已选日志中所有基站 (${selectedSessionIds.size}条)"
                    else
                        "请先勾选日志 (日志管理页面)"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Work status indicator
            AnimatedVisibility(
                visible = workState != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                WorkStatusCard(state = workState, message = workProgress)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Results
            if (result != null) {
                ResultCard(
                    result = result!!,
                    isExporting = isExporting,
                    onExportClick = { showExportDialog = true }
                )
            } else if (selectedEci == null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "请先选择一个基站 (ECI) 并采集足够数据",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        EstimationExportDialog(
            onDismiss = { showExportDialog = false },
            onExport = { format ->
                showExportDialog = false
                val ext = if (format == "json") "json" else "csv"
                val fileName = "bslocator_estimation_eci${selectedEci}_${System.currentTimeMillis()}.$ext"
                pendingExportAction = { uri: Uri ->
                    selectedEci?.let { eci ->
                        viewModel.exportEstimationResult(context, uri, eci, format)
                    }
                }
                createDocumentLauncher.launch(fileName)
            }
        )
    }
}

@Composable
private fun EstimationExportDialog(
    onDismiss: () -> Unit,
    onExport: (format: String) -> Unit
) {
    var format by remember { mutableStateOf("json") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出推断结果") },
        text = {
            Column {
                Text("将导出推断结果 + 关联测量数据", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))

                Text("文件格式", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    ExportFormatChip(
                        selected = format == "csv",
                        onClick = { format = "csv" },
                        label = "CSV"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ExportFormatChip(
                        selected = format == "json",
                        onClick = { format = "json" },
                        label = "JSON"
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(format) }) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ExportFormatChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(label)
    }
}

@Composable
fun WorkStatusCard(state: WorkInfo.State?, message: String) {
    val (icon, color, statusText) = when (state) {
        WorkInfo.State.ENQUEUED -> Triple(
            Icons.Default.Schedule, Color(0xFF2196F3), "等待中"
        )
        WorkInfo.State.RUNNING -> Triple(
            Icons.Default.Schedule, Color(0xFFFF9800), "正在计算"
        )
        WorkInfo.State.SUCCEEDED -> Triple(
            Icons.Default.CheckCircle, Color(0xFF4CAF50), "完成"
        )
        WorkInfo.State.FAILED -> Triple(
            Icons.Default.Error, Color(0xFFF44336), "失败"
        )
        WorkInfo.State.CANCELLED -> Triple(
            Icons.Default.Error, Color.Gray, "已取消"
        )
        WorkInfo.State.BLOCKED -> Triple(
            Icons.Default.Schedule, Color.Gray, "阻塞"
        )
        null -> Triple(Icons.Default.Schedule, Color.Gray, "未知")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        elevation = CardDefaults.elevatedCardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message,
                fontSize = 13.sp,
                color = Color.DarkGray
            )

            if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = color
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "提示: 可以锁屏或切换到其他 APP，推断完成后会发送通知",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ResultCard(
    result: BaseStationEstimator.EstimationResult,
    isExporting: Boolean,
    onExportClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "推断结果",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            ResultRow(label = "基站纬度", value = "${result.bsLatitude.format(6)}\u00b0")
            ResultRow(label = "基站经度", value = "${result.bsLongitude.format(6)}\u00b0")
            ResultRow(label = "方位角", value = "${result.azimuthDeg.format(1)}\u00b0")
            ResultRow(label = "波束宽度", value = "${result.beamwidthDeg.format(1)}\u00b0")
            ResultRow(label = "下倾角", value = "${result.tiltDeg.format(1)}\u00b0")
            ResultRow(label = "基站高度", value = "${result.bsHeightM.format(1)} m")
            ResultRow(label = "路径损耗指数 n", value = result.pathLossExponent.format(2))
            ResultRow(label = "参考 RSSI", value = "${result.referenceRssi.format(1)} dBm")

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "拟合 RMSE: ${result.rmse.format(2)} dB",
                    fontSize = 13.sp,
                    color = if (result.rmse < 5) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                Text(
                    text = "迭代: ${result.iterations}",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Export button
            Button(
                onClick = onExportClick,
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                } else {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("导出推断结果 + 测量数据")
            }
        }
    }
}

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 14.sp, color = Color.Gray)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun Double.format(digits: Int): String {
    return String.format(java.util.Locale.US, "%." + digits + "f", this)
}
