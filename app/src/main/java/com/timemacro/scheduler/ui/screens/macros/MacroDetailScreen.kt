package com.timemacro.scheduler.ui.screens.macros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

        if (playbackState.status != com.timemacro.scheduler.core.macro.MacroPlaybackState.Status.IDLE) {
            Text("Playback: ${playbackState.status} • step ${playbackState.currentIndex}/${playbackState.totalActions}")
            if (!playbackState.errorMessage.isNullOrBlank()) {
                Text("Playback error: ${playbackState.errorMessage}")
            }
        }

        if (playbackStatus != null) {
            Text("Status: $playbackStatus")
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

