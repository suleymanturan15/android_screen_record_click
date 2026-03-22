package com.timemacro.scheduler.core.macro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MacroPayload(
    val version: Int = 1,
    val videoPath: String? = null,
    // Recording-time screen metadata for coordinate normalization.
    val recordScreenW: Int? = null,
    val recordScreenH: Int? = null,
    val recordRotation: Int? = null, // Surface.ROTATION_*
    val recordDensityDpi: Int? = null,
    // Best-effort: dominant package name where the macro was recorded (used by Task preflight).
    val targetPackageName: String? = null,
    val actions: List<MacroAction>,
)

@Serializable
sealed class MacroAction {
    @Serializable
    @SerialName("wait")
    data class Wait(
        val durationMs: Long,
        // Optional timestamp (ms) of the source event (best-effort).
        val tMs: Long? = null,
    ) : MacroAction()

    @Serializable
    data class TapTarget(
        val viewIdResourceName: String? = null,
        val text: String? = null,
        val className: String? = null,
        val packageName: String? = null,
        // Event type that produced this tap candidate (e.g. TYPE_VIEW_CLICKED / TYPE_VIEW_FOCUSED).
        val sourceEventType: Int? = null,
        // Bounds at record time (best-effort).
        val left: Int? = null,
        val top: Int? = null,
        val right: Int? = null,
        val bottom: Int? = null,
    )

    /**
     * Preferred selector (stable across layout shifts).
     */
    @Serializable
    data class TargetSelector(
        val pkg: String? = null,
        val viewId: String? = null,
        val text: String? = null,
        val cls: String? = null,
        val isClickable: Boolean? = null,
        val isScrollable: Boolean? = null,
        // Bounds at record time (optional).
        val left: Int? = null,
        val top: Int? = null,
        val right: Int? = null,
        val bottom: Int? = null,
    )

    @Serializable
    @SerialName("tap")
    data class Tap(
        // Backward compatibility: legacy absolute px taps (may be null for new macros).
        val x: Int? = null,
        val y: Int? = null,
        // Preferred: normalized coordinates in [0..1] recorded against recordScreenW/H.
        val nx: Float? = null,
        val ny: Float? = null,
        // Optional legacy metadata (backward compat).
        val target: TapTarget? = null,
        // Preferred: selector used to find/click the same node at playback time.
        val selector: TargetSelector? = null,
        // Optional timestamp (ms) of the source event (best-effort).
        val tMs: Long? = null,
    ) : MacroAction()

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
        // Optional timestamp (ms) of the source event (best-effort).
        val tMs: Long? = null,
    ) : MacroAction()

    /**
     * MVP: AccessibilityEvent'lerden gerçek swipe path çıkarmak zor.
     * Bu yüzden scroll event'lerini "direction + amount" olarak kaydediyoruz.
     */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: ScrollDirection,
        val amount: Int,
        // Preferred: scroll target selector (best-effort).
        val selector: TargetSelector? = null,
        // Optional replay geometry. If present, playback prefers this.
        val startNx: Float? = null,
        val startNy: Float? = null,
        val endNx: Float? = null,
        val endNy: Float? = null,
        val durationMs: Long? = null,
        // Optional timestamp (ms) of the source event (best-effort).
        val tMs: Long? = null,
    ) : MacroAction()
}

@Serializable
enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

