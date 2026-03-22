package com.timemacro.scheduler.core.macro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UI + ScreenRecorderService + AccessibilityService tarafından paylaşılan kayıt oturumu.
 *
 * - ScreenRecorderService: videoPath + stop event'lerini buraya yazar
 * - AccessibilityService: action capture'ı buraya yazar
 * - UI: state'i gözler, stop sonrası Macro JSON'u üretip DB'ye kaydeder
 */
object MacroSessionManager {

    private const val WAIT_THRESHOLD_MS = 150L

    sealed class RecordingState {
        data object Idle : RecordingState()

        data class Starting(
            val startedAtMs: Long,
        ) : RecordingState()

        data class Recording(
            val startedAtMs: Long,
            val videoPath: String?,
            val actionsCount: Int,
        ) : RecordingState()

        data class Stopped(
            val startedAtMs: Long,
            val stoppedAtMs: Long,
            val videoPath: String?,
            val actionsCount: Int,
        ) : RecordingState() {
            val durationMs: Long get() = (stoppedAtMs - startedAtMs).coerceAtLeast(0L)
        }
    }

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastEventDebug = MutableStateFlow<String?>(null)
    val lastEventDebug: StateFlow<String?> = _lastEventDebug.asStateFlow()

    private val actions = CopyOnWriteArrayList<MacroAction>()
    private var lastEventAtMs: Long = 0L
    @Volatile private var pendingName: String? = null
    @Volatile private var lastStopTrigger: String? = null
    @Volatile private var recordScreenW: Int? = null
    @Volatile private var recordScreenH: Int? = null
    @Volatile private var recordRotation: Int? = null
    @Volatile private var recordDensityDpi: Int? = null
    @Volatile private var recordStartElapsedRealtimeMs: Long? = null

    fun recordStartElapsedRealtime(): Long? = recordStartElapsedRealtimeMs

    fun isRecording(): Boolean = _state.value is RecordingState.Recording

    /**
     * We allow capturing actions during both:
     * - Starting (arming): user may tap quickly before MediaProjection fully starts
     * - Recording
     */
    fun isArmingOrRecording(): Boolean =
        when (_state.value) {
            is RecordingState.Starting,
            is RecordingState.Recording,
            -> true
            else -> false
        }

    fun setPendingName(name: String?) {
        pendingName = name?.trim()?.ifBlank { null }
    }

    fun consumePendingName(): String? {
        val n = pendingName
        pendingName = null
        return n
    }

    fun lastStopTrigger(): String? = lastStopTrigger

    fun updateLastEventDebug(text: String?) {
        _lastEventDebug.value = text
    }

    fun startSession(nowMs: Long = System.currentTimeMillis()) {
        actions.clear()
        // IMPORTANT: AccessibilityEvent.eventTime is in uptime millis.
        // Keep timing in the SAME time base, otherwise waits will be wrong (and long macros will replay too fast).
        lastEventAtMs = android.os.SystemClock.uptimeMillis()
        lastStopTrigger = null
        // Best-effort: arming start. For MediaProjection sessions, this will be overridden at onVideoStarted().
        recordStartElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        // Recording display metadata is set by UI right before starting session (if available).
        _state.value = RecordingState.Starting(startedAtMs = nowMs)
    }

    /**
     * Accessibility-only recording (no MediaProjection required).
     *
     * This immediately transitions into Recording state with videoPath=null so UI can show STOP
     * and AccessibilityService can capture taps/scrolls reliably.
     */
    fun startTapOnlySession(nowMs: Long = System.currentTimeMillis()) {
        startSession(nowMs)
        onVideoStarted(videoPath = null, startedAtMs = nowMs)
    }

    fun setRecordingDisplayInfo(
        w: Int?,
        h: Int?,
        rotation: Int?,
        densityDpi: Int?,
    ) {
        recordScreenW = w
        recordScreenH = h
        recordRotation = rotation
        recordDensityDpi = densityDpi
    }

    fun onVideoStarted(videoPath: String?, startedAtMs: Long = System.currentTimeMillis()) {
        // Align timing base with AccessibilityEvent.eventTime (uptime).
        lastEventAtMs = android.os.SystemClock.uptimeMillis()
        // Actual recording start moment (monotonic time, safe against wall-clock changes).
        recordStartElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        _state.value = RecordingState.Recording(
            startedAtMs = startedAtMs,
            videoPath = videoPath,
            actionsCount = actions.size,
        )
    }

    fun onStopRequested(trigger: String) {
        lastStopTrigger = trigger
    }

    fun onStopped(stoppedAtMs: Long = System.currentTimeMillis()) {
        val current = _state.value
        val startedAtMs = when (current) {
            is RecordingState.Starting -> current.startedAtMs
            is RecordingState.Recording -> current.startedAtMs
            is RecordingState.Stopped -> current.startedAtMs
            RecordingState.Idle -> stoppedAtMs
        }
        val videoPath = when (current) {
            is RecordingState.Recording -> current.videoPath
            is RecordingState.Stopped -> current.videoPath
            else -> null
        }
        _state.value = RecordingState.Stopped(
            startedAtMs = startedAtMs,
            stoppedAtMs = stoppedAtMs,
            videoPath = videoPath,
            actionsCount = actions.size,
        )
    }

    fun reset() {
        actions.clear()
        lastEventAtMs = 0L
        pendingName = null
        lastStopTrigger = null
        recordScreenW = null
        recordScreenH = null
        recordRotation = null
        recordDensityDpi = null
        recordStartElapsedRealtimeMs = null
        _lastEventDebug.value = null
        _state.value = RecordingState.Idle
    }

    fun recordTap(
        x: Int,
        y: Int,
        eventAtMs: Long,
        target: MacroAction.TapTarget? = null,
        selector: MacroAction.TargetSelector? = null,
        preWaitMs: Long = 0L,
        nxOverride: Float? = null,
        nyOverride: Float? = null,
    ) {
        if (!isArmingOrRecording()) return
        maybeInsertWait(eventAtMs)
        val pre = preWaitMs.coerceAtLeast(0L)
        if (pre > 0L) {
            actions += MacroAction.Wait(durationMs = pre, tMs = eventAtMs)
        }
        val w = recordScreenW
        val h = recordScreenH
        val nx =
            when {
                w != null && w > 0 -> (x.toFloat() / w.toFloat())
                nxOverride != null -> nxOverride
                else -> null
            }
        val ny =
            when {
                h != null && h > 0 -> (y.toFloat() / h.toFloat())
                nyOverride != null -> nyOverride
                else -> null
            }
        actions += MacroAction.Tap(x = x, y = y, nx = nx, ny = ny, target = target, selector = selector, tMs = eventAtMs)
        updateCounts(eventAtMs)
    }

    fun recordSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, eventAtMs: Long) {
        if (!isArmingOrRecording()) return
        maybeInsertWait(eventAtMs)
        actions += MacroAction.Swipe(x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = durationMs, tMs = eventAtMs)
        updateCounts(eventAtMs)
    }

    fun recordScroll(
        direction: ScrollDirection,
        amount: Int,
        eventAtMs: Long,
        selector: MacroAction.TargetSelector? = null,
        startX: Int? = null,
        startY: Int? = null,
        endX: Int? = null,
        endY: Int? = null,
        durationMs: Long? = null,
    ) {
        if (!isArmingOrRecording()) return
        maybeInsertWait(eventAtMs)
        val w = recordScreenW
        val h = recordScreenH
        val startNx = if (w != null && w > 0 && startX != null) (startX.toFloat() / w.toFloat()) else null
        val startNy = if (h != null && h > 0 && startY != null) (startY.toFloat() / h.toFloat()) else null
        val endNx = if (w != null && w > 0 && endX != null) (endX.toFloat() / w.toFloat()) else null
        val endNy = if (h != null && h > 0 && endY != null) (endY.toFloat() / h.toFloat()) else null
        actions +=
            MacroAction.Scroll(
                direction = direction,
                amount = amount,
                selector = selector,
                startNx = startNx,
                startNy = startNy,
                endNx = endNx,
                endNy = endNy,
                durationMs = durationMs,
                tMs = eventAtMs,
            )
        updateCounts(eventAtMs)
    }

    fun actionsSnapshot(): List<MacroAction> = actions.toList()

    fun buildActionsJson(): String {
        val snapshot = actionsSnapshot()
        val dominantPkg =
            snapshot.asSequence()
                .mapNotNull { a ->
                    when (a) {
                        is MacroAction.Tap -> a.selector?.pkg ?: a.target?.packageName
                        is MacroAction.Scroll -> a.selector?.pkg
                        else -> null
                    }?.takeIf { it.isNotBlank() }
                }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

        val payload = MacroPayload(
            videoPath = when (val s = _state.value) {
                is RecordingState.Recording -> s.videoPath
                is RecordingState.Stopped -> s.videoPath
                else -> null
            },
            recordScreenW = recordScreenW,
            recordScreenH = recordScreenH,
            recordRotation = recordRotation,
            recordDensityDpi = recordDensityDpi,
            targetPackageName = dominantPkg,
            actions = snapshot,
        )
        return MacroJsonCodec.json.encodeToString(payload)
    }

    private fun maybeInsertWait(eventAtMs: Long) {
        val delta = (eventAtMs - lastEventAtMs).coerceAtLeast(0L)
        if (delta > WAIT_THRESHOLD_MS) {
            actions += MacroAction.Wait(durationMs = delta, tMs = eventAtMs)
        }
    }

    private fun updateCounts(eventAtMs: Long) {
        lastEventAtMs = eventAtMs
        val current = _state.value
        if (current is RecordingState.Recording) {
            _state.value = current.copy(actionsCount = actions.size)
        }
    }
}

