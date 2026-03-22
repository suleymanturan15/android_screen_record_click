package com.timemacro.scheduler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timemacro.scheduler.domain.model.Task
import com.timemacro.scheduler.domain.repository.LogRepository
import com.timemacro.scheduler.domain.repository.MacroRepository
import com.timemacro.scheduler.domain.repository.TaskRepository
import com.timemacro.scheduler.domain.scheduler.SchedulerEngine
import com.timemacro.scheduler.domain.scheduler.TaskPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TaskViewModel(
    private val taskRepository: TaskRepository,
    private val macroRepository: MacroRepository,
    private val logRepository: LogRepository,
    private val schedulerEngine: SchedulerEngine,
    private val taskPlanner: TaskPlanner,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TaskUiItem>>(emptyList())
    val items: StateFlow<List<TaskUiItem>> = _items.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _items.value =
                withContext(Dispatchers.IO) {
                    val tasks = taskRepository.listAll()
                    val now = System.currentTimeMillis()
                    tasks.map { task ->
                        val macro = macroRepository.getById(task.macroId)
                        val lastLog = logRepository.lastForTask(task.id)
                        val lastRun = task.lastRunAt?.toEpochMilli() ?: lastLog?.startTime?.toEpochMilli()
                        val next = taskPlanner.computeNextRun(
                            nowEpochMs = now,
                            task = task,
                            lastScheduledOrLastRunEpochMs = lastRun,
                            macroDurationMs = macro?.recordDurationMs,
                        )
                        TaskUiItem(
                            task = task,
                            macroName = macro?.name,
                            nextRunAtEpochMs = next.nextRunAtEpochMs,
                            nextRunReason = next.reason,
                            lastStatus = lastLog?.status,
                        )
                    }
                }
        }
    }

    fun toggleActive(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = taskRepository.getById(taskId) ?: return@launch
            val updated = task.copy(active = !task.active)
            taskRepository.upsert(updated)
            if (updated.active) schedulerEngine.schedule(updated) else schedulerEngine.cancel(updated.id)
            refresh()
        }
    }

    fun upsert(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.upsert(task)
            if (task.active) schedulerEngine.schedule(task)
            refresh()
        }
    }

    suspend fun loadTask(taskId: String): Task? {
        return withContext(Dispatchers.IO) { taskRepository.getById(taskId) }
    }

    fun updateTask(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            // Re-apply schedule deterministically after update.
            schedulerEngine.cancel(task.id)
            val updated = task.copy(nextScheduledAt = null)
            taskRepository.update(updated)
            if (updated.active) schedulerEngine.schedule(updated)
            refresh()
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            schedulerEngine.cancel(taskId)
            logRepository.deleteAllForTask(taskId)
            taskRepository.delete(taskId)
            refresh()
        }
    }
}

data class TaskUiItem(
    val task: Task,
    val macroName: String?,
    val nextRunAtEpochMs: Long?,
    val nextRunReason: String?,
    val lastStatus: String?,
) {
    fun nextRunLabel(): String {
        val next = nextRunAtEpochMs ?: return nextRunReason ?: "—"
        val dt = java.time.Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
        return dt.format(DateTimeFormatter.ofPattern("HH:mm dd MMM"))
    }
}

class TaskViewModelFactory(
    private val taskRepository: TaskRepository,
    private val macroRepository: MacroRepository,
    private val logRepository: LogRepository,
    private val schedulerEngine: SchedulerEngine,
    private val taskPlanner: TaskPlanner,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
            return TaskViewModel(
                taskRepository = taskRepository,
                macroRepository = macroRepository,
                logRepository = logRepository,
                schedulerEngine = schedulerEngine,
                taskPlanner = taskPlanner,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

