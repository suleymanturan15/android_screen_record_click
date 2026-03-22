package com.timemacro.scheduler.ui.screens.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.timemacro.scheduler.AppContainer
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import com.timemacro.scheduler.core.accessibility.AccessibilityStateRepository
import com.timemacro.scheduler.core.accessibility.enabledAccessibilityServicesRaw
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.timemacro.scheduler.core.accessibility.AccessibilityRuntimeUiState

@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenMiuiHelp: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repo: AccessibilityStateRepository = container.accessibilityStateRepository
    val snap by repo.snapshot.collectAsState()
    val accessibilityEnabledInSettings = snap.enabledInSettings
    val lastConnectedAt = snap.lastConnectedAtWallMs
    val runtimeConnected = snap.runtimeConnected
    val runtimeUiState = snap.runtimeUiState

    var enabledServicesRaw by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var ignoringBatteryOptimizations by remember { mutableStateOf(false) }
    var canDrawOverlays by remember { mutableStateOf(false) }
    var canScheduleExactAlarms by remember { mutableStateOf(true) }

    val volumeKeyStopEnabled by container.userPreferences.stopRecordingWithVolumeKeys.collectAsState(initial = true)
    val showTapDotEnabled by container.userPreferences.showTapDotDuringPlayback.collectAsState(initial = true)
    val playbackSpeedPercent by container.userPreferences.playbackSpeedPercent.collectAsState(initial = 100)
    val tapOffsetXDp by container.userPreferences.tapOffsetXDp.collectAsState(initial = 0)
    val tapOffsetYDp by container.userPreferences.tapOffsetYDp.collectAsState(initial = 0)
    val tapJitterDp by container.userPreferences.tapJitterDp.collectAsState(initial = 0)
    val taskLaunchTargetApp by container.userPreferences.taskLaunchTargetApp.collectAsState(initial = true)
    val taskLaunchDelayMs by container.userPreferences.taskLaunchDelayMs.collectAsState(initial = 1500)

    var tapOffsetXText by remember { mutableStateOf(tapOffsetXDp.toString()) }
    var tapOffsetYText by remember { mutableStateOf(tapOffsetYDp.toString()) }
    var tapJitterText by remember { mutableStateOf(tapJitterDp.toString()) }
    var speedText by remember { mutableStateOf(playbackSpeedPercent.toString()) }
    var launchDelayText by remember { mutableStateOf(taskLaunchDelayMs.toString()) }

    LaunchedEffect(Unit) {
        enabledServicesRaw = enabledAccessibilityServicesRaw(context)
        notificationsEnabled = isNotificationsEnabled(context)
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        canDrawOverlays = Settings.canDrawOverlays(context)
        canScheduleExactAlarms = isExactAlarmsAllowed(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    repo.onAppForegrounded()
                    enabledServicesRaw = enabledAccessibilityServicesRaw(context)
                    canDrawOverlays = Settings.canDrawOverlays(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        val contentObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    repo.refreshEnabledInSettings()
                    enabledServicesRaw = enabledAccessibilityServicesRaw(context)
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
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings / Permissions", style = MaterialTheme.typography.headlineSmall)

        ChecklistRow("Accessibility enabled (Settings)", accessibilityEnabledInSettings)
        ChecklistRow(
            "Accessibility connected (runtime)",
            runtimeConnected,
            customRightText =
                when {
                    !accessibilityEnabledInSettings -> "Missing"
                    runtimeConnected -> "OK"
                    runtimeUiState == AccessibilityRuntimeUiState.CONNECTING -> "Connecting…"
                    else -> "Disconnected by system"
                },
        )
        if (accessibilityEnabledInSettings && !runtimeConnected) {
            Text(
                text =
                    if (runtimeUiState == AccessibilityRuntimeUiState.CONNECTING) {
                        "Accessibility is enabled. Reconnecting… (MIUI may take a few seconds)."
                    } else {
                        "Disconnected by system (common on MIUI). Recording/playback will not start until runtime reconnects."
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (snap.isMiui || snap.isXiaomiFamily) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenMiuiHelp,
                ) { Text("Fix on MIUI") }
            }
        }
        Text(
            text = "Last connected: ${lastConnectedAt ?: "never"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Debug (Secure.ENABLED_ACCESSIBILITY_SERVICES): ${enabledServicesRaw ?: "null"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDiagnostics,
        ) {
            Text("Diagnostics")
        }
        ChecklistRow("Notifications allowed (Android 13+)", notificationsEnabled)
        ChecklistRow("Exact alarms allowed (Android 12+)", canScheduleExactAlarms)
        ChecklistRow("Battery optimization ignored (recommended)", ignoringBatteryOptimizations)
        ChecklistRow("Overlay permission (optional)", canDrawOverlays)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Stop recording with volume keys")
            Switch(
                checked = volumeKeyStopEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { container.userPreferences.setStopRecordingWithVolumeKeys(enabled) }
                },
            )
        }
        Text(
            text = "Note: Some devices may block volume key capture. Use in-app STOP if it doesn't work.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Show tap dot during playback")
            Switch(
                checked = showTapDotEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { container.userPreferences.setShowTapDotDuringPlayback(enabled) }
                },
            )
        }
        Text(
            text = "Debug helper: shows where taps will occur (default ON).",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Playback tuning", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = speedText,
            onValueChange = { speedText = it },
            label = { Text("Speed percent (25..400) — 100=1.0x") },
            singleLine = true,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val v = speedText.trim().toIntOrNull() ?: 100
                scope.launch { container.userPreferences.setPlaybackSpeedPercent(v) }
            },
        ) { Text("Apply speed") }

        Text("Tap calibration (only affects coordinate fallback taps)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = tapOffsetXText,
            onValueChange = { tapOffsetXText = it },
            label = { Text("Tap offset X (dp) [-50..50]") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = tapOffsetYText,
            onValueChange = { tapOffsetYText = it },
            label = { Text("Tap offset Y (dp) [-50..50]") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = tapJitterText,
            onValueChange = { tapJitterText = it },
            label = { Text("Tap jitter radius (dp) [0..30]") },
            singleLine = true,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val ox = tapOffsetXText.trim().toIntOrNull() ?: 0
                val oy = tapOffsetYText.trim().toIntOrNull() ?: 0
                val j = tapJitterText.trim().toIntOrNull() ?: 0
                scope.launch {
                    container.userPreferences.setTapOffsetXDp(ox)
                    container.userPreferences.setTapOffsetYDp(oy)
                    container.userPreferences.setTapJitterDp(j)
                }
            },
        ) { Text("Apply tap calibration") }

        Text("Task preflight", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Launch target app before task playback")
            Switch(
                checked = taskLaunchTargetApp,
                onCheckedChange = { enabled ->
                    scope.launch { container.userPreferences.setTaskLaunchTargetApp(enabled) }
                },
            )
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = launchDelayText,
            onValueChange = { launchDelayText = it },
            label = { Text("Launch delay ms (0..10000)") },
            singleLine = true,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val ms = launchDelayText.trim().toIntOrNull() ?: 1500
                scope.launch { container.userPreferences.setTaskLaunchDelayMs(ms) }
            },
        ) { Text("Apply task launch delay") }

        // 2x2 button grid (bigger touch targets) + bottom padding so buttons aren't hidden by nav bar.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                onClick = { openAccessibilitySettings(context) },
            ) { Text("Accessibility") }
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                onClick = { openAppNotificationSettings(context) },
            ) { Text("Notifications") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                onClick = { openExactAlarmSettings(context) },
            ) { Text("Exact alarms") }
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                onClick = { openBatteryOptimizationSettings(context) },
            ) { Text("Battery") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                onClick = { openOverlaySettings(context) },
            ) { Text("Overlay") }
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Note: Macro playback requires Accessibility. Screen recording will request MediaProjection at record time (PART 3).",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChecklistRow(label: String, ok: Boolean, customRightText: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(customRightText ?: if (ok) "OK" else "Missing")
    }
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openBatteryOptimizationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun isNotificationsEnabled(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

private fun isExactAlarmsAllowed(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 31) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    } else {
        true
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAccessibilityServiceEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
    // Deprecated in this screen; use isAccessibilityEnabledForThisService() from core/accessibility.
    return false
}
