package com.timemacro.scheduler.ui.screens.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.viewmodel.MacroLogViewModel
import com.timemacro.scheduler.viewmodel.MacroLogViewModelFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val vm: MacroLogViewModel = viewModel(factory = MacroLogViewModelFactory(container.macroLogRepository))
    val logs by vm.logs.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all logs?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        vm.clearAll()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                Button(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    val fmt = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Logs", style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = {
                        confirmClear = true
                    },
                ) {
                    Text("Clear Logs")
                }
            }
        }

        items(logs, key = { it.id }) { log ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${fmt.format(log.timestamp)} • ${log.source}", style = MaterialTheme.typography.bodySmall)
                    Text("${log.status} • ${log.macroNameSnapshot}", style = MaterialTheme.typography.bodyMedium)
                    if (!log.message.isNullOrBlank()) {
                        Text(log.message, style = MaterialTheme.typography.bodySmall)
                    }
                    if (log.durationMs != null || log.steps != null) {
                        Text(
                            text = "duration=${log.durationMs ?: 0}ms • steps=${log.steps ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item { Text("No logs yet.") }
        }
    }
}

