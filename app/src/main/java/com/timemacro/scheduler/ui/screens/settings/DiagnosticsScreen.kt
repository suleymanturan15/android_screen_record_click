package com.timemacro.scheduler.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemacro.scheduler.AppContainer
import android.widget.Toast

@Composable
fun DiagnosticsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val snap by container.accessibilityStateRepository.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)

        Text("enabledInSettings: ${snap.enabledInSettings}")
        Text("runtimeConnected: ${snap.runtimeConnected}")
        Text("runtimeUiState: ${snap.runtimeUiState}")
        Text("lastHeartbeat(uptimeMs): ${snap.lastHeartbeatUptimeMs ?: "—"}")
        Text("lastConnected(wallMs): ${snap.lastConnectedAtWallMs ?: "—"}")

        Text("manufacturer: ${snap.manufacturer}")
        Text("isXiaomiFamily: ${snap.isXiaomiFamily}")
        Text("isMIUI: ${snap.isMiui}")

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Clear saved overlay position so it defaults to bottom-right again.
                context.getSharedPreferences("overlay_stop_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
                Toast.makeText(context, "Overlay position reset (will reopen at bottom-right)", Toast.LENGTH_SHORT).show()
            },
        ) { Text("Reset overlay position") }

        Button(modifier = Modifier.fillMaxWidth(), onClick = onBack) { Text("Back") }
    }
}

