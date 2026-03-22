package com.timemacro.scheduler.ui.screens.macros

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.core.macro.MacroSessionManager
import com.timemacro.scheduler.core.recording.RecordingController
import com.timemacro.scheduler.service.keepalive.KeepAliveService
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.timemacro.scheduler.core.accessibility.AccessibilityRuntimeUiState

@Composable
fun MacroRecordScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMacroSaved: (macroId: String) -> Unit,
    onCancel: () -> Unit,
    onOpenMiuiHelp: () -> Unit,
) {
    val context = LocalContext.current
    // Tap recording is Accessibility-only; no MediaProjection / overlay required.

    val state by MacroSessionManager.state.collectAsState()
    val isRecording = state is MacroSessionManager.RecordingState.Recording
    var didAutoMinimize by remember { mutableStateOf(false) }
    val hasOverlayPermission = Settings.canDrawOverlays(context)
    val lastEventDebug by MacroSessionManager.lastEventDebug.collectAsState(initial = null)

    val repo = container.accessibilityStateRepository
    val snap by repo.snapshot.collectAsState()
    val enabledInSettings = snap.enabledInSettings
    val runtimeConnected = snap.runtimeConnected
    val runtimeUiState = snap.runtimeUiState

    var name by rememberSaveable { mutableStateOf("") }
    var nowTickMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            nowTickMs = SystemClock.elapsedRealtime()
        }
    }

    // Auto-minimize after recording is truly started (service confirmed Recording state).
    LaunchedEffect(state) {
        if (!didAutoMinimize && state is MacroSessionManager.RecordingState.Recording) {
            didAutoMinimize = true
            // Optional: allow user to switch to target app quickly.
            runCatching { context.findActivity()?.moveTaskToBack(true) }
        }
        if (state is MacroSessionManager.RecordingState.Idle) {
            didAutoMinimize = false
        }
    }

    // Refresh enabled-in-settings on resume and when user toggles Accessibility setting.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    repo.onAppForegrounded()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        val contentObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    repo.refreshEnabledInSettings()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            contentObserver,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            contentObserver,
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.contentResolver.unregisterContentObserver(contentObserver) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Macro Recording", style = MaterialTheme.typography.headlineSmall)

        if (!enabledInSettings) {
            Log.d("TimeMacro/Recording", "MacroRecord: enabledInSettings=false")
            Text(
                text = "Accessibility is required to capture actions & play macros.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { openAccessibilitySettings(context) }) { Text("Enable Accessibility") }
        } else if (!runtimeConnected) {
            Log.d("TimeMacro/Recording", "MacroRecord: enabled but runtimeConnected=false ui=$runtimeUiState")
            val msg =
                if (runtimeUiState == AccessibilityRuntimeUiState.CONNECTING) {
                    "Accessibility enabled. Connecting…"
                } else {
                    "Disconnected by system (MIUI). Please reconnect before recording."
                }
            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
            if (snap.isMiui || snap.isXiaomiFamily) {
                Button(onClick = onOpenMiuiHelp, modifier = Modifier.fillMaxWidth()) { Text("Fix on MIUI") }
            }
            Button(onClick = { openAccessibilitySettings(context) }) { Text("Open Accessibility Settings") }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = name,
            onValueChange = { name = it },
            label = { Text("Macro name (optional)") },
            singleLine = true,
            enabled = !isRecording,
        )

        val statusText: String
        val actionsCount: Int
        val elapsedMs: Long
        when (val s = state) {
            MacroSessionManager.RecordingState.Idle -> {
                statusText = "Not recording"
                actionsCount = 0
                elapsedMs = 0
            }

            is MacroSessionManager.RecordingState.Starting -> {
                statusText = "Starting…"
                actionsCount = 0
                elapsedMs = 0
            }

            is MacroSessionManager.RecordingState.Recording -> {
                statusText = "RECORDING ACTIVE"
                actionsCount = s.actionsCount
                val startElapsed = MacroSessionManager.recordStartElapsedRealtime()
                elapsedMs = if (startElapsed != null) (nowTickMs - startElapsed).coerceAtLeast(0) else 0L
            }

            is MacroSessionManager.RecordingState.Stopped -> {
                statusText = "Stopped"
                actionsCount = s.actionsCount
                elapsedMs = s.durationMs
            }
        }
        val mm = (elapsedMs / 60_000)
        val ss = (elapsedMs / 1000) % 60

        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusText, style = MaterialTheme.typography.titleMedium)
                Text("Timer: %02d:%02d".format(mm, ss))
                Text("Captured actions: $actionsCount")
                if (!lastEventDebug.isNullOrBlank()) {
                    Text("Last event: $lastEventDebug", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (isRecording && !hasOverlayPermission) {
            Text(
                text = "Overlay disabled. Use in-app STOP button.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = when (state) {
                MacroSessionManager.RecordingState.Idle -> "Status: Idle"
                is MacroSessionManager.RecordingState.Starting -> "Status: Starting…"
                is MacroSessionManager.RecordingState.Recording -> {
                    val s = state as MacroSessionManager.RecordingState.Recording
                    "Status: Recording… captured actions: ${s.actionsCount}"
                }

                is MacroSessionManager.RecordingState.Stopped -> {
                    val s = state as MacroSessionManager.RecordingState.Stopped
                    "Status: Stopped • duration: ${s.durationMs}ms • actions: ${s.actionsCount}"
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Button(
            onClick = {
                Log.i("TimeMacro/Recording", "StartRecording clicked runtimeConnected=$runtimeConnected enabledInSettings=$enabledInSettings")
                if (!runtimeConnected) return@Button
                MacroSessionManager.setPendingName(name)
                // Capture real display size + rotation at record start for tap normalization.
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val p = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(p)
                val rotation = display.rotation
                val densityDpi = context.resources.displayMetrics.densityDpi
                MacroSessionManager.setRecordingDisplayInfo(
                    w = p.x,
                    h = p.y,
                    rotation = rotation,
                    densityDpi = densityDpi,
                )
                Log.i("TimeMacro/Recording", "start tap-only session display=${p.x}x${p.y} rot=$rotation")
                MacroSessionManager.startTapOnlySession()
                KeepAliveService.start(context, token = "recording", label = "Recording")
                RecordingController.startOverlayIfPermitted(context, reason = "TAP_ONLY_START")
                // Immediately minimize so user can interact with target app.
                runCatching { context.findActivity()?.moveTaskToBack(true) }
            },
            enabled = runtimeConnected && !isRecording && state !is MacroSessionManager.RecordingState.Starting,
        ) {
            Text("Start Recording (taps only)")
        }

        Button(
            onClick = {
                Log.i("TimeMacro/Recording", "STOP clicked (UI)")
                RecordingController.stopRecording(context, trigger = "UI")
                onMacroSaved("tap-only")
            },
            enabled = isRecording,
        ) {
            Text("STOP")
        }

        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
