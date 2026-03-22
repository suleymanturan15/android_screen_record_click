package com.timemacro.scheduler.core.accessibility

import android.content.Context
import android.database.ContentObserver
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.timemacro.scheduler.core.device.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * UI/runtime status for Accessibility.
 * Settings "enabled" is separate and must never flip the UI to OFF if it's true.
 */
enum class AccessibilityRuntimeUiState {
    CONNECTED_AND_ACTIVE,
    CONNECTING,
    DISCONNECTED_BY_SYSTEM,
}

data class AccessibilitySnapshot(
    val enabledInSettings: Boolean,
    val runtimeConnected: Boolean,
    val runtimeUiState: AccessibilityRuntimeUiState,
    val lastConnectedAtWallMs: Long?,
    val lastHeartbeatUptimeMs: Long?,
    val manufacturer: String,
    val isXiaomiFamily: Boolean,
    val isMiui: Boolean,
)

/**
 * Single source of truth:
 * - enabledInSettings: computed from Settings.Secure (stable)
 * - runtimeConnected: based on service presence + heartbeat freshness
 * - runtimeUiState: CONNECTING (<=5s) then DISCONNECTED_BY_SYSTEM
 */
class AccessibilityStateRepository(
    private val appContext: Context,
    private val appScope: CoroutineScope,
) {
    private val context = appContext.applicationContext

    private val _enabledInSettings = MutableStateFlow(false)
    val enabledInSettings: StateFlow<Boolean> = _enabledInSettings.asStateFlow()

    private val _runtimeConnected = MutableStateFlow(false)
    val runtimeConnected: StateFlow<Boolean> = _runtimeConnected.asStateFlow()

    private val _runtimeUiState = MutableStateFlow(AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM)
    val runtimeUiState: StateFlow<AccessibilityRuntimeUiState> = _runtimeUiState.asStateFlow()

    private val _snapshot =
        MutableStateFlow(
            AccessibilitySnapshot(
                enabledInSettings = false,
                runtimeConnected = false,
                runtimeUiState = AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM,
                lastConnectedAtWallMs = null,
                lastHeartbeatUptimeMs = null,
                manufacturer = DeviceInfo.manufacturer(),
                isXiaomiFamily = DeviceInfo.isXiaomiFamily(),
                isMiui = DeviceInfo.isMiui(),
            ),
        )
    val snapshot: StateFlow<AccessibilitySnapshot> = _snapshot.asStateFlow()

    private var reconnectJob: Job? = null

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshEnabledInSettings()
            }
        }

    init {
        // Initial snapshot + observer registration for changes.
        refreshEnabledInSettings()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            observer,
        )

        // Combine stable enabled flag + live runtime signals.
        appScope.launch(Dispatchers.Default) {
            combine(
                _enabledInSettings,
                AccessibilityConnection.isConnected,
                AccessibilityConnection.lastConnectedAt,
                AccessibilityConnection.lastSeenUptimeMs,
            ) { enabled, connected, lastAt, lastSeenUptimeMs ->
                val nowUptime = SystemClock.uptimeMillis()
                val seenRecently = (lastSeenUptimeMs?.let { nowUptime - it } ?: Long.MAX_VALUE) <= 2_000L
                val runtime = enabled && connected && seenRecently && AccessibilityConnection.serviceRef.get() != null

                _runtimeConnected.value = runtime

                val ui =
                    when {
                        !enabled -> AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM
                        runtime -> AccessibilityRuntimeUiState.CONNECTED_AND_ACTIVE
                        _runtimeUiState.value == AccessibilityRuntimeUiState.CONNECTING -> AccessibilityRuntimeUiState.CONNECTING
                        else -> AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM
                    }
                AccessibilitySnapshot(
                    enabledInSettings = enabled,
                    runtimeConnected = runtime,
                    runtimeUiState = ui,
                    lastConnectedAtWallMs = lastAt,
                    lastHeartbeatUptimeMs = lastSeenUptimeMs,
                    manufacturer = DeviceInfo.manufacturer(),
                    isXiaomiFamily = DeviceInfo.isXiaomiFamily(),
                    isMiui = DeviceInfo.isMiui(),
                )
            }.collect { snap ->
                _snapshot.value = snap
                // If we become connected, clear any reconnect loop state.
                if (snap.runtimeConnected) {
                    _runtimeUiState.value = AccessibilityRuntimeUiState.CONNECTED_AND_ACTIVE
                    reconnectJob?.cancel()
                    reconnectJob = null
                } else if (!snap.enabledInSettings) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _runtimeUiState.value = AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM
                }
            }
        }
    }

    fun refreshEnabledInSettings() {
        val enabled = runCatching { isAccessibilityEnabledForThisService(context) }.getOrDefault(false)
        _enabledInSettings.value = enabled
        Log.d("TimeMacro/Accessibility", "enabledInSettings=$enabled")
    }

    /**
     * Call on app foreground / resume.
     */
    fun onAppForegrounded() {
        refreshEnabledInSettings()
        // MIUI-safe reconnect strategy: if enabled in settings but runtime disconnected, show CONNECTING and poll.
        if (_enabledInSettings.value && !_runtimeConnected.value) {
            startReconnectWindow()
        }
    }

    private fun startReconnectWindow() {
        reconnectJob?.cancel()
        _runtimeUiState.value = AccessibilityRuntimeUiState.CONNECTING
        reconnectJob =
            appScope.launch(Dispatchers.Default) {
                val start = SystemClock.uptimeMillis()
                val maxMs = 5_000L
                val pollMs = 300L
                while (SystemClock.uptimeMillis() - start < maxMs) {
                    if (!_enabledInSettings.value) {
                        _runtimeUiState.value = AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM
                        return@launch
                    }
                    if (_runtimeConnected.value) {
                        _runtimeUiState.value = AccessibilityRuntimeUiState.CONNECTED_AND_ACTIVE
                        return@launch
                    }
                    delay(pollMs)
                }
                // Still not connected after grace window.
                if (_enabledInSettings.value && !_runtimeConnected.value) {
                    _runtimeUiState.value = AccessibilityRuntimeUiState.DISCONNECTED_BY_SYSTEM
                }
            }
    }
}

