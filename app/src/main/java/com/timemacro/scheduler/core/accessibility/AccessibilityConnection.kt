package com.timemacro.scheduler.core.accessibility

import com.timemacro.scheduler.service.accessibility.MacroAccessibilityService
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real runtime connection state for our AccessibilityService.
 *
 * "Enabled" in system settings is not enough; the service process may not be alive.
 */
object AccessibilityConnection {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastConnectedAt = MutableStateFlow<Long?>(null)
    val lastConnectedAt: StateFlow<Long?> = _lastConnectedAt.asStateFlow()

    /**
     * Heartbeat: updated by the AccessibilityService while it's alive.
     * Uses uptime millis so it's monotonic and comparable to AccessibilityEvent.eventTime.
     */
    private val _lastSeenUptimeMs = MutableStateFlow<Long?>(null)
    val lastSeenUptimeMs: StateFlow<Long?> = _lastSeenUptimeMs.asStateFlow()

    val serviceRef: AtomicReference<MacroAccessibilityService?> = AtomicReference(null)

    fun setConnected(service: MacroAccessibilityService?) {
        serviceRef.set(service)
        _isConnected.value = service != null
        if (service != null) {
            _lastConnectedAt.value = System.currentTimeMillis()
            // Initial heartbeat so UI can immediately show CONNECTED_AND_ACTIVE.
            _lastSeenUptimeMs.value = android.os.SystemClock.uptimeMillis()
        }
    }

    fun beat(uptimeMs: Long = android.os.SystemClock.uptimeMillis()) {
        _lastSeenUptimeMs.value = uptimeMs
    }

    // B4 fix: heartbeat tolerance bumped from 2_000 → 8_000.
    // After Doze/screen-off, the AccessibilityService's main-handler heartbeat can fall behind
    // even though the service is still bound and ready. The 2 sn budget caused "PERMISSION_ERROR
    // Accessibility runtime disconnected" log entries on every scheduled task.
    fun isRuntimeConnectedNow(maxHeartbeatAgeMs: Long = 8_000L): Boolean {
        if (!_isConnected.value) return false
        if (serviceRef.get() == null) return false
        val last = _lastSeenUptimeMs.value ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        return (now - last) in 0..maxHeartbeatAgeMs
    }
}

