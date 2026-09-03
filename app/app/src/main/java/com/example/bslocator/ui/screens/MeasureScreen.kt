package com.example.bslocator.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.example.bslocator.data.Measurement
import com.example.bslocator.ui.theme.SignalBad
import com.example.bslocator.ui.theme.SignalExcellent
import com.example.bslocator.ui.theme.SignalFair
import com.example.bslocator.ui.theme.SignalGood
import com.example.bslocator.ui.theme.SignalPoor
import com.example.bslocator.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isCollecting by viewModel.isCollecting
    val currentSessionName by viewModel.currentSessionName
    val latest by viewModel.latestMeasurement
    val totalCount by viewModel.totalCount
    val measurements by viewModel.measurements.collectAsState(initial = emptyList())
    val isExporting by viewModel.isExporting
    val exportMessage by viewModel.exportMessage
    val exportError by viewModel.exportError
    val collectionStatus by viewModel.collectionStatus
    val cellList = viewModel.cellList

    val snackbarHostState = remember { SnackbarHostState() }

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

    // SAF launcher for exporting
    var pendingExportAction: (Uri) -> Unit by remember { mutableStateOf({}) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { pendingExportAction(it) }
    }

    // Export dialog state
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.bindService(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindService(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("基站路测采集") },
                actions = {
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = !isExporting && totalCount > 0
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.FileDownload, contentDescription = "导出数据")
                        }
                    }
                    IconButton(onClick = { viewModel.clearAllData() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空数据")
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
        ) {
            // Control Panel
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "采集状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isCollecting) Icons.Default.Notifications else Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (isCollecting) SignalExcellent else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCollecting) "采集中..." else "已停止",
                            color = if (isCollecting) SignalExcellent else Color.Gray
                        )
                    }

                    // Show current session name when collecting
                    if (isCollecting && currentSessionName.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentSessionName,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "已采集: $totalCount 条",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (collectionStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = collectionStatus,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (isCollecting) {
                                viewModel.stopCollection(context)
                            } else {
                                viewModel.startCollection(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCollecting) SignalBad else SignalExcellent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isCollecting) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isCollecting) "停止采集" else "开始采集")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Latest Measurement Card
            if (latest != null) {
                LatestMeasurementCard(measurement = latest!!)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recent Measurements List
            Text(
                text = "最近记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(measurements.take(20)) { measurement ->
                    MeasurementItem(measurement)
                }
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        ExportDialog(
            cellList = cellList,
            totalCount = totalCount,
            onDismiss = { showExportDialog = false },
            onExport = { pci, format ->
                showExportDialog = false
                val ext = if (format == "json") "json" else "csv"
                val fileName = if (pci == null) {
                    "bslocator_all_${System.currentTimeMillis()}.$ext"
                } else {
                    "bslocator_pci${pci}_${System.currentTimeMillis()}.$ext"
                }

                pendingExportAction = { uri: Uri ->
                    if (pci == null) {
                        viewModel.exportAllMeasurements(context, uri, format)
                    } else {
                        viewModel.exportMeasurementsForPci(context, uri, pci, format)
                    }
                }
                createDocumentLauncher.launch(fileName)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDialog(
    cellList: List<com.example.bslocator.data.CellSummary>,
    totalCount: Int,
    onDismiss: () -> Unit,
    onExport: (pci: Int?, format: String) -> Unit
) {
    var selectedPci by remember { mutableStateOf<Int?>(null) }
    var format by remember { mutableStateOf("csv") }
    var pciExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出数据") },
        text = {
            Column {
                Text("选择导出范围与格式", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))

                // PCI selection
                ExposedDropdownMenuBox(
                    expanded = pciExpanded,
                    onExpandedChange = { pciExpanded = !pciExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedPci?.let { "PCI $it" } ?: "全部数据 ($totalCount 条)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("导出范围") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pciExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = pciExpanded,
                        onDismissRequest = { pciExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部数据 ($totalCount 条)") },
                            onClick = {
                                selectedPci = null
                                pciExpanded = false
                            }
                        )
                        cellList.forEach { cell ->
                            DropdownMenuItem(
                                text = { Text("PCI ${cell.pci}") },
                                onClick = {
                                    selectedPci = cell.pci
                                    pciExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Format selection
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
            Button(onClick = { onExport(selectedPci, format) }) {
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
fun LatestMeasurementCard(measurement: Measurement) {
    val signalColor = when {
        measurement.rsrp >= -80 -> SignalExcellent
        measurement.rsrp >= -90 -> SignalGood
        measurement.rsrp >= -100 -> SignalFair
        measurement.rsrp >= -110 -> SignalPoor
        else -> SignalBad
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = signalColor.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最新测量",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    tint = signalColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    val eciStr = if (measurement.eci >= 0) "ECI:${measurement.eci} " else ""
                    Text("${eciStr}PCI: ${measurement.pci}", fontSize = 14.sp)
                    Text("RSRP: ${measurement.rsrp} dBm", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("RSRQ: ${measurement.rsrq} dB", fontSize = 14.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lat: ${measurement.latitude.format(5)}", fontSize = 12.sp)
                    Text("Lng: ${measurement.longitude.format(5)}", fontSize = 12.sp)
                    Text("精度: ±${measurement.gpsAccuracy}m", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun MeasurementItem(measurement: Measurement) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(measurement.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                val eciLabel = if (measurement.eci >= 0) "ECI${measurement.eci}/PCI" else "PCI"
                Text(
                    text = "$eciLabel ${measurement.pci}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "RSRP ${measurement.rsrp} dBm",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeStr,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${measurement.latitude.format(4)}, ${measurement.longitude.format(4)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun Double.format(digits: Int): String {
    return String.format(java.util.Locale.US, "%." + digits + "f", this)
}
