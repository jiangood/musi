# Sync Status Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent sync status card to the Settings page showing sync state at a glance, with a click-to-view-details dialog.

**Architecture:** Two-file change. `SettingsViewModel` exposes `lastSyncError` and `pendingOpsCount` as new StateFlows. `SettingsScreen.kt` adds a `SyncStatusCard` composable and a detail dialog, placed below `GitSyncCard`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, DataStore, StateFlow

---

### Task 1: ViewModel — Add sync status state

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add `lastSyncError` and `pendingOpsCount` state flows**

After `val isSyncing: StateFlow<Boolean> = syncManager.isSyncing` (line 72), add:

```kotlin
val lastSyncError: StateFlow<String?> = syncManager.syncError

private val _pendingOpsCount = MutableStateFlow(0)
val pendingOpsCount: StateFlow<Int> = _pendingOpsCount.asStateFlow()
```

- [ ] **Step 2: Initialize `_pendingOpsCount` at the end of the `init` block**

Since this ViewModel doesn't have an `init` block, add one:

```kotlin
init {
    _pendingOpsCount.value = opLog.pendingCount()
}
```

Place this after the `mediaStoreSongs` declaration (after line 86).

- [ ] **Step 3: Update `_pendingOpsCount` after import and after sync**

In `importFromMediaStore`, after the import loop completes and `_importProgress.value` is set to the final state (line 163), update:

```kotlin
_pendingOpsCount.value = _pendingOpsCount.value + completed
```

In `syncNow`, after `syncAndAwait()` returns and `_syncResult.value` is set (line 249), reset:

```kotlin
_pendingOpsCount.value = 0
```

---

### Task 2: SettingsScreen — Add SyncStatusCard composable

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Collect new state in `SettingsScreen`**

After `val importProgress by viewModel.importProgress.collectAsState()` (line 68), add:

```kotlin
val lastSyncError by viewModel.lastSyncError.collectAsState()
val pendingOpsCount by viewModel.pendingOpsCount.collectAsState()
```

- [ ] **Step 2: Add `SyncStatusCard` and detail dialog state**

After `var showMediaStorePicker` (line 73), add:

```kotlin
var showSyncStatusDialog by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Add `SyncStatusCard` call in the main Column**

After the `GitSyncCard` block (after line 112's `HorizontalDivider()`), add:

```kotlin
HorizontalDivider()

SyncStatusCard(
    isSyncing = isSyncing,
    lastSyncedAt = userPrefs.lastSyncedAt,
    lastSyncError = lastSyncError,
    pendingOpsCount = pendingOpsCount,
    onClick = { showSyncStatusDialog = true }
)
```

- [ ] **Step 4: Add the detail dialog before `if (showMediaStorePicker)`**

After the `if (showOverwriteConfirm)` block (before line 258), add:

```kotlin
if (showSyncStatusDialog) {
    SyncStatusDialog(
        isSyncing = isSyncing,
        lastSyncedAt = userPrefs.lastSyncedAt,
        lastSyncError = lastSyncError,
        pendingOpsCount = pendingOpsCount,
        syncResult = syncResult,
        onDismiss = { showSyncStatusDialog = false }
    )
}
```

- [ ] **Step 5: Implement `SyncStatusCard` composable**

Add this composable after the `GitSyncCard` function (after line 508):

```kotlin
@Composable
private fun SyncStatusCard(
    isSyncing: Boolean,
    lastSyncedAt: Long?,
    lastSyncError: String?,
    pendingOpsCount: Int,
    onClick: () -> Unit
) {
    val (statusText, statusColor) = when {
        isSyncing -> "Syncing..." to MaterialTheme.colorScheme.tertiary
        lastSyncError != null -> "Error" to MaterialTheme.colorScheme.error
        pendingOpsCount > 0 -> "Pending" to Color(0xFFFFA000)
        lastSyncedAt != null -> "Synced" to Color(0xFF4CAF50)
        else -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sync Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isSyncing) "Syncing in progress..."
                           else "Last synced: ${formatRelativeTime(lastSyncedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pendingOpsCount > 0 && !isSyncing) {
                    Text(
                        text = "Pending operations: $pendingOpsCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Circle,
                        contentDescription = statusText,
                        modifier = Modifier.size(12.dp),
                        tint = statusColor
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
        Text(
            text = "Tap for details",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 12.dp, bottom = 8.dp)
        )
    }
}
```

Note: `Icons.Default.Circle` is not in `Icons.Default`. We need a different approach for the status dot. Let me think...

Actually, Compose doesn't have a simple filled circle icon. I'll use a `Canvas` or `Box` with a small colored circle:

```kotlin
Box(
    modifier = Modifier
        .size(12.dp)
        .clip(CircleShape)
        .background(statusColor)
)
```

Update the above code to use this `Box` instead of the `Icon`.

- [ ] **Step 6: Add `formatRelativeTime` helper**

Add this function at the bottom of the file (or as a private function):

```kotlin
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
```

- [ ] **Step 7: Implement `SyncStatusDialog` composable**

```kotlin
@Composable
private fun SyncStatusDialog(
    isSyncing: Boolean,
    lastSyncedAt: Long?,
    lastSyncError: String?,
    pendingOpsCount: Int,
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
                            pendingOpsCount > 0 -> "Pending"
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
                if (pendingOpsCount > 0) {
                    Text(
                        text = "Pending operations: $pendingOpsCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastSyncError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
```

- [ ] **Step 8: Add missing imports**

Add at the top of `SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
```

Also add the `SyncResult` import if not present:

```kotlin
import fumi.day.literalmusi.data.git.SyncResult
```

(Check if this is already imported — if `syncResult` is already used, it's already there.)
