package fumi.day.literalmusi.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fumi.day.literalmusi.domain.model.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    onNavigateToPlayer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: MusicListViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsState()
    var deleteSong by remember { mutableStateOf<Song?>(null) }

    deleteSong?.let { song ->
        AlertDialog(
            onDismissRequest = { deleteSong = null },
            title = { Text("Delete Song") },
            text = { Text("Delete \"${song.title}\"? The file will be moved to trash.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteSong = null
                    viewModel.deleteSong(song)
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSong = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Musi") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No music yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Go to Settings to import music",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(modifier = Modifier.padding(paddingValues)) {
                Text(
                    text = "${songs.size} songs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, s -> s.id }) { _, song ->
                        SongItem(
                            song = song,
                            onClick = {
                                viewModel.playSong(song)
                                onNavigateToPlayer()
                            },
                            onLongClick = { deleteSong = song }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongItem(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = song.formattedDuration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
