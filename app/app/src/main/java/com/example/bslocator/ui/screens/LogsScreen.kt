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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.example.bslocator.data.MeasurementSession
import com.example.bslocator.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsState(initial = emptyList())
    val selectedSessionIds by viewModel.selectedSessionIds.collectAsState()
    val isExporting by viewModel.isExporting
    val isImporting by viewModel.isImporting
    val exportMessage by viewModel.exportMessage
    val exportError by viewModel.exportError
    val importMessage by viewModel.importMessage
    val importError by viewModel.importError

    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(importMessage, importError) {
        importMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissImportMessage()
        }
        importError?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.dismissImportMessage()
        }
    }

    // Export launcher
    var pendingExportAction by remember { mutableStateOf<(Uri) -> Unit>({}) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { pendingExportAction(it) }
    }

    // Import launcher (SAF open document)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importCsvFromUri(context, it) }
    }

    // Dialog states
    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }
    var showRenameDialog by remember { mutableStateOf<MeasurementSession?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志管理") },
                actions = {
                    // Import button
                    IconButton(
                        onClick = { openDocumentLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                        enabled = !isImporting
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.FileUpload, contentDescription = "导入 CSV")
                        }
                    }
                    // Bulk export button (only when multiple selected)
                    if (selectedSessionIds.size > 1) {
                        IconButton(
                            onClick = { showExportDialog = true },
                            enabled = !isExporting
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.FileDownload, contentDescription = "批量导出")
                            }
                        }
                    }
                    // Select all / clear all
                    TextButton(
                        onClick = {
                            if (selectedSessionIds.size == sessions.size && sessions.isNotEmpty()) {
                                viewModel.clearSessionSelection()
                            } else {
                                viewModel.selectAllSessions(sessions.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            if (selectedSessionIds.size == sessions.size && sessions.isNotEmpty())
                                "取消全选"
                            else
                                "全选"
                        )
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
            // Selection summary bar
            if (selectedSessionIds.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "已选择 ${selectedSessionIds.size} 条日志",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButton(onClick = { viewModel.clearSessionSelection() }) {
                            Text("清除选择")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Info text
            Text(
                text = "勾选日志可在地图中叠加显示；点击操作图标可导出、重命名或删除",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("暂无日志", color = Color.Gray)
                    Text(
                        "在采集页面开始路测后会自动生成日志",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("text/csv", "*/*")) },
                        enabled = !isImporting
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入 CSV 文件")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            isSelected = session.id in selectedSessionIds,
                            onToggleSelection = { viewModel.toggleSessionSelection(session.id) },
                            onExport = {
                                val ext = "csv"
                                val fileName = "bslocator_${session.name.replace(" ", "_")}_${System.currentTimeMillis()}.$ext"
                                pendingExportAction = { uri: Uri ->
                                    viewModel.exportSessionMeasurements(context, uri, session.id, "csv")
                                }
                                createDocumentLauncher.launch(fileName)
                            },
                            onDelete = { showDeleteDialog = session.id },
                            onRename = { showRenameDialog = session }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除日志") },
            text = { Text("确定要删除这条日志吗？关联的测量数据也会被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(sessionId)
                        showDeleteDialog = null
                    }
                ) {
                    Text("删除", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Rename dialog
    showRenameDialog?.let { session ->
        var newName by remember { mutableStateOf(session.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("重命名日志") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("日志名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.renameSession(session.id, newName)
                        }
                        showRenameDialog = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Bulk export dialog
    if (showExportDialog) {
        var format by remember { mutableStateOf("csv") }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("批量导出") },
            text = {
                Column {
                    Text("将导出 ${selectedSessionIds.size} 条日志的测量数据", fontSize = 14.sp, color = Color.Gray)
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
                Button(
                    onClick = {
                        showExportDialog = false
                        val ext = format
                        val fileName = "bslocator_batch_${selectedSessionIds.size}sessions_${System.currentTimeMillis()}.$ext"
                        pendingExportAction = { uri: Uri ->
                            viewModel.exportMultipleSessions(context, uri, selectedSessionIds.toList(), format)
                        }
                        createDocumentLauncher.launch(fileName)
                    }
                ) {
                    Text("导出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SessionItem(
    session: MeasurementSession,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val startStr = dateFormat.format(Date(session.startTime))
    val durationStr = formatDuration(session.durationMs)
    val statusText = if (session.isActive) "采集中" else "已完成"
    val statusColor = if (session.isActive) Color(0xFF4CAF50) else Color.Gray

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox for map selection
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    // Status badge
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f))
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 10.sp,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "开始: $startStr  ·  时长: $durationStr",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                if (session.notes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = session.notes,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            // Actions
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    IconButton(onClick = onExport, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "导出",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "重命名",
                            modifier = Modifier.size(18.dp),
                            tint = Color.Gray
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFE57373)
                        )
                    }
                }
            }
        }
    }
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

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        minutes > 0 -> String.format("%d:%02d", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}
