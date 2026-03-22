package com.timemacro.scheduler.core.macro

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred

data class PlaybackRequest(
    val runId: String,
    val macroId: String,
    val taskId: String?,
    val scheduledTimeEpochMs: Long?,
    val trigger: String,
    val started: CompletableDeferred<Unit>,
    val result: CompletableDeferred<PlaybackResult>,
)

data class CancelRequest(
    val runId: String,
    val reason: String,
)

object MacroPlaybackBus {
    val requests: Channel<PlaybackRequest> = Channel(Channel.BUFFERED)
    val cancelRequests: Channel<CancelRequest> = Channel(Channel.BUFFERED)

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    fun setAccessibilityConnected(value: Boolean) {
        _accessibilityConnected.value = value
    }

    fun requestPlayback(request: PlaybackRequest): Boolean {
        return requests.trySendBlocking(request).isSuccess
    }

    fun requestCancel(runId: String, reason: String = "Cancelled by user"): Boolean {
        return cancelRequests.trySend(CancelRequest(runId = runId, reason = reason)).isSuccess
    }
}

enum class PlaybackStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class PlaybackResult(
    val runId: String,
    val status: PlaybackStatus,
    val executedActions: Int,
    val totalActions: Int,
    val errorMessage: String? = null,
)

