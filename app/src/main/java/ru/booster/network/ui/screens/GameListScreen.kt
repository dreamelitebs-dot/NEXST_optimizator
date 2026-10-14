package ru.booster.network.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import ru.booster.network.core.model.GameInfo
import ru.booster.network.ui.GameListViewModel
import ru.booster.network.ui.theme.NeonCyan
import ru.booster.network.ui.theme.NeonGreen
import ru.booster.network.ui.theme.NeonMagenta

/**
 * Список установленных игр + добавление пользовательских приложений.
 * Переключатель Split Tunneling (isTracked) — только отмеченные игры
 * идут через AmneziaWG-туннель, весь остальной трафик обходит VPN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    onGameSelected: (String?) -> Unit,
    onBack: () -> Unit
) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var customPkg by remember { mutableStateOf("") }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("ИГРЫ // SPLIT TUNNELING", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Назад", tint = NeonCyan) }
                },
                actions = {
                    IconButton({ viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Обновить список", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = NeonMagenta,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, "Добавить приложение") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showAddDialog) {
                Card(Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Пользовательское приложение (package name)",
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customPkg,
                            onValueChange = { customPkg = it },
                            placeholder = { Text("com.example.mygame") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text("ОК", color = NeonGreen, modifier = Modifier
                                .clickable {
                                    viewModel.addCustom(customPkg); customPkg = ""; showAddDialog = false
                                }.padding(12.dp))
                            Text("ОТМЕНА", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showAddDialog = false }.padding(12.dp))
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                items(games, key = { it.packageName }) { game ->
                    GameRow(
                        game = game,
                        onClick = { onGameSelected(game.packageName) },
                        onToggle = { viewModel.toggleTrack(game) }
                    )
                }
                if (games.isEmpty()) {
                    item {
                        Text(
                            "Игры не найдены.\nНажмите ⟳ для сканирования или + для ручного добавления.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(game: GameInfo, onClick: () -> Unit, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {

            Icon(Icons.Default.Games, null, tint = if (game.isTracked) NeonGreen else NeonCyan)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(game.appName, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(game.packageName, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (game.pingBeforeMs >= 0 && game.pingAfterMs >= 0) {
                    Text(
                        "ping ${game.pingBeforeMs} → ${game.pingAfterMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (game.pingAfterMs < game.pingBeforeMs) NeonGreen else NeonMagenta
                    )
                }
            }

            // Split-tunneling switch: пуст? only this game through the tunnel
            Switch(
                checked = game.isTracked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NeonGreen,
                    checkedThumbColor = MaterialTheme.colorScheme.background
                )
            )
        }
    }
}
