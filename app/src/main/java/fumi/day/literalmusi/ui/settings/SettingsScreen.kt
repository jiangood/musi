package fumi.day.literalmusi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fumi.day.literalmusi.BuildConfig
import fumi.day.literalmusi.data.git.SyncResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val userPrefs by viewModel.userPrefs.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val lastSyncError by viewModel.lastSyncError.collectAsState()
    val showOverwriteConfirm by viewModel.showOverwriteConfirm.collectAsState()
    val mediaStoreSongs by viewModel.mediaStoreSongs.collectAsState()

    var showOssDialog by remember { mutableStateOf(false) }
    var showMediaStorePicker by remember { mutableStateOf(false) }
    var showSyncStatusDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImportMusicCard(
                onImportFromMediaStore = {
                    viewModel.loadMediaStoreSongs()
                    showMediaStorePicker = true
                }
            )

            HorizontalDivider()

            CloudSyncCard(
                userPrefs = userPrefs,
                isSyncing = isSyncing,
                accentColor = MaterialTheme.colorScheme.primary,
                onConnectClick = { showOssDialog = true },
                onSyncNowClick = viewModel::syncNow,
                onEditClick = { showOssDialog = true },
                onDisconnectClick = viewModel::disconnectCloudSync
            )

            HorizontalDivider()

            SyncStatusCard(
                isSyncing = isSyncing,
                lastSyncedAt = userPrefs.lastSyncedAt,
                lastSyncError = lastSyncError,
                onClick = { showSyncStatusDialog = true }
            )

            HorizontalDivider()

            Text(
                text = "About",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Musi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "A minimal music player. All music is stored in the app's internal pile/ folder for seamless sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showOssDialog) {
        CloudSettingsDialog(
            initialAccessKey = userPrefs.ossAccessKey,
            initialSecretKey = userPrefs.ossSecretKey,
            initialBucket = userPrefs.ossBucket,
            initialRegion = userPrefs.ossRegion,
            initialDomain = userPrefs.ossDomain,
            onSave = { accessKey, secretKey, bucket, region, domain ->
                viewModel.saveOssConfig(accessKey, secretKey, bucket, region, domain)
                showOssDialog = false
            },
            onDismiss = { showOssDialog = false }
        )
    }

    if (showOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Local data found") },
            text = {
                Text("Local music data already exists. How would you like to proceed?")
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { viewModel.confirmOverwrite() }) {
                        Text("Overwrite")
                    }
                    TextButton(onClick = { viewModel.confirmMerge() }) {
                        Text("Merge")
                    }
                }
            }
        )
    }

    if (importProgress.isImporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing...") },
            text = {
                Column {
                    if (importProgress.total > 0) {
                        Text("${importProgress.completed} / ${importProgress.total} files")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { importProgress.completed.toFloat() / importProgress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    if (importProgress.errors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importProgress.errors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = { }
        )
    }

    if (!importProgress.isImporting && (importProgress.total > 0 || importProgress.errors.isNotEmpty())) {
        AlertDialog(
            onDismissRequest = { viewModel.clearImportResult() },
            title = { Text("Import Complete") },
            text = {
                Text(
                    if (importProgress.errors.isEmpty())
                        "Successfully imported ${importProgress.completed} files."
                    else
                        "Imported ${importProgress.completed} files with ${importProgress.errors.size} errors:\n${importProgress.errors.joinToString("\n")}"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportResult() }) {
                    Text("OK")
                }
            }
        )
    }

    if (isSyncing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Syncing...") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Downloading and uploading files...")
                }
            },
            confirmButton = { }
        )
    }

    syncResult?.let { result ->
        if (!isSyncing) {
            AlertDialog(
                onDismissRequest = { viewModel.clearSyncResult() },
                title = { Text(if (result.errors.isEmpty()) "Sync Complete" else "Sync Error") },
                text = {
                    if (result.errors.isEmpty()) {
                        Text("Downloaded ${result.downloaded} files\nUploaded ${result.uploaded} files")
                    } else {
                        Text(result.errors.joinToString("\n"))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSyncResult() }) {
                        Text("OK")
                    }
                }
            )
        }
    }

    if (showSyncStatusDialog) {
        SyncStatusDialog(
            isSyncing = isSyncing,
            lastSyncedAt = userPrefs.lastSyncedAt,
            lastSyncError = lastSyncError,
            syncResult = syncResult,
            onDismiss = { showSyncStatusDialog = false }
        )
    }

    if (showMediaStorePicker && mediaStoreSongs.isNotEmpty()) {
        MediaStorePickerDialog(
            songs = mediaStoreSongs,
            onImport = { selectedUris ->
                viewModel.importFromMediaStore(selectedUris)
                showMediaStorePicker = false
            },
            onDismiss = { showMediaStorePicker = false }
        )
    }
}

@Composable
private fun ImportMusicCard(
    onImportFromMediaStore: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Import Music",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Add music files to your library. Files are copied to the app's internal storage for syncing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onImportFromMediaStore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Music")
            }
        }
    }
}

@Composable
private fun MediaStorePickerDialog(
    songs: List<MediaStoreSong>,
    onImport: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }

    val folderEntries = remember(songs) {
        songs.groupBy { it.parentFolder }.entries.sortedBy { it.key }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Music to Import") },
        text = {
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier.height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No music found on device.")
                }
            } else {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    folderEntries.forEach { (folder, folderSongs) ->
                        val folderName = folder.substringAfterLast('/').ifEmpty { folder }
                        val selectedInFolder = folderSongs.count { it.uri in selected }
                        val allSelected = selectedInFolder == folderSongs.size
                        val isExpanded = folder in expandedFolders

                        item(key = "header_$folder") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedFolders = if (isExpanded) expandedFolders - folder
                                        else expandedFolders + folder
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = {
                                        selected = if (allSelected) {
                                            selected - folderSongs.map { it.uri }.toSet()
                                        } else {
                                            selected + folderSongs.map { it.uri }.toSet()
                                        }
                                    }
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                                    else Icons.Default.KeyboardArrowRight,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folderName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "$selectedInFolder / ${folderSongs.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (isExpanded) {
                            items(folderSongs, key = { it.id }) { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = if (song.uri in selected) selected - song.uri
                                            else selected + song.uri
                                        }
                                        .padding(start = 48.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = song.uri in selected,
                                        onCheckedChange = {
                                            selected = if (song.uri in selected) selected - song.uri
                                            else selected + song.uri
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${song.artist} · ${song.formattedDuration}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("Import (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SyncStatusCard(
    isSyncing: Boolean,
    lastSyncedAt: Long?,
    lastSyncError: String?,
    onClick: () -> Unit
) {
    val (statusText, statusColor) = when {
        isSyncing -> "Syncing..." to MaterialTheme.colorScheme.tertiary
        lastSyncError != null -> "Error" to MaterialTheme.colorScheme.error
        lastSyncedAt != null -> "Synced" to Color(0xFF4CAF50)
        else -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sync Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isSyncing) "Syncing in progress..."
                       else "Last synced: ${formatRelativeTime(lastSyncedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun SyncStatusDialog(
    isSyncing: Boolean,
    lastSyncedAt: Long?,
    lastSyncError: String?,
    syncResult: SyncResult?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Status: ${
                        when {
                            isSyncing -> "Syncing..."
                            lastSyncError != null -> "Error"
                            lastSyncedAt != null -> "Synced"
                            else -> "Not connected"
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Last synced: ${formatRelativeTime(lastSyncedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastSyncError != null) {
                    Text(
                        text = "Error: $lastSyncError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (syncResult != null && !isSyncing) {
                    Text(
                        text = "Uploaded: ${syncResult.uploaded} files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Downloaded: ${syncResult.downloaded} files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncResult.errors.isNotEmpty()) {
                        Text(
                            text = "Errors: ${syncResult.errors.joinToString("\n")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun formatRelativeTime(timestamp: Long?): String {
    if (timestamp == null) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}/${cal.get(java.util.Calendar.YEAR)}"
        }
    }
}

@Composable
private fun CloudSyncCard(
    userPrefs: fumi.day.literalmusi.data.prefs.UserPrefs,
    isSyncing: Boolean,
    accentColor: Color,
    onConnectClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onEditClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Cloud Sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!userPrefs.ossEnabled) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Connect")
                }
            } else {
                Text(
                    text = "Qiniu Cloud",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = userPrefs.ossBucket,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (userPrefs.ossAccessKey.isBlank() || userPrefs.ossSecretKey.isBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Credentials missing. Please re-enter your Access Key and Secret Key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        OutlinedButton(onClick = onSyncNowClick) {
                            Text("Sync Now")
                        }
                    }
                    OutlinedButton(onClick = onEditClick) {
                        Text("Edit")
                    }
                    OutlinedButton(onClick = onDisconnectClick) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudSettingsDialog(
    initialAccessKey: String,
    initialSecretKey: String,
    initialBucket: String,
    initialRegion: String,
    initialDomain: String,
    onSave: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var accessKey by remember { mutableStateOf(initialAccessKey) }
    var secretKey by remember { mutableStateOf(initialSecretKey) }
    var bucket by remember { mutableStateOf(initialBucket) }
    var region by remember { mutableStateOf(initialRegion.ifEmpty { "z0" }) }
    var domain by remember { mutableStateOf(initialDomain) }
    var showAdvanced by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = accessKey,
                    onValueChange = { accessKey = it },
                    label = { Text("Access Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text("Secret Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Bucket") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showAdvanced) "Hide Advanced Settings" else "Show Advanced Settings",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = { Text("Region") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain") },
                        singleLine = true,
                        placeholder = { Text("Auto-generated from bucket") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(accessKey, secretKey, bucket, region, domain) },
                enabled = accessKey.isNotBlank() && secretKey.isNotBlank() && bucket.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
