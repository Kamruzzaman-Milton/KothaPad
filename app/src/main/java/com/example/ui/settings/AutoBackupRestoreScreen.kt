package com.example.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.AutoBackupManager
import com.example.data.backup.BackupFileInfo
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceVariant
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupRestoreScreen(
    onBackClick: () -> Unit,
    onRestoreBackupFile: (BackupFileInfo, (Result<com.example.data.backup.BackupSummary>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<BackupFileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRestoring by remember { mutableStateOf(false) }

    var backupToRestore by remember { mutableStateOf<BackupFileInfo?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupFileInfo?>(null) }

    fun refreshBackups() {
        isLoading = true
        scope.launch {
            val list = AutoBackupManager.listBackups(context)
            backups = list
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshBackups()
    }

    BackHandler {
        onBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Backups & Restore",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${backups.size} archive${if (backups.size == 1) "" else "s"} found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Settings"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshBackups() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh list"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (backups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Backup,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Backups Found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Backups will appear here as scheduled or after using 'Back up now' in Automatic Backup settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AmoledSurfaceVariant,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Tap a backup to restore notes, templates & settings. A pre-restore safety backup is saved automatically before restoring.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    items(backups, key = { it.name }) { backup ->
                        BackupFileCard(
                            backup = backup,
                            onRestoreClick = { backupToRestore = backup },
                            onDeleteClick = { backupToDelete = backup }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            // Restore Confirmation Dialog
            backupToRestore?.let { backup ->
                AlertDialog(
                    onDismissRequest = { if (!isRestoring) backupToRestore = null },
                    icon = {
                        Icon(
                            imageVector = if (backup.isSafetyBackup) Icons.Default.Security else Icons.Outlined.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = {
                        Text(
                            text = if (backup.isSafetyBackup) "Restore Safety Backup?" else "Restore Backup?",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "File: ${backup.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Size: ${formatBytes(backup.sizeBytes)} • Date: ${formatBackupDate(backup.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Restoring replaces all current notes, templates, and settings. A fresh safety backup will be created immediately before restoring so nothing is lost.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                isRestoring = true
                                onRestoreBackupFile(backup) { result ->
                                    isRestoring = false
                                    backupToRestore = null
                                    result.onSuccess { summary ->
                                        Toast.makeText(
                                            context,
                                            "Restored ${summary.notesCount} notes and ${summary.templatesCount} templates successfully",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        refreshBackups()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "Restore failed: ${err.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Restore Now")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { backupToRestore = null },
                            enabled = !isRestoring
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Delete Confirmation Dialog
            backupToDelete?.let { backup ->
                AlertDialog(
                    onDismissRequest = { backupToDelete = null },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    title = { Text("Delete Backup?", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("Are you sure you want to delete '${backup.name}'? This action cannot be undone.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val item = backup
                                backupToDelete = null
                                scope.launch {
                                    val deleted = AutoBackupManager.deleteBackup(context, item)
                                    if (deleted) {
                                        Toast.makeText(context, "Backup deleted", Toast.LENGTH_SHORT).show()
                                        refreshBackups()
                                    } else {
                                        Toast.makeText(context, "Could not delete backup", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { backupToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Restoring In-Progress Overlay
            if (isRestoring) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Creating safety backup & restoring...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Please keep the app open",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupFileCard(
    backup: BackupFileInfo,
    onRestoreClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val borderColor = if (backup.isSafetyBackup) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        AmoledBorder
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRestoreClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (backup.isSafetyBackup) Icons.Default.Security else Icons.Outlined.Backup,
                contentDescription = null,
                tint = if (backup.isSafetyBackup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (backup.isSafetyBackup) "Pre-Restore Safety Backup" else formatBackupDate(backup.timestamp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatBytes(backup.sizeBytes),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = backup.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Backup",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatBackupDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Unknown date"
    val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        "%.1f MB".format(Locale.US, mb)
    } else {
        "%.1f KB".format(Locale.US, kb)
    }
}
