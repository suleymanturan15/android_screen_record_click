package com.timemacro.scheduler.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

class UserPreferences(
    private val context: Context,
) {
    private object Keys {
        val OnboardingSeen = booleanPreferencesKey("onboarding_seen")
        val StopRecordingWithVolumeKeys = booleanPreferencesKey("stop_recording_with_volume_keys")
        val ShowTapDotDuringPlayback = booleanPreferencesKey("show_tap_dot_during_playback")
        val MacroAutoIndex = intPreferencesKey("macro_auto_index")
        val PostScrollSettleMs = intPreferencesKey("post_scroll_settle_ms")
        val ScrollEventQuietWindowMs = intPreferencesKey("scroll_event_quiet_window_ms")
        val MaxScrollSettleWaitMs = intPreferencesKey("max_scroll_settle_wait_ms")
        val PlaybackSpeedPercent = intPreferencesKey("playback_speed_percent")
        val TapOffsetXDp = intPreferencesKey("tap_offset_x_dp")
        val TapOffsetYDp = intPreferencesKey("tap_offset_y_dp")
        val TapJitterDp = intPreferencesKey("tap_jitter_dp")
        val TaskLaunchTargetApp = booleanPreferencesKey("task_launch_target_app")
        val TaskLaunchDelayMs = intPreferencesKey("task_launch_delay_ms")
    }

    /**
     * Never emits null. If DataStore is slow/fails, default is false (show onboarding).
     */
    val onboardingSeen: Flow<Boolean> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.OnboardingSeen] ?: false }

    val stopRecordingWithVolumeKeys: Flow<Boolean> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.StopRecordingWithVolumeKeys] ?: true }

    val showTapDotDuringPlayback: Flow<Boolean> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.ShowTapDotDuringPlayback] ?: false }

    val postScrollSettleMs: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.PostScrollSettleMs] ?: 450 }

    val scrollEventQuietWindowMs: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.ScrollEventQuietWindowMs] ?: 180 }

    val maxScrollSettleWaitMs: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.MaxScrollSettleWaitMs] ?: 1200 }

    /**
     * Playback speed (percent). 100 = 1.0x.
     */
    val playbackSpeedPercent: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> (prefs[Keys.PlaybackSpeedPercent] ?: 100).coerceIn(25, 400) }

    /**
     * Tap calibration (dp offsets and optional jitter radius).
     */
    val tapOffsetXDp: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> (prefs[Keys.TapOffsetXDp] ?: 0).coerceIn(-50, 50) }

    val tapOffsetYDp: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> (prefs[Keys.TapOffsetYDp] ?: 0).coerceIn(-50, 50) }

    val tapJitterDp: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> (prefs[Keys.TapJitterDp] ?: 0).coerceIn(0, 30) }

    /**
     * Task preflight: launch the recorded target app before playback.
     */
    val taskLaunchTargetApp: Flow<Boolean> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[Keys.TaskLaunchTargetApp] ?: false }

    val taskLaunchDelayMs: Flow<Int> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> (prefs[Keys.TaskLaunchDelayMs] ?: 1500).coerceIn(0, 10_000) }

    suspend fun setOnboardingSeen(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OnboardingSeen] = value
        }
    }

    suspend fun setStopRecordingWithVolumeKeys(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.StopRecordingWithVolumeKeys] = value
        }
    }

    suspend fun setShowTapDotDuringPlayback(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ShowTapDotDuringPlayback] = value
        }
    }

    suspend fun setPostScrollSettleMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PostScrollSettleMs] = value.coerceIn(0, 5000)
        }
    }

    suspend fun setScrollEventQuietWindowMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ScrollEventQuietWindowMs] = value.coerceIn(0, 5000)
        }
    }

    suspend fun setMaxScrollSettleWaitMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MaxScrollSettleWaitMs] = value.coerceIn(0, 10_000)
        }
    }

    suspend fun setPlaybackSpeedPercent(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PlaybackSpeedPercent] = value.coerceIn(25, 400)
        }
    }

    suspend fun setTapOffsetXDp(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TapOffsetXDp] = value.coerceIn(-50, 50)
        }
    }

    suspend fun setTapOffsetYDp(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TapOffsetYDp] = value.coerceIn(-50, 50)
        }
    }

    suspend fun setTapJitterDp(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TapJitterDp] = value.coerceIn(0, 30)
        }
    }

    suspend fun setTaskLaunchTargetApp(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TaskLaunchTargetApp] = value
        }
    }

    suspend fun setTaskLaunchDelayMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TaskLaunchDelayMs] = value.coerceIn(0, 10_000)
        }
    }

    /**
     * Persistent auto-name counter for unnamed macros.
     *
     * Returns next index (1-based): Macro 1, Macro 2, ...
     */
    suspend fun nextMacroAutoIndex(): Int {
        var next = 1
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.MacroAutoIndex] ?: 0
            next = cur + 1
            prefs[Keys.MacroAutoIndex] = next
        }
        return next
    }
}

