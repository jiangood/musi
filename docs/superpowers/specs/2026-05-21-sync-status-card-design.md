# Sync Status Card — Design Spec

## Goal

Add a persistent sync status indicator in the Settings screen that shows the current state of GitHub sync at a glance, and allows viewing details/logs via a dialog.

## Design

### 1. New Composable: `SyncStatusCard`

A new independent card placed after `GitSyncCard` (below the `HorizontalDivider()` in `SettingsScreen.kt`).

**Layout:**
```
┌─────────────────────────────────────┐
│ Sync Status           ● Synced      │
│                                     │
│ Last synced: 5 minutes ago          │
│ Pending operations: 2               │
│                                     │
│           [View Details]            │
└─────────────────────────────────────┘
```

States and indicators:
| State | Indicator |
|-------|-----------|
| `isSyncing == true` | Blue spinning progress, "Syncing..." text |
| `syncError != null` | Red dot, "Error" text |
| `pendingOpsCount > 0` | Yellow/amber dot, "Pending" text |
| Default (idle, no errors, no pending ops) | Green dot, "Synced" text |

The entire card is clickable, opening the detail dialog.

### 2. Detail Dialog

An `AlertDialog` (matching existing code style) showing:
- Sync status (text description)
- Last synced time (formatted as relative time like "5 minutes ago" or "Never")
- Last sync result (uploaded/downloaded/errors, if available)
- Pending operations list (if any)
- Close button

### 3. ViewModel Changes (`SettingsViewModel`)

New state exposed:
- `lastSyncError: StateFlow<String?>` — delegates to `GitSyncManager.syncError`
- `pendingOpsCount: StateFlow<Int>` — tracks number of pending operations in OpLog

`pendingOpsCount` is incremented when `OpLog.append()` is called (during import), and reset to 0 after a successful sync.

### 4. Data Layer

No changes needed. All required data is already available:
- `UserPrefs.lastSyncedAt` — for last sync time
- `GitSyncManager.isSyncing` — sync in progress flag
- `GitSyncManager.syncError` — last error message
- `OpLog.pendingCount()` — pending operations

### 5. Files Changed

| File | Change |
|------|--------|
| `ui/settings/SettingsScreen.kt` | Add `SyncStatusCard` composable + detail dialog, call it from main column |
| `ui/settings/SettingsViewModel.kt` | Add `lastSyncError` and `pendingOpsCount` state flows |

No new files, no new dependencies.

## Non-Goals

- No persistent sync history log
- No new data layer entities
- No i18n (project uses hardcoded English strings)
