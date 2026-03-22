package com.timemacro.scheduler.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.viewmodel.TaskViewModel
import com.timemacro.scheduler.viewmodel.TaskViewModelFactory
import java.time.format.DateTimeFormatter

private val TaskTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun HomeTasksScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onCreateTask: () -> Unit,
    onEditTask: (taskId: String) -> Unit,
) {
    val vm: TaskViewModel =
        viewModel(
            factory =
                TaskViewModelFactory(
                    taskRepository = container.taskRepository,
                    macroRepository = container.macroRepository,
                    logRepository = container.logRepository,
                    schedulerEngine = container.schedulerEngine,
                    taskPlanner = container.taskPlanner,
                ),
        )
    val items by vm.items.collectAsState()
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (deleteTargetId != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("Delete task?") },
            text = { Text("This will delete the task and its logs.") },
            confirmButton = {
                Button(
                    onClick = {
                        val id = deleteTargetId
                        deleteTargetId = null
                        if (id != null) vm.deleteTask(id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { deleteTargetId = null }) { Text("Cancel") }
            },
        )
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
                Text("Tasks", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onCreateTask) { Text("Create Task") }
            }
        }

        items(items, key = { it.task.id }) { item ->
            val task = item.task
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(),
                onClick = { onEditTask(task.id) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val title = "${item.macroName ?: "Macro"} @ ${task.startTime.format(TaskTimeFmt)}"
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Next: ${item.nextRunLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Last: ${item.lastStatus ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Row {
                        Switch(
                            checked = task.active,
                            onCheckedChange = { vm.toggleActive(task.id) },
                        )
                        IconButton(onClick = { onEditTask(task.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit task")
                        }
                        IconButton(onClick = { deleteTargetId = task.id }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete task")
                        }
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Text(
                    text = "No tasks yet. Create one to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

