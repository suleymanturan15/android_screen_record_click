package com.timemacro.scheduler.domain.macro

import com.timemacro.scheduler.domain.model.Macro

/**
 * Macro kayıt sözleşmesi.
 *
 * Not: Bu aşamada implementasyon yok; yalnızca sınırlar tanımlanır.
 */
interface MacroRecorder {
    suspend fun startRecording(macroName: String): String /* recordingSessionId */
    suspend fun stopRecording(recordingSessionId: String): Macro
    suspend fun cancelRecording(recordingSessionId: String)
}

