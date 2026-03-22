package com.timemacro.scheduler.core.macro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MacroPlaybackState(
    val runId: String? = null,
    val taskId: String?,
    val macroId: String?,
    val totalActions: Int,
    val currentIndex: Int,
    val startedAtEpochMs: Long?,
    val status: Status,
    val errorMessage: String? = null,
) {
    enum class Status {
        IDLE,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED,
    }
}

object MacroPlaybackStateHolder {
    private val _state =
        MutableStateFlow(
            MacroPlaybackState(
                runId = null,
                taskId = null,
                macroId = null,
                totalActions = 0,
                currentIndex = 0,
                startedAtEpochMs = null,
                status = MacroPlaybackState.Status.IDLE,
            ),
        )
    val state: StateFlow<MacroPlaybackState> = _state.asStateFlow()

    fun start(runId: String?, taskId: String?, macroId: String?, totalActions: Int) {
        _state.value =
            MacroPlaybackState(
                runId = runId,
                taskId = taskId,
                macroId = macroId,
                totalActions = totalActions,
                currentIndex = 0,
                startedAtEpochMs = System.currentTimeMillis(),
                status = MacroPlaybackState.Status.RUNNING,
            )
    }

    fun progress(currentIndex: Int) {
        val s = _state.value
        if (s.status != MacroPlaybackState.Status.RUNNING) return
        _state.value = s.copy(currentIndex = currentIndex.coerceAtLeast(0))
    }

    fun success() {
        val s = _state.value
        _state.value = s.copy(status = MacroPlaybackState.Status.SUCCESS, errorMessage = null)
    }

    fun cancelled() {
        val s = _state.value
        _state.value = s.copy(status = MacroPlaybackState.Status.CANCELLED, errorMessage = "Cancelled by user")
    }

    fun failed(error: String?) {
        val s = _state.value
        _state.value = s.copy(status = MacroPlaybackState.Status.FAILED, errorMessage = error)
    }

    fun reset() {
        _state.value =
            MacroPlaybackState(
                runId = null,
                taskId = null,
                macroId = null,
                totalActions = 0,
                currentIndex = 0,
                startedAtEpochMs = null,
                status = MacroPlaybackState.Status.IDLE,
            )
    }
}

