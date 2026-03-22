package com.timemacro.scheduler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.domain.repository.MacroLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MacroLogViewModel(
    private val repo: MacroLogRepository,
) : ViewModel() {
    val logs: StateFlow<List<MacroLog>> =
        repo.observeRecent(limit = 200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearAll()
        }
    }
}

class MacroLogViewModelFactory(
    private val repo: MacroLogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MacroLogViewModel::class.java)) {
            return MacroLogViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

