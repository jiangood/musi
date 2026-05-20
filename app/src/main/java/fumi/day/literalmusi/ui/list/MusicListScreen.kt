package fumi.day.literalmusi.ui.list

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
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
    val searchQuery by viewModel.searchQuery.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

    val filteredSongs = if (searchQuery.isBlank()) songs
    else songs.filter { it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchText,
                        onQueryChange = {
                            searchText = it
                            viewModel.updateSearch(it)
                        },
                        onSearch = { searchActive = false },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        placeholder = { Text("Search songs...") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) }
                    )
                },
                expanded = searchActive,
                onExpandedChange = { searchActive = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
            }

            if (filteredSongs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (songs.isEmpty()) "No music found on device" else "No results",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "${filteredSongs.size} songs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(filteredSongs, key = { _, s -> s.id }) { _, song ->
                        SongItem(
                            song = song,
                            onClick = {
                                viewModel.playSong(song)
                                onNavigateToPlayer()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SongItem(
    song: Song,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
