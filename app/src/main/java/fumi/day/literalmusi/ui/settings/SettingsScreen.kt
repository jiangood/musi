package fumi.day.literalmusi.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fumi.day.literalmusi.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val userPrefs by viewModel.userPrefs.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()

    var showGitDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            ) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Settings") }
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
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
            GitSyncCard(
                userPrefs = userPrefs,
                isSyncing = isSyncing,
                accentColor = MaterialTheme.colorScheme.primary,
                onConnectClick = { showGitDialog = true },
                onSyncNowClick = viewModel::syncNow,
                onEditClick = { showGitDialog = true },
                onDisconnectClick = viewModel::disconnectGitHub
            )

            Text(
                text = "Literal Musi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = "Privacy Policy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { showPrivacyPolicy = true }
            )
        }
    }

    if (showPrivacyPolicy) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicy = false },
            title = { Text("Privacy Policy") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        Text(
                            text = """We do not collect, store, or share any personal data. All app data is stored locally on your device. We have no servers and no backend.

Literal Musi includes an optional GitHub sync feature. If enabled, the app connects directly to a GitHub repository you specify and control. Your data goes only to the repository you configure — not to us.

We do not use any analytics, advertising, crash reporting, or third-party SDKs.

All data remains on your device or in the Git repository you control. Access credentials (tokens) used for Git sync are stored in Android's encrypted storage and are never transmitted to us.

Since we do not collect any data, there is nothing to retain or delete on our end. Uninstalling the app removes all locally stored data from your device.

Our apps do not collect any personal information from anyone, including children under the age of 13.

Contact: literalapps@proton.me""",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicy = false }) {
                    Text("Close")
                }
            }
        )
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

    // Sync progress dialog
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

    // Sync result dialog
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
}

@Composable
private fun GitSyncCard(
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
                text = "Git Sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!userPrefs.gitHubEnabled) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Connect")
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

                if (userPrefs.gitHubToken.isBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Token missing. Please re-enter your Personal Access Token.",
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
private fun GitSettingsDialog(
    initialToken: String,
    initialRepo: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var repo by remember { mutableStateOf(initialRepo) }
    val repoWillChange = initialRepo.isNotBlank() && repo != initialRepo

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
                if (repoWillChange) {
                    Text(
                        text = "Switching repositories will remove all local data. It will be re-synced from the new repository.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
