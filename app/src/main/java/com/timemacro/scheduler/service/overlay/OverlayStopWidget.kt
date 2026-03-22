package com.timemacro.scheduler.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.timemacro.scheduler.R

/**
 * Shared draggable overlay STOP widget used by:
 * - recording overlay service (TYPE_APPLICATION_OVERLAY)
 * - accessibility playback overlay (TYPE_ACCESSIBILITY_OVERLAY)
 *
 * Layout order in `overlay_stop_panel.xml`: [STOP][TIMER]
 */
class OverlayStopWidget(
    private val context: Context,
    private val windowType: Int,
    private val mode: String, // "record" | "playback"
    private val onStopClicked: () -> Unit,
    private val elapsedMsProvider: () -> Long?,
    private val showTimer: Boolean = true,
    private val layoutFlags: Int = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN),
) {
    private companion object {
        private const val TAG = "TimeMacro/Recording"
        private const val TICK_MS = 500L
        private const val TIMER_LOG_EVERY_MS = 2_000L

        private const val PREFS = "overlay_stop_prefs"
        private const val KEY_EDGE_END = "edge_end"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }

    private val appContext = context.applicationContext
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var view: View? = null
    private var timerView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastTimerLogAt: Long = 0L

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                updateTimer()
                handler.postDelayed(this, TICK_MS)
            }
        }

    fun show() {
        if (view != null) return
        Log.i(TAG, "overlay_show(mode=$mode)")

        val v = LayoutInflater.from(appContext).inflate(R.layout.overlay_stop_panel, null, false)
        view = v
        timerView = v.findViewById(R.id.overlayTimer)
        if (!showTimer) {
            timerView?.visibility = View.GONE
        }

        val (_, _, _, _, insetRight, insetBottom) = getSafeArea()
        val margin = dp(12)

        val savedEdgeEnd = prefs.getBoolean(KEY_EDGE_END, true)
        val savedX = prefs.getInt(KEY_X, -1)
        val savedY = prefs.getInt(KEY_Y, -1)

        // Default: bottom-right with safe-area padding (nav/gesture bar).
        val defaultX = (insetRight + margin).coerceAtLeast(0)
        val defaultY = (insetBottom + margin).coerceAtLeast(0)

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                layoutFlags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = (if (savedEdgeEnd) Gravity.END else Gravity.START) or Gravity.BOTTOM
                x = if (savedX >= 0) savedX else defaultX
                y = if (savedY >= 0) savedY else defaultY
            }
        params = lp

        v.findViewById<TextView>(R.id.overlayStop).setOnClickListener {
            Log.i(TAG, "overlay_stop_clicked(mode=$mode)")
            onStopClicked()
        }

        v.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val isEnd = (p.gravity and Gravity.END) == Gravity.END
                    val isBottom = (p.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
                    // x/y are offsets from the gravity edges.
                    p.x = if (isEnd) (initialX - dx) else (initialX + dx)
                    p.y = if (isBottom) (initialY - dy) else (initialY + dy)
                    clampToScreen(v, p)
                    wm.updateViewLayout(v, p)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    snapToEdgeAndPersist(v, p)
                    true
                }

                else -> false
            }
        }

        runCatching { wm.addView(v, lp) }
            .onFailure {
                Log.e(TAG, "overlay_show failed (mode=$mode type=$windowType)", it)
                // Ensure we don't keep a half-initialized instance.
                view = null
                timerView = null
                params = null
                return
            }
        // Clamp once we know measured width/height.
        v.post {
            val p = params ?: return@post
            clampToScreen(v, p)
            wm.updateViewLayout(v, p)
        }
        if (showTimer) handler.post(tickRunnable)
    }

    fun hide() {
        val v = view ?: return
        Log.i(TAG, "overlay_hide(mode=$mode)")
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(v) }
        view = null
        timerView = null
        params = null
    }

    private fun updateTimer() {
        if (!showTimer) return
        val elapsed = elapsedMsProvider.invoke()?.coerceAtLeast(0L) ?: 0L
        val mm = (elapsed / 60_000)
        val ss = (elapsed / 1000) % 60
        timerView?.text = String.format("%02d:%02d", mm, ss)
        val now = SystemClock.uptimeMillis()
        if (now - lastTimerLogAt >= TIMER_LOG_EVERY_MS) {
            lastTimerLogAt = now
            Log.v(TAG, "overlay_timer_tick(mode=$mode ms=$elapsed)")
        }
    }

    private fun snapToEdgeAndPersist(v: View, p: WindowManager.LayoutParams) {
        val (screenW, _, insetLeft, _, insetRight, _) = getSafeArea()
        val loc = IntArray(2)
        runCatching { v.getLocationOnScreen(loc) }
        val centerX = loc[0] + (v.width / 2)
        // Prefer RIGHT if equidistant.
        val snapToLeft = centerX < (screenW / 2)

        p.gravity = (if (snapToLeft) Gravity.START else Gravity.END) or Gravity.BOTTOM

        val margin = dp(12)
        p.x = (if (snapToLeft) insetLeft else insetRight) + margin
        clampToScreen(v, p)
        wm.updateViewLayout(v, p)

        // Persist last position (shared for record + playback).
        val edgeEnd = !snapToLeft
        prefs.edit()
            .putBoolean(KEY_EDGE_END, edgeEnd)
            .putInt(KEY_X, p.x.coerceAtLeast(0))
            .putInt(KEY_Y, p.y.coerceAtLeast(0))
            .apply()
    }

    private fun clampToScreen(v: View, p: WindowManager.LayoutParams) {
        val (screenW, screenH, insetLeft, insetTop, insetRight, insetBottom) = getSafeArea()
        val margin = dp(12)

        val isEnd = (p.gravity and Gravity.END) == Gravity.END
        val isBottom = (p.gravity and Gravity.BOTTOM) == Gravity.BOTTOM

        val minX = (if (isEnd) insetRight else insetLeft) + margin
        val maxX = (screenW - v.width - (if (isEnd) insetLeft else insetRight) - margin).coerceAtLeast(minX)
        p.x = p.x.coerceIn(minX, maxX)

        // y is offset from bottom.
        val minY = insetBottom + margin
        val maxY = (screenH - v.height - insetTop - margin).coerceAtLeast(minY)
        p.y = if (isBottom) p.y.coerceIn(minY, maxY) else p.y.coerceIn(0, maxY)
    }

    private fun getSafeArea(): SafeArea {
        val dm = appContext.resources.displayMetrics
        var screenW = dm.widthPixels
        var screenH = dm.heightPixels
        var left = 0
        var top = 0
        var right = 0
        var bottom = navBarHeightFallback()

        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width()
            screenH = m.bounds.height()
            val insets =
                m.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
                )
            left = insets.left
            top = insets.top
            right = insets.right
            bottom = insets.bottom
        }
        return SafeArea(screenW, screenH, left, top, right, bottom)
    }

    private fun navBarHeightFallback(): Int {
        val resId = appContext.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) appContext.resources.getDimensionPixelSize(resId) else 0
    }

    private fun dp(v: Int): Int = (appContext.resources.displayMetrics.density * v.toFloat()).toInt()

    private data class SafeArea(
        val w: Int,
        val h: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int,
    )
}

