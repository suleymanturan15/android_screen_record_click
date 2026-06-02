package com.timemacro.scheduler.ui.screens.macros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.core.macro.MacroJsonCodec
import com.timemacro.scheduler.core.macro.MacroPlaybackStateHolder
import com.timemacro.scheduler.core.macro.MacroPayload
import com.timemacro.scheduler.domain.macro.RunContext
import com.timemacro.scheduler.domain.model.MacroLog
import com.timemacro.scheduler.domain.model.MacroExportFile
import com.timemacro.scheduler.viewmodel.MacroViewModel
import com.timemacro.scheduler.viewmodel.MacroViewModelFactory
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.format.DateTimeFormatter
import android.app.Activity
import android.content.Context
import com.timemacro.scheduler.core.accessibility.AccessibilityRuntimeUiState
import com.timemacro.scheduler.service.keepalive.KeepAliveService

@Composable
fun MacroDetailScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    macroId: String,
    onDeleted: () -> Unit,
) {
    val vm: MacroViewModel = viewModel(factory = MacroViewModelFactory(container.macroRepository))
    val macros by vm.macros.collectAsStateWithLifecycle()
    val snap by container.accessibilityStateRepository.snapshot.collectAsStateWithLifecycle()
    val accessibilityEnabledInSettings = snap.enabledInSettings
    val accessibilityConnected = snap.runtimeConnected
    val runtimeUiState = snap.runtimeUiState
    val context = LocalContext.current
    var playbackStatus by remember { mutableStateOf<String?>(null) }
    val playbackState by MacroPlaybackStateHolder.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val macro = macros.firstOrNull { it.id == macroId }
    val actionsCount =
        remember(macro?.actionsJson) {
            if (macro == null) 0
            else runCatching { MacroJsonCodec.json.decodeFromString<MacroPayload>(macro.actionsJson).actions.size }.getOrDefault(0)
        }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete macro?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        container.appScope.launch {
                            container.macroRepository.delete(macroId)
                            onDeleted()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Macro Detail", style = MaterialTheme.typography.headlineSmall)

        if (macro == null) {
            Text("Macro not found (it may have been deleted).")
            return@Column
        }

        Text("Name: ${macro.name}")
        val mm = (macro.recordDurationMs / 60_000)
        val ss = (macro.recordDurationMs / 1000) % 60
        Text("Duration: %02d:%02d".format(mm, ss))
        Text("Actions: $actionsCount")
        Text("Created: ${macro.createdAt}")

        val exportLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                container.appScope.launch {
                    runCatching {
                        val payload = MacroJsonCodec.json.decodeFromString<MacroPayload>(macro.actionsJson)
                        val export =
                            MacroExportFile(
                                schemaVersion = 1,
                                exportedAt = Instant.now().toString(),
                                macro =
                                    MacroExportFile.ExportedMacro(
                                        id = macro.id,
                                        name = macro.name,
                                        durationMs = macro.recordDurationMs,
                                        isEnabled = macro.isEnabled,
                                        payload = payload,
                                        meta =
                                            MacroExportFile.Meta(
                                                screenWidth = payload.recordScreenW,
                                                screenHeight = payload.recordScreenH,
                                                rotation = payload.recordRotation,
                                                densityDpi = payload.recordDensityDpi,
                                                deviceModel = android.os.Build.MODEL,
                                                appVersion = "debug",
                                            ),
                                    ),
                            )
                        val json = MacroJsonCodec.json.encodeToString(export)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open output stream")
                        container.macroLogRepository.insert(
                            MacroLog(
                                id = UUID.randomUUID().toString(),
                                timestamp = Instant.now(),
                                macroId = macro.id,
                                macroNameSnapshot = macro.name,
                                source = "EXPORT",
                                status = "SUCCESS",
                                message = "Exported",
                                durationMs = null,
                                steps = actionsCount,
                            ),
                        )
                        Toast.makeText(context, "Exported", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        container.macroLogRepository.insert(
                            MacroLog(
                                id = UUID.randomUUID().toString(),
                                timestamp = Instant.now(),
                                macroId = macro.id,
                                macroNameSnapshot = macro.name,
                                source = "EXPORT",
                                status = "FAILED",
                                message = it.message ?: it::class.java.simpleName,
                                durationMs = null,
                                steps = actionsCount,
                            ),
                        )
                        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Start test immediately (no overlay controls).
                if (!macro.isEnabled) {
                    Toast.makeText(context, "Macro is disabled", Toast.LENGTH_SHORT).show()
                    container.appScope.launch {
                        container.macroLogRepository.insert(
                            MacroLog(
                                id = UUID.randomUUID().toString(),
                                timestamp = Instant.now(),
                                macroId = macro.id,
                                macroNameSnapshot = macro.name,
                                source = "PLAYBACK",
                                status = "SKIPPED",
                                message = "Macro disabled",
                                durationMs = 0L,
                                steps = null,
                            ),
                        )
                    }
                    return@Button
                }

                val startMs = System.currentTimeMillis()
                // UI-thread operations must happen on main to avoid ANR on some OEMs (MIUI).
                KeepAliveService.start(context, token = "playback:$macroId", label = "Playback")
                runCatching { context.findActivity()?.moveTaskToBack(true) }

                container.appScope.launch {
                    val startedAt = Instant.now()
                    container.macroLogRepository.insert(
                        MacroLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = startedAt,
                            macroId = macro.id,
                            macroNameSnapshot = macro.name,
                            source = "PLAYBACK",
                            status = "STARTED",
                            message = null,
                            durationMs = null,
                            steps = actionsCount,
                        ),
                    )
                    val statusAndError =
                        runCatching {
                            container.macroRunner.runMacro(
                                macroId = macroId,
                                runContext = RunContext(taskId = "MANUAL_TEST", scheduledTimeEpochMs = startMs, trigger = "TEST_NOW"),
                            )
                        }.fold(
                            onSuccess = { "SUCCESS" to null },
                            onFailure = {
                                when {
                                    it is CancellationException -> "CANCELLED" to (it.message ?: "Cancelled")
                                    it.message?.contains("Accessibility service not connected", ignoreCase = true) == true ->
                                        "PERMISSION_ERROR" to "Accessibility service not connected"
                                    it.message?.contains("ACCESSIBILITY_DISCONNECTED", ignoreCase = true) == true ->
                                        "FAILED" to "ACCESSIBILITY_DISCONNECTED"
                                    else -> "FAILED" to (it.message ?: it::class.java.simpleName)
                                }
                            },
                        )

                    val endMs = System.currentTimeMillis()
                    KeepAliveService.stop(context, token = "playback:$macroId")
                    container.macroLogRepository.insert(
                        MacroLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = Instant.ofEpochMilli(endMs),
                            macroId = macro.id,
                            macroNameSnapshot = macro.name,
                            source = "PLAYBACK",
                            status = statusAndError.first,
                            message = statusAndError.second,
                            durationMs = (endMs - startMs).coerceAtLeast(0L),
                            steps = actionsCount,
                        ),
                    )
                }
            },
            enabled = accessibilityConnected && actionsCount > 0,
        ) {
            Text("TEST NOW")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val safe = macro.name.ifBlank { "macro" }.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
                val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now())
                exportLauncher.launch("${safe}_$ts.json")
            },
        ) {
            Text("Export")
        }

        if (actionsCount <= 0) {
            Text("No actions recorded. Enable Accessibility and record taps.")
        }

        if (!accessibilityConnected) {
            val msg =
                if (!accessibilityEnabledInSettings) {
                    "Accessibility is OFF in settings. Enable it to run playback."
                } else if (runtimeUiState == AccessibilityRuntimeUiState.CONNECTING) {
                    "Accessibility enabled. Connecting…"
                } else {
                    "Disconnected by system (MIUI). Open Settings → Fix on MIUI."
                }
            Text(msg, style = MaterialTheme.typography.bodyMedium)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { Text("Open Accessibility Settings") }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Cancels the current playback run (if any) and leaves overlay in place (user can also cancel there).
                container.appScope.launch {
                    container.macroRunner.stopCurrentRun("Cancelled by user")
                }
            },
            enabled = playbackState.status == com.timemacro.scheduler.core.macro.MacroPlaybackState.Status.RUNNING,
        ) {
            Text("Cancel Playback")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { confirmDelete = true },
        ) {
            Text("Delete Macro")
        }

        // ─────────────────────────────────────────────────────────────────────
        // Live Test Panel — visible while a TEST NOW / scheduled run is RUNNING.
        // Shows step counter, last tap coords, mode, speed, elapsed time.
        // Tick is local to this Composable; the playback state itself updates via
        // MacroPlaybackStateHolder.tap()/progress() called from AccessibilityService.
        // ─────────────────────────────────────────────────────────────────────
        if (playbackState.status != com.timemacro.scheduler.core.macro.MacroPlaybackState.Status.IDLE) {
            val isRunning = playbackState.status == com.timemacro.scheduler.core.macro.MacroPlaybackState.Status.RUNNING
            var nowTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(isRunning) {
                while (isRunning) {
                    kotlinx.coroutines.delay(250L)
                    nowTickMs = System.currentTimeMillis()
                }
            }
            val playbackSpeedPercent by container.userPreferences.playbackSpeedPercent.collectAsStateWithLifecycle(initialValue = 100)
            val elapsedMs = playbackState.startedAtEpochMs?.let { (nowTickMs - it).coerceAtLeast(0L) } ?: 0L
            val speedX = playbackSpeedPercent / 100.0
            val totalSteps = playbackState.totalActions.coerceAtLeast(1)
            val frac = (playbackState.currentIndex.toDouble() / totalSteps.toDouble()).coerceIn(0.0, 1.0)
            val remainingMs = if (frac > 0.05) ((elapsedMs / frac) - elapsedMs).toLong().coerceAtLeast(0L) else 0L

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Canlı Test Paneli — ${playbackState.status}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Adım", fontWeight = FontWeight.Medium)
                        Text("${playbackState.currentIndex} / ${playbackState.totalActions}")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("X", fontWeight = FontWeight.Medium)
                        Text(playbackState.lastTapX?.toString() ?: "—")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Y", fontWeight = FontWeight.Medium)
                        Text(playbackState.lastTapY?.toString() ?: "—")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mode", fontWeight = FontWeight.Medium)
                        Text(playbackState.lastTapMode ?: "—")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Hız", fontWeight = FontWeight.Medium)
                        Text(
                            if (speedX == speedX.toInt().toDouble()) "${speedX.toInt()}x"
                            else "%.2fx".format(speedX),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Geçen süre", fontWeight = FontWeight.Medium)
                        Text(formatElapsed(elapsedMs))
                    }
                    if (isRunning && remainingMs > 0L) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kalan (tahmini)", fontWeight = FontWeight.Medium)
                            Text(formatElapsed(remainingMs))
                        }
                    }
                    if (!playbackState.errorMessage.isNullOrBlank()) {
                        Text("Hata: ${playbackState.errorMessage}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (playbackStatus != null) {
            Text("Status: $playbackStatus")
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mm = totalSec / 60L
    val ss = totalSec % 60L
    return "%02d:%02d".format(mm, ss)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

