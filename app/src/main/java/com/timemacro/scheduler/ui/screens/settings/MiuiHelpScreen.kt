package com.timemacro.scheduler.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun MiuiHelpScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fix on MIUI (Xiaomi/Redmi)", style = MaterialTheme.typography.headlineSmall)

        Text(
            text =
                "Symptom: Accessibility is ON in settings, but TimeMacro shows runtime DISCONNECTED.\n\n" +
                    "Do these steps (MIUI may kill Accessibility services aggressively):\n" +
                    "1) Open Accessibility settings → toggle TimeMacro OFF then ON.\n" +
                    "2) Enable Auto-start for TimeMacro.\n" +
                    "3) Set Battery saver to “No restrictions” for TimeMacro.\n" +
                    "4) Lock the app in Recents (swipe down / lock icon).\n" +
                    "5) Disable MIUI battery optimizations for TimeMacro (where available).",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openAccessibilitySettings(context) },
        ) {
            Text("Open Accessibility Settings")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openMiuiAutostartSettingsBestEffort(context) },
        ) {
            Text("Open MIUI Auto-start (best effort)")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openAppDetails(context) },
        ) {
            Text("Open App details (Battery/Permissions)")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openBatteryOptimizationSettings(context) },
        ) {
            Text("Battery optimization settings")
        }

        if (Build.VERSION.SDK_INT >= 23) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { requestIgnoreBatteryOptimizations(context) },
            ) {
                Text("Request ignore battery optimizations")
            }
        }

        Button(onClick = onBack) { Text("Back") }
    }
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openAppDetails(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openBatteryOptimizationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(intent)
}

private fun openMiuiAutostartSettingsBestEffort(context: Context) {
    val intents =
        listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            // Some MIUI versions:
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                ),
            ).putExtra("extra_pkgname", context.packageName),
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            ).putExtra("package_name", context.packageName).putExtra("package_label", "TimeMacro"),
        )

    val launched =
        intents.firstOrNull { i ->
            runCatching { context.packageManager.resolveActivity(i, 0) != null }.getOrDefault(false)
        }

    if (launched != null) {
        context.startActivity(launched.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }

    // Fallback to app details if MIUI components are not found.
    openAppDetails(context)
}

