package fumi.day.literalmusi.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fumi.day.literalmusi.BuildConfig
import fumi.day.literalmusi.data.prefs.UserPrefs
import fumi.day.literalmusi.domain.model.Song
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val userPrefs by viewModel.userPrefs.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val uploadCandidates by viewModel.uploadCandidates.collectAsState()
    val downloadCandidates by viewModel.downloadCandidates.collectAsState()
    val localFileCount by viewModel.localFileCount.collectAsState()
    val remoteFileCount by viewModel.remoteFileCount.collectAsState()
    val showOverwriteConfirm by viewModel.showOverwriteConfirm.collectAsState()
    val mediaStoreSongs by viewModel.mediaStoreSongs.collectAsState()

    var showGitDialog by remember { mutableStateOf(false) }
    var showMediaStorePicker by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }

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
                gitHubEnabled = userPrefs.gitHubEnabled,
                isUploading = uploadProgress.isUploading,
                isDownloading = downloadProgress.isDownloading,
                onConnectClick = { showGitDialog = true },
                onEditClick = { showGitDialog = true },
                onDisconnectClick = viewModel::disconnectGitHub,
                onUploadClick = {
                    viewModel.refreshUploadCandidates()
                    showUploadDialog = true
                },
                onDownloadClick = {
                    viewModel.refreshDownloadCandidates()
                    showDownloadDialog = true
                }
            )

            HorizontalDivider()

            SyncStatusCard(
                gitHubEnabled = userPrefs.gitHubEnabled,
                localFileCount = localFileCount,
                remoteFileCount = remoteFileCount
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

    if (showGitDialog) {
        GitSettingsDialog(
            initialToken = userPrefs.gitHubToken,
            initialRepo = userPrefs.gitHubRepo,
            onSave = { token, repo ->
                viewModel.saveGitConfig(token, repo)
                showGitDialog = false
            },
            onDismiss = { showGitDialog = false }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

    if (showUploadDialog) {
        UploadDialog(
            candidates = uploadCandidates,
            onUpload = { fileNames ->
                viewModel.uploadToCloud(fileNames)
                showUploadDialog = false
            },
            onDismiss = { showUploadDialog = false }
        )
    }

    if (showDownloadDialog) {
        DownloadDialog(
            candidates = downloadCandidates,
            onDownload = { fileNames ->
                viewModel.downloadFromCloud(fileNames)
                showDownloadDialog = false
            },
            onDismiss = { showDownloadDialog = false }
        )
    }

    if (uploadProgress.isUploading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Uploading to Cloud") },
            text = {
                Column {
                    Text("Uploading: ${uploadProgress.currentFile}")
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uploadProgress.total > 0) {
                        Text("${uploadProgress.completed + 1} / ${uploadProgress.total}")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (uploadProgress.completed + 1).toFloat() / uploadProgress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelUpload() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (!uploadProgress.isUploading && (uploadProgress.total > 0 || uploadProgress.errors.isNotEmpty())) {
        AlertDialog(
            onDismissRequest = { viewModel.clearUploadResult() },
            title = { Text("Upload Complete") },
            text = {
                Text(
                    if (uploadProgress.errors.isEmpty())
                        "Successfully uploaded ${uploadProgress.total} files."
                    else
                        "Uploaded ${uploadProgress.total} files with ${uploadProgress.errors.size} errors:\n${uploadProgress.errors.joinToString("\n")}"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearUploadResult() }) {
                    Text("OK")
                }
            }
        )
    }

    if (downloadProgress.isDownloading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading from Cloud") },
            text = {
                Column {
                    Text("Downloading: ${downloadProgress.currentFile}")
                    Spacer(modifier = Modifier.height(8.dp))
                    if (downloadProgress.total > 0) {
                        Text("${downloadProgress.completed + 1} / ${downloadProgress.total}")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (downloadProgress.completed + 1).toFloat() / downloadProgress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMediaStorePicker && mediaStoreSongs.isNotEmpty()) {
        MediaStorePickerDialog(
            songs = mediaStoreSongs,
            onImport = { selectedUris, alsoUpload ->
                viewModel.importFromMediaStore(selectedUris, alsoUpload)
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
    onImport: (List<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }
    var alsoUploadToCloud by remember { mutableStateOf(false) }

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
                Column {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = alsoUploadToCloud,
                                onCheckedChange = { alsoUploadToCloud = it }
                            )
                            Text("同时上传到云端", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(selected.toList(), alsoUploadToCloud) },
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
private fun CloudSyncCard(
    userPrefs: UserPrefs,
    gitHubEnabled: Boolean,
    isUploading: Boolean,
    isDownloading: Boolean,
    onConnectClick: () -> Unit,
    onEditClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUploadClick: () -> Unit,
    onDownloadClick: () -> Unit
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

            if (!gitHubEnabled) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect GitHub")
                }
            } else {
                Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = userPrefs.gitHubRepo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                        Text("Edit")
                    }
                    OutlinedButton(onClick = onDisconnectClick, modifier = Modifier.weight(1f)) {
                        Text("Disconnect")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onUploadClick,
                        enabled = !isUploading && !isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isUploading) "Uploading..." else "Upload")
                    }

                    Button(
                        onClick = onDownloadClick,
                        enabled = !isUploading && !isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isDownloading) "Downloading..." else "Download from Cloud")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    gitHubEnabled: Boolean,
    localFileCount: Int,
    remoteFileCount: Int?
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Sync Status",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Local files: $localFileCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (remoteFileCount != null) {
                Text(
                    text = "Cloud files: $remoteFileCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (gitHubEnabled) {
                Text(
                    text = "Cloud files: --",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UploadDialog(
    candidates: List<Song>,
    onUpload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload to Cloud") },
        text = {
            if (candidates.isEmpty()) {
                Text("All local songs are already uploaded to cloud.")
            } else {
                Column {
                    Text(
                        text = "${candidates.size} songs not yet uploaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.uri }.toSet()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected.size == candidates.size,
                                    onCheckedChange = {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.uri }.toSet()
                                    }
                                )
                                Text("Select All", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(candidates, key = { it.uri }) { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (song.uri in selected) selected - song.uri
                                        else selected + song.uri
                                    }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
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
                                Column {
                                    Text(song.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUpload(selected.map { File(it).name })
                },
                enabled = selected.isNotEmpty()
            ) {
                Text("Upload (${selected.size})")
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
private fun DownloadDialog(
    candidates: List<CloudFile>,
    onDownload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from Cloud") },
        text = {
            if (candidates.isEmpty()) {
                Text("No new songs available on cloud.")
            } else {
                Column {
                    Text(
                        text = "${candidates.size} songs available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.fileName }.toSet()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected.size == candidates.size,
                                    onCheckedChange = {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.fileName }.toSet()
                                    }
                                )
                                Text("Select All", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(candidates, key = { it.fileName }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (file.fileName in selected) selected - file.fileName
                                        else selected + file.fileName
                                    }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = file.fileName in selected,
                                    onCheckedChange = {
                                        selected = if (file.fileName in selected) selected - file.fileName
                                        else selected + file.fileName
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(file.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDownload(selected.toList())
                },
                enabled = selected.isNotEmpty()
            ) {
                Text("Download (${selected.size})")
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
private fun GitSettingsDialog(
    initialToken: String,
    initialRepo: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var repo by remember { mutableStateOf(initialRepo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Personal Access Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Repository (owner/repo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(token, repo) },
                enabled = token.isNotBlank() && repo.contains("/")
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
