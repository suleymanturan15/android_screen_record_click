package com.timemacro.scheduler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timemacro.scheduler.domain.model.LogEntry
import com.timemacro.scheduler.domain.repository.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogViewModel(
    private val logRepository: LogRepository,
) : ViewModel() {

    val recentLogs: StateFlow<List<LogEntry>> =
        logRepository
            .observeRecent(limit = 200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearOld(beforeEpochMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.clearOld(beforeEpochMs)
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.deleteAll()
        }
    }
}

class LogViewModelFactory(
    private val logRepository: LogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            return LogViewModel(logRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

