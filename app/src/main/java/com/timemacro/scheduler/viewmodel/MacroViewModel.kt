package com.timemacro.scheduler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timemacro.scheduler.domain.model.Macro
import com.timemacro.scheduler.domain.repository.MacroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class MacroViewModel(
    private val macroRepository: MacroRepository,
) : ViewModel() {

    val macros: StateFlow<List<Macro>> =
        macroRepository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createPlaceholderMacro(
        name: String = "Placeholder Macro",
        recordDurationMs: Long = 0L,
        actionsJson: String = """{"actions":[]}""",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val macro = Macro(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = Instant.now(),
                recordDurationMs = recordDurationMs,
                actionsJson = actionsJson,
            )
            macroRepository.upsert(macro)
        }
    }

    fun deleteMacro(macroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            macroRepository.delete(macroId)
        }
    }

    fun setMacroEnabled(macroId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            macroRepository.setEnabled(macroId, enabled)
        }
    }
}

class MacroViewModelFactory(
    private val macroRepository: MacroRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MacroViewModel::class.java)) {
            return MacroViewModel(macroRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

