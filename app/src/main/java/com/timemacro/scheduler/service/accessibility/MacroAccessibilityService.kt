package com.timemacro.scheduler.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.timemacro.scheduler.App
import com.timemacro.scheduler.core.accessibility.AccessibilityConnection
import com.timemacro.scheduler.core.macro.MacroAction
import com.timemacro.scheduler.core.macro.MacroJsonCodec
import com.timemacro.scheduler.core.macro.MacroPayload
import com.timemacro.scheduler.core.macro.MacroPlaybackBus
import com.timemacro.scheduler.core.macro.PlaybackResult
import com.timemacro.scheduler.core.macro.PlaybackStatus
import com.timemacro.scheduler.core.macro.MacroPlaybackStateHolder
import com.timemacro.scheduler.core.macro.MacroSessionManager
import com.timemacro.scheduler.core.macro.ScrollDirection
import com.timemacro.scheduler.core.recording.RecordingController
import com.timemacro.scheduler.service.overlay.OverlayStopWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import android.util.Log

/**
 * Macro playback'ın sistem entrypoint'i.
 *
 * Bu servis:
 * - gesture/tap/swipe injection (playback)
 * - (opsiyonel) kullanıcı aksiyonlarını gözlemleme
 * görevlerini taşır.
 *
 * PART 3:
 * - Recording sırasında: AccessibilityEvent'lerden TAP/SCROLL (+WAIT) aksiyonlarını yakalar.
 * - Playback sırasında: dispatchGesture ile TAP/SWIPE/SCROLL/WAİT uygular.
 */
class MacroAccessibilityService : AccessibilityService() {
    private companion object {
        const val TAG_ACC = "TimeMacro/Accessibility"
        const val TAG_RECORD = "TimeMacro/Recording"
        const val TAG_PLAYBACK = "TimeMacro/Playback"
    }

    @Volatile
    private var volumeKeyStopEnabled: Boolean = true

    @Volatile
    private var showTapDotDuringPlayback: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tapDotView: View? = null
    private var playbackOverlayWidget: OverlayStopWidget? = null
    private var playbackOverlayStartElapsedMs: Long? = null
    private var lastHeartbeatLogAtUptimeMs: Long = 0L
    private val heartbeatRunnable =
        object : Runnable {
            override fun run() {
                // Heartbeat: proves the service instance is alive even if no events are coming in.
                val now = android.os.SystemClock.uptimeMillis()
                AccessibilityConnection.beat(now)
                if (now - lastHeartbeatLogAtUptimeMs >= 2_000L) {
                    lastHeartbeatLogAtUptimeMs = now
                    Log.v(TAG_ACC, "heartbeat uptimeMs=$now")
                }
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    @Volatile private var lastScrollEventAtMs: Long = 0L
    @Volatile private var lastCapturedScrollAtMs: Long = 0L
    @Volatile private var eventCounter: Long = 0L

    @Volatile private var lastTapAtMs: Long = 0L
    @Volatile private var lastTapX: Int = -1
    @Volatile private var lastTapY: Int = -1

    @Volatile private var postScrollSettleMs: Int = 450
    @Volatile private var scrollEventQuietWindowMs: Int = 180
    @Volatile private var maxScrollSettleWaitMs: Int = 1200
    @Volatile private var playbackSpeedPercent: Int = 100
    @Volatile private var tapOffsetXDp: Int = 0
    @Volatile private var tapOffsetYDp: Int = 0
    @Volatile private var tapJitterDp: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_ACC, "onCreate")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            AccessibilityConnection.beat(event.eventTime)

            // Always keep last TYPE_VIEW_SCROLLED timestamp (used by playback settle logic).
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                lastScrollEventAtMs = event.eventTime
            }

            // Light breadcrumbs without spamming logcat.
            val c = ++eventCounter
            if (c % 50L == 0L) {
                Log.v(TAG_ACC, "events=$c lastType=${event.eventType}")
            }

            if (!MacroSessionManager.isArmingOrRecording()) return

            // Update in-app debug label.
            MacroSessionManager.updateLastEventDebug("type=${event.eventType} pkg=${event.packageName}")

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                -> captureTap(event)

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> captureScroll(event)

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                -> {
                    // Not recorded as action; useful to see app activity for diagnostics.
                }
            }
        } catch (t: Throwable) {
            // Never crash the AccessibilityService process due to a bad event/device-specific bug.
            Log.e(TAG_ACC, "onAccessibilityEvent crash prevented", t)
        }
    }

    override fun onInterrupt() {
        try {
            Log.w(TAG_ACC, "disconnect reason=onInterrupt")
            playbackJob?.cancel()
        } catch (t: Throwable) {
            Log.e(TAG_ACC, "onInterrupt crash prevented", t)
        } finally {
            // Treat as disconnected per requested spec.
            AccessibilityConnection.setConnected(null)
            MacroPlaybackBus.setAccessibilityConnected(false)
            // If this happens while recording, stop safely (do NOT auto-save).
            if (MacroSessionManager.isRecording()) {
                RecordingController.stopRecording(applicationContext, trigger = "ACCESSIBILITY_DISCONNECTED")
            }
        }
    }

    /**
     * IMPORTANT: Do not run long playback loops on main thread.
     * Main-thread work is reserved for dispatchGesture and UI overlay updates.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null
    @Volatile private var currentRunId: String? = null

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()

            MacroPlaybackBus.setAccessibilityConnected(true)
            AccessibilityConnection.setConnected(this)
            mainHandler.removeCallbacks(heartbeatRunnable)
            mainHandler.post(heartbeatRunnable)
            Log.i(TAG_ACC, "onServiceConnected CONNECTED")

            // Request key event filtering for volume keys.
            serviceInfo =
                serviceInfo.apply {
                    flags =
                        flags or
                            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                }
            Log.d(TAG_ACC, "serviceInfo.flags=${serviceInfo.flags}")

            // Consume playback requests from UI.
            scope.launch {
                MacroPlaybackBus.requests.receiveAsFlow().collect { req ->
                    // Supersede any previous run; it MUST still complete its deferred result (see below).
                    playbackJob?.cancel(CancellationException("Superseded by new run"))
                    currentRunId = req.runId
                    // Strict handshake: confirm the service received the request.
                    req.started.complete(Unit)
                    playbackJob =
                        launch {
                            var result: PlaybackResult? = null
                            val startedAt = SystemClock.uptimeMillis()
                            try {
                                if (req.trigger.equals("MANUAL_TEST", ignoreCase = true) || req.trigger.equals("TEST_NOW", ignoreCase = true)) {
                                    showPlaybackStopOverlay(req.runId)
                                } else {
                                    hidePlaybackStopOverlay()
                                }
                                val executed = executePlayback(req.macroId, req.taskId, req.runId)
                                MacroPlaybackStateHolder.success()
                                result =
                                    PlaybackResult(
                                        runId = req.runId,
                                        status = PlaybackStatus.SUCCESS,
                                        executedActions = executed,
                                        totalActions = MacroPlaybackStateHolder.state.value.totalActions,
                                        errorMessage = null,
                                    )
                                Log.d(TAG_PLAYBACK, "END runId=${req.runId} status=SUCCESS elapsedMs=${SystemClock.uptimeMillis() - startedAt}")
                            } catch (t: Throwable) {
                                result =
                                    when {
                                        t is CancellationException -> {
                                            MacroPlaybackStateHolder.cancelled()
                                            PlaybackResult(
                                                runId = req.runId,
                                                status = PlaybackStatus.CANCELLED,
                                                executedActions = MacroPlaybackStateHolder.state.value.currentIndex,
                                                totalActions = MacroPlaybackStateHolder.state.value.totalActions,
                                                errorMessage = t.message ?: "Cancelled",
                                            )
                                        }
                                        t is TimeoutCancellationException -> {
                                            MacroPlaybackStateHolder.failed("Playback exceeded max duration (watchdog) and was stopped")
                                            PlaybackResult(
                                                runId = req.runId,
                                                status = PlaybackStatus.FAILED,
                                                executedActions = MacroPlaybackStateHolder.state.value.currentIndex,
                                                totalActions = MacroPlaybackStateHolder.state.value.totalActions,
                                                errorMessage = "Playback exceeded max duration (watchdog) and was stopped",
                                            )
                                        }
                                        else -> {
                                            MacroPlaybackStateHolder.failed(t.message)
                                            PlaybackResult(
                                                runId = req.runId,
                                                status = PlaybackStatus.FAILED,
                                                executedActions = MacroPlaybackStateHolder.state.value.currentIndex,
                                                totalActions = MacroPlaybackStateHolder.state.value.totalActions,
                                                errorMessage = t.message ?: t::class.java.simpleName,
                                            )
                                        }
                                    }
                                val r = result!!
                                Log.d(
                                    TAG_PLAYBACK,
                                    "END runId=${req.runId} status=${r.status} elapsedMs=${SystemClock.uptimeMillis() - startedAt} err=${r.errorMessage}",
                                )
                            } finally {
                                hidePlaybackStopOverlay()
                                // CRITICAL: complete deferred result even if this coroutine was cancelled.
                                withContext(NonCancellable) {
                                    if (!req.result.isCompleted) {
                                        req.result.complete(
                                            result
                                                ?: PlaybackResult(
                                                    runId = req.runId,
                                                    status = PlaybackStatus.FAILED,
                                                    executedActions = 0,
                                                    totalActions = MacroPlaybackStateHolder.state.value.totalActions,
                                                    errorMessage = "Playback did not produce a result",
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                }
            }

            // Consume cancel requests.
            scope.launch {
                MacroPlaybackBus.cancelRequests.consumeEach { cancel ->
                    if (cancel.runId != currentRunId) return@consumeEach
                    playbackJob?.cancel(CancellationException(cancel.reason))
                }
            }

            // Keep a hot boolean for key handling.
            val prefs = (application as App).container.userPreferences
            scope.launch {
                prefs.stopRecordingWithVolumeKeys.collect { enabled ->
                    volumeKeyStopEnabled = enabled
                }
            }
            scope.launch {
                prefs.showTapDotDuringPlayback.collect { enabled ->
                    showTapDotDuringPlayback = enabled
                }
            }
            scope.launch {
                prefs.postScrollSettleMs.collect { ms ->
                    postScrollSettleMs = ms
                }
            }
            scope.launch {
                prefs.scrollEventQuietWindowMs.collect { ms ->
                    scrollEventQuietWindowMs = ms
                }
            }
            scope.launch {
                prefs.maxScrollSettleWaitMs.collect { ms ->
                    maxScrollSettleWaitMs = ms
                }
            }
            scope.launch {
                prefs.playbackSpeedPercent.collect { pct ->
                    playbackSpeedPercent = pct.coerceIn(25, 400)
                }
            }
            scope.launch {
                prefs.tapOffsetXDp.collect { v -> tapOffsetXDp = v.coerceIn(-50, 50) }
            }
            scope.launch {
                prefs.tapOffsetYDp.collect { v -> tapOffsetYDp = v.coerceIn(-50, 50) }
            }
            scope.launch {
                prefs.tapJitterDp.collect { v -> tapJitterDp = v.coerceIn(0, 30) }
            }
            Log.d(TAG_ACC, "onServiceConnected end")
        } catch (t: Throwable) {
            Log.e(TAG_ACC, "onServiceConnected crash prevented", t)
            // Mark disconnected so UI can show "enabled but not connected".
            AccessibilityConnection.setConnected(null)
            MacroPlaybackBus.setAccessibilityConnected(false)
        }
    }

    override fun onDestroy() {
        try {
            Log.w(TAG_ACC, "disconnect reason=onDestroy")
            super.onDestroy()
        } catch (t: Throwable) {
            Log.e(TAG_ACC, "onDestroy crash prevented", t)
        } finally {
            mainHandler.removeCallbacks(heartbeatRunnable)
            hidePlaybackStopOverlay()
            MacroPlaybackBus.setAccessibilityConnected(false)
            AccessibilityConnection.setConnected(null)
            scope.cancel()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG_ACC, "disconnect reason=onUnbind")
        MacroPlaybackBus.setAccessibilityConnected(false)
        AccessibilityConnection.setConnected(null)
        if (MacroSessionManager.isRecording()) {
            RecordingController.stopRecording(applicationContext, trigger = "ACCESSIBILITY_DISCONNECTED")
        }
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return try {
            if (!volumeKeyStopEnabled) return super.onKeyEvent(event)
            if (!MacroSessionManager.isRecording()) return super.onKeyEvent(event)
            if (event.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                -> {
                    RecordingController.stopRecording(applicationContext, trigger = "VOLUME_KEY")
                    true
                }

                else -> super.onKeyEvent(event)
            }
        } catch (t: Throwable) {
            Log.e(TAG_ACC, "onKeyEvent crash prevented", t)
            // Let OS handle key event if we had an issue.
            false
        }
    }

    private fun captureTap(event: AccessibilityEvent) {
        try {
            val source = event.source
            if (source == null) {
                Log.d(TAG_RECORD, "captureTap: source=null type=${event.eventType} pkg=${event.packageName}")
                return
            }
            val rect = Rect()
            source.getBoundsInScreen(rect)
            val viewId = runCatching { source.viewIdResourceName }.getOrNull()
            val textFull = runCatching { source.text?.toString() }.getOrNull()
            val textSnippet = textFull?.take(30)
            val className = runCatching { source.className?.toString() }.getOrNull()
            val isClickable = runCatching { source.isClickable }.getOrNull()
            runCatching { source.recycle() }

            if (rect.isEmpty) {
                Log.d(TAG_RECORD, "captureTap: rect empty viewId=$viewId type=${event.eventType} pkg=${event.packageName}")
                return
            }
            val x = rect.centerX()
            val y = rect.centerY()

            // Always compute normalized coords from *real* display size at capture time
            // (fallback if recordScreenW/H wasn't set).
            val (dw, dh, _) = getRealDisplayInfo()
            val nxOverride = if (dw > 0) (x.toFloat() / dw.toFloat()) else null
            val nyOverride = if (dh > 0) (y.toFloat() / dh.toFloat()) else null

            // Debounce duplicate tap-like events (some apps fire both FOCUSED + CLICKED).
            val now = event.eventTime
            if (now - lastTapAtMs in 0..80L && abs(x - lastTapX) <= 12 && abs(y - lastTapY) <= 12) {
                Log.d(TAG_RECORD, "debounce tap x=$x y=$y dt=${now - lastTapAtMs}ms type=${event.eventType}")
                return
            }
            lastTapAtMs = now
            lastTapX = x
            lastTapY = y

            // If a tap happens soon after a scroll, insert an implicit settle wait before tap.
            val sinceScroll = now - lastCapturedScrollAtMs
            val preWaitMs =
                if (sinceScroll in 0..600L) postScrollSettleMs.toLong().coerceIn(0L, 650L) else 0L
            if (preWaitMs > 0L) {
                Log.d(TAG_RECORD, "scroll->tap settle wait=${preWaitMs}ms sinceScroll=${sinceScroll}ms")
            }

            Log.d(TAG_RECORD, "captureTap x=$x y=$y rect=$rect viewId=$viewId")
            MacroSessionManager.recordTap(
                x = x,
                y = y,
                eventAtMs = event.eventTime,
                target =
                    MacroAction.TapTarget(
                        viewIdResourceName = viewId,
                        text = textSnippet,
                        className = className,
                        packageName = event.packageName?.toString(),
                        sourceEventType = event.eventType,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                    ),
                selector =
                    MacroAction.TargetSelector(
                        pkg = event.packageName?.toString(),
                        viewId = viewId,
                        text = textSnippet,
                        cls = className,
                        isClickable = isClickable,
                        isScrollable = null,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                    ),
                preWaitMs = preWaitMs,
                nxOverride = nxOverride,
                nyOverride = nyOverride,
            )
        } catch (t: Throwable) {
            Log.e(TAG_RECORD, "captureTap crash prevented", t)
        }
    }

    private fun captureScroll(event: AccessibilityEvent) {
        try {
            lastCapturedScrollAtMs = event.eventTime
            val src = event.source
            val selector =
                if (src != null) {
                    val r = Rect()
                    runCatching { src.getBoundsInScreen(r) }
                    val viewId = runCatching { src.viewIdResourceName }.getOrNull()
                    val text = runCatching { src.text?.toString() }.getOrNull()?.take(30)
                    val cls = runCatching { src.className?.toString() }.getOrNull()
                    val clickable = runCatching { src.isClickable }.getOrNull()
                    val scrollable = runCatching { src.isScrollable }.getOrNull()
                    runCatching { src.recycle() }
                    MacroAction.TargetSelector(
                        pkg = event.packageName?.toString(),
                        viewId = viewId,
                        text = text,
                        cls = cls,
                        isClickable = clickable,
                        isScrollable = scrollable,
                        left = r.left.takeIf { !r.isEmpty },
                        top = r.top.takeIf { !r.isEmpty },
                        right = r.right.takeIf { !r.isEmpty },
                        bottom = r.bottom.takeIf { !r.isEmpty },
                    )
                } else {
                    null
                }
            val (dir, amount) =
                if (Build.VERSION.SDK_INT >= 28) {
                    val dx = event.scrollDeltaX
                    val dy = event.scrollDeltaY
                    when {
                        kotlin.math.abs(dy) >= kotlin.math.abs(dx) && dy != 0 -> {
                            val direction = if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
                            direction to kotlin.math.abs(dy)
                        }

                        dx != 0 -> {
                            val direction = if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
                            direction to kotlin.math.abs(dx)
                        }

                        else -> return
                    }
                } else {
                    // Pre-28: scroll delta yok; MVP için direction tahmini yapmıyoruz.
                    return
                }

            // Best-effort: record a swipe geometry representing this scroll.
            // NOTE: For DOWN (content down), we swipe up. For RIGHT, we swipe left (etc).
            val (w, h, _) = getRealDisplayInfo()
            val midX = w / 2
            val midY = h / 2

            val baseStepPx = 750 // used only to shape the recorded gesture length
            val dist = amount.coerceIn(200, baseStepPx)
            val (sx, sy, ex, ey) =
                when (dir) {
                    ScrollDirection.DOWN -> {
                        val startY = (h * 0.70f).toInt()
                        val endY = (startY - dist).coerceAtLeast((h * 0.15f).toInt())
                        Quad(midX, startY, midX, endY)
                    }
                    ScrollDirection.UP -> {
                        val startY = (h * 0.30f).toInt()
                        val endY = (startY + dist).coerceAtMost((h * 0.85f).toInt())
                        Quad(midX, startY, midX, endY)
                    }
                    ScrollDirection.LEFT -> {
                        val startX = (w * 0.30f).toInt()
                        val endX = (startX + dist).coerceAtMost((w * 0.85f).toInt())
                        Quad(startX, midY, endX, midY)
                    }
                    ScrollDirection.RIGHT -> {
                        val startX = (w * 0.70f).toInt()
                        val endX = (startX - dist).coerceAtLeast((w * 0.15f).toInt())
                        Quad(startX, midY, endX, midY)
                    }
                }

            MacroSessionManager.recordScroll(
                direction = dir,
                amount = amount,
                eventAtMs = event.eventTime,
                selector = selector,
                startX = sx,
                startY = sy,
                endX = ex,
                endY = ey,
                durationMs = 350L,
            )
        } catch (t: Throwable) {
            Log.e(TAG_RECORD, "captureScroll crash prevented", t)
        }
    }

    private suspend fun executePlayback(macroId: String, taskId: String?, runId: String): Int {
        try {
            val container = (application as App).container
            val macro = container.macroRepository.getById(macroId) ?: error("Macro not found")

            val payload = MacroJsonCodec.json.decodeFromString<MacroPayload>(macro.actionsJson)
            val actions = payload.actions
            Log.d(TAG_PLAYBACK, "decoded actions=${actions.size} jsonLen=${macro.actionsJson.length}")
            if (actions.isEmpty()) error("Macro has no actions")

            val speed = (playbackSpeedPercent.coerceAtLeast(1)).toDouble() / 100.0

            // Prefer real timeline scheduling if we have timestamps.
            fun tMsOf(a: MacroAction): Long? =
                when (a) {
                    is MacroAction.Wait -> a.tMs
                    is MacroAction.Tap -> a.tMs
                    is MacroAction.Swipe -> a.tMs
                    is MacroAction.Scroll -> a.tMs
                }
            val baseT = actions.firstNotNullOfOrNull { tMsOf(it) }
            val timelineCount = actions.count { tMsOf(it) != null }
            val useTimeline = baseT != null && timelineCount >= (actions.size / 2)
            val lastT = actions.asReversed().firstNotNullOfOrNull { tMsOf(it) }

            // Watchdog timeout (prevents infinite runs/hangs).
            val recordedDurationMs = macro.recordDurationMs.coerceAtLeast(0L)
            val timelineDurationMs = if (baseT != null && lastT != null) (lastT - baseT).coerceAtLeast(0L) else 0L
            val predictedMs = maxOf(recordedDurationMs, timelineDurationMs)
            val multiplier = 1.35
            val minCapMs = 15_000L
            // Long macros (e.g. 4 minutes) must be allowed for scheduled tasks.
            val maxCapMs = 20 * 60 * 1000L
            val maxAllowedMs =
                (((predictedMs.toDouble() / speed) * multiplier).toLong())
                    .coerceIn(minCapMs, maxCapMs)
            Log.d(
                TAG_PLAYBACK,
                "START runId=$runId macroId=$macroId actions=${actions.size} recordedDurationMs=$recordedDurationMs timeline=$useTimeline speedPct=$playbackSpeedPercent maxAllowedMs=$maxAllowedMs",
            )

            val (curW, curH, curRotation) = getRealDisplayInfo()
            val recW = payload.recordScreenW
            val recH = payload.recordScreenH
            val recRot = payload.recordRotation
            Log.d(TAG_PLAYBACK, "display cur=${curW}x${curH} rot=$curRotation rec=${recW}x${recH} rot=$recRot")

            // Guard: rotation mismatch is unsafe (taps will be wrong).
            if (recRot != null && recRot != curRotation) {
                val msg = "Rotation changed. Please rotate back to recorded orientation."
                showToast(msg)
                error(msg)
            }
            // Guard: aspect ratio mismatch is a strong signal of wrong coordinate space.
            if (recW != null && recH != null && recW > 0 && recH > 0) {
                val recAspect = recW.toFloat() / recH.toFloat()
                val curAspect = curW.toFloat() / curH.toFloat()
                if (abs(recAspect - curAspect) > 0.03f) {
                    val msg = "Screen size/aspect changed. Please use same orientation/size as recording."
                    showToast(msg)
                    error(msg)
                }
            }

            MacroPlaybackStateHolder.start(runId = runId, taskId = taskId, macroId = macroId, totalActions = actions.size)
            var executed = 0
            var postScrollCreditMs = 0L
            var stepCount = 0
            val playbackStartUptime = SystemClock.uptimeMillis()
            withTimeout(maxAllowedMs) {
                actions.forEachIndexed { idx, action ->
                    stepCount++
                    if (stepCount > actions.size + 5) {
                        error("Playback step watchdog exceeded: steps=$stepCount actions=${actions.size}")
                    }

                    MacroPlaybackStateHolder.progress(idx + 1)

                    // Timeline scheduling (preferred): align each action to recorded eventTime deltas.
                    if (useTimeline) {
                        val t = tMsOf(action)
                        if (t != null) {
                            val desiredElapsed = ((t - baseT!!).toDouble() / speed).toLong().coerceAtLeast(0L)
                            val nowElapsed = (SystemClock.uptimeMillis() - playbackStartUptime).coerceAtLeast(0L)
                            val toWait = (desiredElapsed - nowElapsed).coerceAtLeast(0L).coerceAtMost(10_000L)
                            if (toWait > 0L) delay(toWait)
                        }
                    }

                    when (action) {
                        is MacroAction.Wait -> {
                            if (useTimeline) return@forEachIndexed // timeline already handled
                            val original = action.durationMs.coerceAtLeast(0L)
                            val applied = (original - postScrollCreditMs).coerceAtLeast(0L)
                            val sped = (applied.toDouble() / speed).toLong()
                            Log.d(TAG_PLAYBACK, "WAIT idx=${idx + 1}/${actions.size} original=${original}ms credit=${postScrollCreditMs}ms applied=${applied}ms")
                            if (sped > 0) delay(sped)
                            postScrollCreditMs = 0L
                        }
                        is MacroAction.Tap -> {
                            postScrollCreditMs = 0L
                            val clicked = performNodeClickForTap(action, payload, curW, curH)
                            if (!clicked) {
                                val resolved =
                                    resolveTapPoint(
                                        tap = action,
                                        payload = payload,
                                        curW = curW,
                                        curH = curH,
                                    )
                                val (x, y) = resolved.point
                                Log.d(
                                    TAG_PLAYBACK,
                                    "TAP idx=${idx + 1}/${actions.size} mode=${resolved.mode} x=$x y=$y",
                                )
                                dispatchTap(x, y)
                            }
                            executed++
                        }
                        is MacroAction.Swipe -> {
                            postScrollCreditMs = 0L
                            dispatchSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
                            executed++
                        }
                        is MacroAction.Scroll -> {
                            val settleBudgetMs =
                                if (useTimeline) {
                                    val nextT = actions.getOrNull(idx + 1)?.let { tMsOf(it) }
                                    if (nextT != null && baseT != null) {
                                        val nextDesired = ((nextT - baseT).toDouble() / speed).toLong().coerceAtLeast(0L)
                                        val nowElapsed = (SystemClock.uptimeMillis() - playbackStartUptime).coerceAtLeast(0L)
                                        (nextDesired - nowElapsed).coerceIn(120L, maxScrollSettleWaitMs.toLong())
                                    } else 180L
                                } else {
                                    val nextWaitMs = (actions.getOrNull(idx + 1) as? MacroAction.Wait)?.durationMs ?: 0L
                                    if (nextWaitMs > 0L) nextWaitMs else 150L
                                }
                            val settleSpent = dispatchScrollNodeOrGestureWithSettle(action, curW, curH, settleBudgetMs)
                            // Credit settle time against the immediately-following WAIT (if any) so we don't extend runtime.
                            if (!useTimeline) {
                                val nextWaitMs = (actions.getOrNull(idx + 1) as? MacroAction.Wait)?.durationMs ?: 0L
                                postScrollCreditMs = if (nextWaitMs > 0L) settleSpent.coerceAtMost(nextWaitMs) else 0L
                            } else {
                                postScrollCreditMs = 0L
                            }
                            executed++
                        }
                    }
                }
            }
            if (executed <= 0) error("Playback executed 0 actions")
            return executed
        } catch (t: Throwable) {
            Log.e(TAG_PLAYBACK, "executePlayback failed", t)
            throw t
        }
    }

    private suspend fun dispatchTap(x: Int, y: Int) {
        val (curW, curH, _) = getRealDisplayInfo()
        val density = resources.displayMetrics.density
        val oxPx = (tapOffsetXDp.toFloat() * density).toInt()
        val oyPx = (tapOffsetYDp.toFloat() * density).toInt()
        val jitterPx = (tapJitterDp.toFloat() * density).toInt().coerceAtLeast(0)
        val (jx, jy) =
            if (jitterPx > 0) {
                val a = (Math.random() * Math.PI * 2.0)
                val r = (Math.random() * jitterPx.toDouble())
                (Math.cos(a) * r).toInt() to (Math.sin(a) * r).toInt()
            } else {
                0 to 0
            }
        val ax = (x + oxPx + jx).coerceIn(0, curW - 1)
        val ay = (y + oyPx + jy).coerceIn(0, curH - 1)
        if (showTapDotDuringPlayback) {
            showTapDot(ax, ay, durationMs = 250)
            delay(250)
        }
        val path = Path().apply {
            moveTo(ax.toFloat(), ay.toFloat())
            lineTo(ax.toFloat() + 1f, ay.toFloat() + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L)))
            .build()
        dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchScroll(scroll: MacroAction.Scroll) {
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2
        val startY = (dm.heightPixels * 0.7f).toInt()
        val endY = (dm.heightPixels * 0.3f).toInt()
        val startX = (dm.widthPixels * 0.7f).toInt()
        val endX = (dm.widthPixels * 0.3f).toInt()

        when (scroll.direction) {
            ScrollDirection.DOWN -> dispatchSwipe(midX, startY, midX, endY, 350)
            ScrollDirection.UP -> dispatchSwipe(midX, endY, midX, startY, 350)
            ScrollDirection.LEFT -> dispatchSwipe(endX, dm.heightPixels / 2, startX, dm.heightPixels / 2, 350)
            ScrollDirection.RIGHT -> dispatchSwipe(startX, dm.heightPixels / 2, endX, dm.heightPixels / 2, 350)
        }
    }

    private data class Quad(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    private data class TapResolveResult(
        val point: Pair<Int, Int>,
        val mode: String,
    )

    private suspend fun performNodeClickForTap(tap: MacroAction.Tap, payload: MacroPayload, curW: Int, curH: Int): Boolean {
        val selector =
            tap.selector
                ?: tap.target?.let {
                    MacroAction.TargetSelector(
                        pkg = it.packageName,
                        viewId = it.viewIdResourceName,
                        text = it.text,
                        cls = it.className,
                    )
                }
        if (selector == null) return false

        val expected = tapToPixels(tap, payload, curW, curH)
        val node = findBestNodeForSelector(selector, expected.first, expected.second) ?: return false
        try {
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }
            val x = rect.centerX().coerceIn(0, curW - 1)
            val y = rect.centerY().coerceIn(0, curH - 1)
            Log.d(TAG_PLAYBACK, "TAP selector viewId=${selector.viewId} text=${selector.text} cls=${selector.cls} expected=${expected.first},${expected.second}")

            if (showTapDotDuringPlayback) {
                showTapDot(x, y, durationMs = 250)
                delay(250)
            }

            val clicked = clickNodeOrParent(node)
            if (clicked) {
                Log.d(TAG_PLAYBACK, "TAP nodeClick SUCCESS")
                delay((120L..250L).random())
                return true
            }
            Log.d(TAG_PLAYBACK, "TAP nodeClick FAILED -> fallback gesture")
            return false
        } finally {
            runCatching { node.recycle() }
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            var hops = 0
            while (cur != null && !cur.isClickable && hops < 8) {
                val parent = cur.parent
                cur.recycle()
                cur = parent
                hops++
            }
            if (cur == null) return false
            return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            cur?.recycle()
        }
    }

    private fun resolveTapPoint(
        tap: MacroAction.Tap,
        payload: MacroPayload,
        curW: Int,
        curH: Int,
    ): TapResolveResult {
        val target = tap.target
        val expected = tapToPixels(tap, payload, curW, curH)
        if (target != null && (target.viewIdResourceName != null || !target.text.isNullOrBlank())) {
            val rect =
                findBestNodeRect(
                    viewId = target.viewIdResourceName,
                    textSnippet = target.text,
                    className = target.className,
                    expectedX = expected.first,
                    expectedY = expected.second,
                )
            if (rect != null && !rect.isEmpty) {
                val x = rect.centerX().coerceIn(0, curW - 1)
                val y = rect.centerY().coerceIn(0, curH - 1)
                return TapResolveResult(point = x to y, mode = "TARGET_RETARGETED")
            }
            // Fallback to recorded coordinates if not found.
            return TapResolveResult(point = expected, mode = "TARGET_NOT_FOUND_FALLBACK")
        }

        return TapResolveResult(point = expected, mode = "RAW_COORDS")
    }

    private fun findBestNodeRect(
        viewId: String?,
        textSnippet: String?,
        className: String?,
        expectedX: Int,
        expectedY: Int,
    ): Rect? {
        val root = rootInActiveWindow ?: return null
        return try {
            val best = BestMatch()
            traverseForBest(root, viewId, textSnippet, className, expectedX, expectedY, best)
            best.rect
        } catch (t: Throwable) {
            Log.e(TAG_PLAYBACK, "findBestNodeRect failed", t)
            null
        } finally {
            runCatching { root.recycle() }
        }
    }

    private class BestMatch {
        var bestScore: Long = Long.MAX_VALUE
        var rect: Rect? = null
    }

    private class BestNodeMatch {
        var bestScore: Long = Long.MAX_VALUE
        var node: AccessibilityNodeInfo? = null
    }

    private fun findBestNodeForSelector(selector: MacroAction.TargetSelector, expectedX: Int, expectedY: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return try {
            val best = BestNodeMatch()
            traverseForBestNode(root, selector.viewId, selector.text, selector.cls, expectedX, expectedY, best)
            best.node
        } catch (t: Throwable) {
            Log.e(TAG_PLAYBACK, "findBestNodeForSelector failed", t)
            null
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun traverseForBestNode(
        node: AccessibilityNodeInfo,
        viewId: String?,
        textSnippet: String?,
        className: String?,
        expectedX: Int,
        expectedY: Int,
        best: BestNodeMatch,
    ) {
        val nodeViewId = runCatching { node.viewIdResourceName }.getOrNull()
        val nodeText = runCatching { node.text?.toString() }.getOrNull()
        val nodeClass = runCatching { node.className?.toString() }.getOrNull()

        val idMatch = viewId != null && nodeViewId != null && nodeViewId == viewId
        val textMatch =
            !textSnippet.isNullOrBlank() &&
                !nodeText.isNullOrBlank() &&
                (nodeText.contains(textSnippet, ignoreCase = true) || nodeText.equals(textSnippet, ignoreCase = true))
        val classMatch = className != null && nodeClass != null && nodeClass == className

        if (idMatch || textMatch) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val cx = r.centerX()
                val cy = r.centerY()
                val dx = (cx - expectedX).toLong()
                val dy = (cy - expectedY).toLong()
                val dist2 = dx * dx + dy * dy
                val idPenalty = if (idMatch) 0L else 10_000_000_000L
                val classPenalty = if (classMatch) 0L else 1_000_000_000L
                val score = idPenalty + classPenalty + dist2
                if (score < best.bestScore) {
                    best.bestScore = score
                    best.node?.let { runCatching { it.recycle() } }
                    best.node = AccessibilityNodeInfo.obtain(node)
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseForBestNode(child, viewId, textSnippet, className, expectedX, expectedY, best)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private fun traverseForBest(
        node: AccessibilityNodeInfo,
        viewId: String?,
        textSnippet: String?,
        className: String?,
        expectedX: Int,
        expectedY: Int,
        best: BestMatch,
    ) {
        val nodeViewId = runCatching { node.viewIdResourceName }.getOrNull()
        val nodeText = runCatching { node.text?.toString() }.getOrNull()
        val nodeClass = runCatching { node.className?.toString() }.getOrNull()

        val idMatch = viewId != null && nodeViewId != null && nodeViewId == viewId
        val textMatch =
            !textSnippet.isNullOrBlank() &&
                !nodeText.isNullOrBlank() &&
                (nodeText.contains(textSnippet, ignoreCase = true) || nodeText.equals(textSnippet, ignoreCase = true))
        val classMatch = className != null && nodeClass != null && nodeClass == className

        if (idMatch || textMatch) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val cx = r.centerX()
                val cy = r.centerY()
                val dx = (cx - expectedX).toLong()
                val dy = (cy - expectedY).toLong()
                val dist2 = dx * dx + dy * dy
                // Prefer id match, then class match, then closest to expected.
                val idPenalty = if (idMatch) 0L else 10_000_000_000L
                val classPenalty = if (classMatch) 0L else 1_000_000_000L
                val score = idPenalty + classPenalty + dist2
                if (score < best.bestScore) {
                    best.bestScore = score
                    best.rect = r
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseForBest(child, viewId, textSnippet, className, expectedX, expectedY, best)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private suspend fun dispatchScrollWithSettle(
        scroll: MacroAction.Scroll,
        curW: Int,
        curH: Int,
        settleBudgetMs: Long,
    ): Long {
        // Step-based scrolling: break large scroll into multiple smaller swipes (1..6).
        val stepPx = 800f
        val maxSteps = 6

        val (sx, sy, ex, ey, durMs) = scrollToSwipePixels(scroll, curW, curH)
        val dx = (ex - sx).toFloat()
        val dy = (ey - sy).toFloat()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val steps = kotlin.math.ceil(distance / stepPx).toInt().coerceIn(1, maxSteps)
        val stepDelayMs = (120L..250L).random()

        Log.d(
            TAG_PLAYBACK,
            "SCROLL dir=${scroll.direction} amount=${scroll.amount} distance=${distance.toInt()}px steps=$steps dur=${durMs}ms",
        )

        for (i in 1..steps) {
            val t0 = (i - 1).toFloat() / steps.toFloat()
            val t1 = i.toFloat() / steps.toFloat()
            val x1 = (sx + dx * t0).roundToInt()
            val y1 = (sy + dy * t0).roundToInt()
            val x2 = (sx + dx * t1).roundToInt()
            val y2 = (sy + dy * t1).roundToInt()
            val stepDur = (durMs / steps).coerceAtLeast(180L)
            Log.d(TAG_PLAYBACK, "SCROLL step $i/$steps swipe ($x1,$y1)->($x2,$y2) dur=${stepDur}ms")
            // Prime lastScrollEventAtMs baseline to avoid waiting on stale value.
            lastScrollEventAtMs = SystemClock.uptimeMillis()
            dispatchSwipe(x1, y1, x2, y2, stepDur)
            if (i != steps) delay(stepDelayMs)
        }

        // After final step: mandatory settle wait + event quiet-window wait.
        val settleStart = SystemClock.uptimeMillis()
        val budget = settleBudgetMs.coerceAtLeast(0L)
        val minPost = postScrollSettleMs.toLong().coerceAtLeast(0L).coerceAtMost(budget)
        if (minPost > 0) delay(minPost)

        val quietWindow = scrollEventQuietWindowMs.toLong().coerceAtLeast(0L)
        val maxWait = maxScrollSettleWaitMs.toLong().coerceAtLeast(0L).coerceAtMost(budget)
        val settleDeadline = settleStart + maxWait
        var loops = 0
        while (SystemClock.uptimeMillis() < settleDeadline) {
            loops++
            val last = lastScrollEventAtMs
            val now = SystemClock.uptimeMillis()
            val since = now - last
            if (since >= quietWindow) break
            val sleep = (quietWindow - since).coerceIn(25L, 120L)
            delay(sleep)
        }
        val spent = (SystemClock.uptimeMillis() - settleStart).coerceAtLeast(0L)
        Log.d(
            TAG_PLAYBACK,
            "SCROLL settle spent=${spent}ms (minPost=${minPost} quiet=${quietWindow} max=${maxWait}) loops=$loops lastScrollAt=${lastScrollEventAtMs}",
        )
        return spent
    }

    private suspend fun dispatchScrollNodeOrGestureWithSettle(
        scroll: MacroAction.Scroll,
        curW: Int,
        curH: Int,
        settleBudgetMs: Long,
    ): Long {
        // Try node scroll actions first.
        val (sx, sy, ex, ey, _) = scrollToSwipePixels(scroll, curW, curH)
        val midX = (sx + ex) / 2
        val midY = (sy + ey) / 2

        val steps =
            kotlin.math.ceil((scroll.amount.coerceAtLeast(1)).toFloat() / 700f)
                .toInt()
                .coerceIn(1, 6)

        val forward = scroll.direction == ScrollDirection.DOWN || scroll.direction == ScrollDirection.RIGHT
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        val selector = scroll.selector
        val ok =
            runCatching {
                repeat(steps) { stepIdx ->
                    val node = findBestScrollableNode(selector, midX, midY) ?: return@runCatching false
                    val performed =
                        try {
                            node.performAction(action)
                        } finally {
                            runCatching { node.recycle() }
                        }
                    Log.d(TAG_PLAYBACK, "SCROLL nodeAction step=${stepIdx + 1}/$steps action=$action ok=$performed")
                    if (!performed) return@runCatching false
                    delay((120L..250L).random())
                }
                true
            }.getOrDefault(false)

        return if (ok) {
            val spent = waitForScrollSettle(settleBudgetMs = settleBudgetMs)
            Log.d(TAG_PLAYBACK, "SCROLL nodeAction settleSpent=${spent}ms")
            spent
        } else {
            Log.d(TAG_PLAYBACK, "SCROLL nodeAction failed -> fallback gesture")
            dispatchScrollWithSettle(scroll, curW, curH, settleBudgetMs = settleBudgetMs)
        }
    }

    private fun findBestScrollableNode(selector: MacroAction.TargetSelector?, expectedX: Int, expectedY: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return try {
            val best = BestNodeMatch()
            traverseForBestScrollable(root, selector?.viewId, selector?.text, selector?.cls, expectedX, expectedY, best)
            best.node
        } catch (t: Throwable) {
            Log.e(TAG_PLAYBACK, "findBestScrollableNode failed", t)
            null
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun traverseForBestScrollable(
        node: AccessibilityNodeInfo,
        viewId: String?,
        textSnippet: String?,
        className: String?,
        expectedX: Int,
        expectedY: Int,
        best: BestNodeMatch,
    ) {
        val isScrollable = runCatching { node.isScrollable }.getOrNull() == true
        if (isScrollable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val nodeViewId = runCatching { node.viewIdResourceName }.getOrNull()
                val nodeText = runCatching { node.text?.toString() }.getOrNull()
                val nodeClass = runCatching { node.className?.toString() }.getOrNull()

                val idMatch = viewId != null && nodeViewId != null && nodeViewId == viewId
                val textMatch =
                    !textSnippet.isNullOrBlank() &&
                        !nodeText.isNullOrBlank() &&
                        nodeText.contains(textSnippet, ignoreCase = true)
                val classMatch = className != null && nodeClass != null && nodeClass == className

                val cx = r.centerX()
                val cy = r.centerY()
                val dx = (cx - expectedX).toLong()
                val dy = (cy - expectedY).toLong()
                val dist2 = dx * dx + dy * dy
                val idPenalty = if (idMatch) 0L else 10_000_000_000L
                val classPenalty = if (classMatch) 0L else 1_000_000_000L
                val textPenalty = if (textMatch) 0L else 2_000_000_000L
                val score = idPenalty + classPenalty + textPenalty + dist2
                if (score < best.bestScore) {
                    best.bestScore = score
                    best.node?.let { runCatching { it.recycle() } }
                    best.node = AccessibilityNodeInfo.obtain(node)
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseForBestScrollable(child, viewId, textSnippet, className, expectedX, expectedY, best)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private suspend fun waitForScrollSettle(settleBudgetMs: Long): Long {
        val settleStart = SystemClock.uptimeMillis()
        val budget = settleBudgetMs.coerceAtLeast(0L)
        val minPost = postScrollSettleMs.toLong().coerceAtLeast(0L).coerceAtMost(budget)
        if (minPost > 0) delay(minPost)

        val quietWindow = scrollEventQuietWindowMs.toLong().coerceAtLeast(0L)
        val maxWait = maxScrollSettleWaitMs.toLong().coerceAtLeast(0L).coerceAtMost(budget)
        val settleDeadline = settleStart + maxWait
        while (SystemClock.uptimeMillis() < settleDeadline) {
            val last = lastScrollEventAtMs
            val now = SystemClock.uptimeMillis()
            val since = now - last
            if (since >= quietWindow) break
            val sleep = (quietWindow - since).coerceIn(25L, 120L)
            delay(sleep)
        }
        return (SystemClock.uptimeMillis() - settleStart).coerceAtLeast(0L)
    }

    private fun scrollToSwipePixels(scroll: MacroAction.Scroll, curW: Int, curH: Int): QuadWithDuration {
        val dur = scroll.durationMs ?: 350L
        val sxNy = scroll.startNy
        val sxNx = scroll.startNx
        val exNx = scroll.endNx
        val exNy = scroll.endNy
        if (sxNx != null && sxNy != null && exNx != null && exNy != null) {
            val x1 = (sxNx * curW).roundToInt().coerceIn(0, curW - 1)
            val y1 = (sxNy * curH).roundToInt().coerceIn(0, curH - 1)
            val x2 = (exNx * curW).roundToInt().coerceIn(0, curW - 1)
            val y2 = (exNy * curH).roundToInt().coerceIn(0, curH - 1)
            return QuadWithDuration(x1, y1, x2, y2, dur)
        }

        // Fallback: use current generic swipe for direction.
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2
        val startY = (dm.heightPixels * 0.7f).toInt()
        val endY = (dm.heightPixels * 0.3f).toInt()
        val startX = (dm.widthPixels * 0.7f).toInt()
        val endX = (dm.widthPixels * 0.3f).toInt()
        return when (scroll.direction) {
            ScrollDirection.DOWN -> QuadWithDuration(midX, startY, midX, endY, dur)
            ScrollDirection.UP -> QuadWithDuration(midX, endY, midX, startY, dur)
            ScrollDirection.LEFT -> QuadWithDuration(endX, dm.heightPixels / 2, startX, dm.heightPixels / 2, dur)
            ScrollDirection.RIGHT -> QuadWithDuration(startX, dm.heightPixels / 2, endX, dm.heightPixels / 2, dur)
        }
    }

    private data class QuadWithDuration(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durMs: Long)

    private suspend fun dispatchGestureAwait(gesture: GestureDescription) {
        val ok =
            withTimeout(3_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    // dispatchGesture should be invoked on main thread.
                    mainHandler.post {
                        val dispatched =
                            dispatchGesture(
                                gesture,
                                object : GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: GestureDescription?) {
                                        if (cont.isActive) cont.resume(true)
                                    }

                                    override fun onCancelled(gestureDescription: GestureDescription?) {
                                        if (cont.isActive) cont.resume(false)
                                    }
                                },
                                null,
                            )
                        if (!dispatched && cont.isActive) cont.resume(false)
                    }
                }
            }
        if (!ok) error("dispatchGesture failed/cancelled")
    }

    private fun tapToPixels(action: MacroAction.Tap, payload: MacroPayload, curW: Int, curH: Int): Pair<Int, Int> {
        val nx = action.nx
        val ny = action.ny
        if (nx != null && ny != null) {
            val x = (nx * curW.toFloat()).roundToInt().coerceIn(0, curW - 1)
            val y = (ny * curH.toFloat()).roundToInt().coerceIn(0, curH - 1)
            return x to y
        }

        // Backward compat: if only absolute pixels exist, try to normalize using recorded screen.
        val xPx = action.x
        val yPx = action.y
        if (xPx != null && yPx != null) {
            val recW = payload.recordScreenW
            val recH = payload.recordScreenH
            if (recW != null && recH != null && recW > 0 && recH > 0) {
                val nnx = xPx.toFloat() / recW.toFloat()
                val nny = yPx.toFloat() / recH.toFloat()
                val x = (nnx * curW.toFloat()).roundToInt().coerceIn(0, curW - 1)
                val y = (nny * curH.toFloat()).roundToInt().coerceIn(0, curH - 1)
                return x to y
            }
            // Best-effort: assume same screen space.
            return xPx.coerceIn(0, curW - 1) to yPx.coerceIn(0, curH - 1)
        }

        return (curW / 2) to (curH / 2)
    }

    private fun getRealDisplayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        val rotation = display.rotation
        return Triple(p.x, p.y, rotation)
    }

    private fun showTapDot(x: Int, y: Int, durationMs: Long) {
        // TYPE_ACCESSIBILITY_OVERLAY does not require overlay permission.
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sizePx = (resources.displayMetrics.density * 14f).roundToInt().coerceAtLeast(8)

        mainHandler.post {
            // Remove any previous dot.
            tapDotView?.let { old ->
                runCatching { wm.removeView(old) }
                tapDotView = null
            }

            val v = View(this).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x66FF0000) // semi-transparent red
                    }
            }
            val lp =
                WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = (x - sizePx / 2).coerceAtLeast(0)
                    this.y = (y - sizePx / 2).coerceAtLeast(0)
                }

            runCatching { wm.addView(v, lp) }
            tapDotView = v

            mainHandler.postDelayed({
                val cur = tapDotView
                if (cur === v) {
                    runCatching { wm.removeView(v) }
                    tapDotView = null
                } else {
                    runCatching { wm.removeView(v) }
                }
            }, durationMs)
        }
    }

    private fun showPlaybackStopOverlay(runId: String) {
        // Use the same widget as recording, but TYPE_ACCESSIBILITY_OVERLAY (no overlay permission).
        if (playbackOverlayWidget != null) return
        Log.i(TAG_PLAYBACK, "overlay_show(mode=playback) runId=$runId")
        playbackOverlayStartElapsedMs = SystemClock.elapsedRealtime()
        playbackOverlayWidget =
            OverlayStopWidget(
                context = this,
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                mode = "playback",
                onStopClicked = {
                    Log.i(TAG_PLAYBACK, "overlay_stop_clicked(mode=playback) runId=$runId")
                    MacroPlaybackBus.requestCancel(runId = runId, reason = "Cancelled by user")
                },
                elapsedMsProvider = {
                    val start = playbackOverlayStartElapsedMs ?: return@OverlayStopWidget 0L
                    (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                },
                // Playback overlay: show only STOP (no timer) to reduce UI load on MIUI.
                showTimer = false,
                // Some OEMs require in-screen layout flags for accessibility overlays.
                layoutFlags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
        // Ensure UI work happens on main.
        mainHandler.post {
            runCatching { playbackOverlayWidget?.show() }
                .onFailure { Log.e(TAG_PLAYBACK, "overlay_show failed", it) }
        }
    }

    private fun hidePlaybackStopOverlay() {
        val w = playbackOverlayWidget ?: return
        mainHandler.post {
            runCatching { w.hide() }
            playbackOverlayWidget = null
            playbackOverlayStartElapsedMs = null
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

