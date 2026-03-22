package com.timemacro.scheduler.ui.screens.macros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.timemacro.scheduler.core.macro.MacroPayload
import com.timemacro.scheduler.domain.model.Macro
import com.timemacro.scheduler.domain.model.MacroExportFile
import com.timemacro.scheduler.viewmodel.MacroViewModel
import com.timemacro.scheduler.viewmodel.MacroViewModelFactory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.Instant
import java.util.UUID
import android.widget.Toast
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import kotlin.math.abs

@Composable
fun MacrosScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onNewMacro: () -> Unit,
    onMacroSelected: (macroId: String) -> Unit,
) {
    val vm: MacroViewModel = viewModel(factory = MacroViewModelFactory(container.macroRepository))
    val macros by vm.macros.collectAsStateWithLifecycle()
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            container.appScope.launch {
                runCatching {
                    val text =
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: error("Could not read file")
                    val parsed = MacroJsonCodec.json.decodeFromString<MacroExportFile>(text)
                    if (parsed.schemaVersion != 1) error("Unsupported export version: ${parsed.schemaVersion}")

                    val payload = parsed.macro.payload
                    val actionsJson = MacroJsonCodec.json.encodeToString(payload)

                    // Device metrics mismatch warning (suggest re-record).
                    val (curW, curH, curRot) = getRealDisplayInfo(context)
                    val recW = parsed.macro.meta?.screenWidth ?: payload.recordScreenW
                    val recH = parsed.macro.meta?.screenHeight ?: payload.recordScreenH
                    val recRot = parsed.macro.meta?.rotation ?: payload.recordRotation
                    val mismatchWarning =
                        buildString {
                            if (recRot != null && recRot != curRot) append("rotation ")
                            if (recW != null && recH != null && recW > 0 && recH > 0) {
                                val recAspect = recW.toFloat() / recH.toFloat()
                                val curAspect = curW.toFloat() / curH.toFloat()
                                if (abs(recAspect - curAspect) > 0.03f) append("screenSize ")
                            }
                        }.trim().ifBlank { null }

                    // Name collision handling.
                    val baseName = parsed.macro.name.trim().ifBlank { "Macro" }
                    val existing = macros.map { it.name }.toSet()
                    var candidate = baseName
                    if (candidate in existing) {
                        candidate = "$baseName (imported)"
                        var idx = 2
                        while (candidate in existing) {
                            candidate = "$baseName (imported $idx)"
                            idx++
                        }
                    }

                    val id = UUID.randomUUID().toString()
                    val macro =
                        Macro(
                            id = id,
                            name = candidate,
                            createdAt = Instant.now(),
                            recordDurationMs = parsed.macro.durationMs,
                            actionsJson = actionsJson,
                            isEnabled = true,
                        )
                    container.macroRepository.upsert(macro)
                    container.macroLogRepository.insert(
                        com.timemacro.scheduler.domain.model.MacroLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = Instant.now(),
                            macroId = id,
                            macroNameSnapshot = candidate,
                            source = "IMPORT",
                            status = "SUCCESS",
                            message = mismatchWarning?.let { "Warning: device metrics differ ($it). Re-record recommended." },
                            durationMs = null,
                            steps = payload.actions.size,
                        ),
                    )
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val msg =
                            if (mismatchWarning == null) "Imported: $candidate"
                            else "Imported: $candidate (Warning: screen differs, re-record recommended)"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    // Optionally open detail.
                    withContext(kotlinx.coroutines.Dispatchers.Main) { onMacroSelected(id) }
                }.onFailure {
                    container.macroLogRepository.insert(
                        com.timemacro.scheduler.domain.model.MacroLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = Instant.now(),
                            macroId = null,
                            macroNameSnapshot = "—",
                            source = "IMPORT",
                            status = "FAILED",
                            message = it.message ?: it::class.java.simpleName,
                            durationMs = null,
                            steps = null,
                        ),
                    )
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    if (deleteTargetId != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("Delete macro?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val id = deleteTargetId
                        deleteTargetId = null
                        if (id != null) vm.deleteMacro(id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { deleteTargetId = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Macros", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
                    Button(onClick = onNewMacro) { Text("New Macro (Record)") }
                }
            }
        }

        items(macros, key = { it.id }) { macro ->
            val actionsCount =
                runCatching { MacroJsonCodec.json.decodeFromString<MacroPayload>(macro.actionsJson).actions.size }
                    .getOrDefault(0)
            val mm = (macro.recordDurationMs / 60_000)
            val ss = (macro.recordDurationMs / 1000) % 60
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Only the left side is clickable (so toggling doesn't open details).
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onMacroSelected(macro.id) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = macro.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "%02d:%02d • %d".format(mm, ss, actionsCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = macro.isEnabled,
                        onCheckedChange = { enabled -> vm.setMacroEnabled(macro.id, enabled) },
                    )
                    IconButton(onClick = { deleteTargetId = macro.id }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete macro")
                    }
                }
            }
        }

        if (macros.isEmpty()) {
            item { Text("No macros yet. Create a placeholder macro to test scheduling UI.") }
        }
    }
}

private fun getRealDisplayInfo(context: Context): Triple<Int, Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    val display = wm.defaultDisplay
    val p = Point()
    @Suppress("DEPRECATION")
    display.getRealSize(p)
    val rotation = display.rotation
    return Triple(p.x, p.y, rotation)
}

