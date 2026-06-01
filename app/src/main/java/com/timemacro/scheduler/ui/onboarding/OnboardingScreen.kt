package com.timemacro.scheduler.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.timemacro.scheduler.core.accessibility.isAccessibilityEnabledForThisService
import com.timemacro.scheduler.core.device.DeviceInfo

/**
 * Interactive onboarding: each row deep-links to the matching system settings page.
 * Auto-refreshes on resume so the user sees their progress without leaving the wizard.
 *
 * Cannot auto-grant permissions (Android security model); routes the user to the
 * right place with one tap.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    var notificationsOk by remember { mutableStateOf(false) }
    var accessibilityOk by remember { mutableStateOf(false) }
    var batteryOptOk by remember { mutableStateOf(false) }
    var exactAlarmOk by remember { mutableStateOf(false) }
    var overlayOk by remember { mutableStateOf(false) }

    fun refresh() {
        notificationsOk = isNotificationsEnabled(context)
        accessibilityOk = runCatching { isAccessibilityEnabledForThisService(context) }.getOrDefault(false)
        batteryOptOk = isIgnoringBatteryOptimizations(context)
        exactAlarmOk = isExactAlarmsAllowed(context)
        overlayOk = Settings.canDrawOverlays(context)
    }

    LaunchedEffect(Unit) { refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isXiaomi = DeviceInfo.isXiaomiFamily() || DeviceInfo.isMiui()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "TimeMacro Scheduler",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Makro kaydet (ekran + dokunma) ve programlanmış saatlerde otomatik çalıştır.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Gerekli izinler",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StepRow(
                        index = 1,
                        title = "Bildirim izni",
                        subtitle = "Programlı görev çalışırken bildirim göstermek için (Android 13+).",
                        isOk = notificationsOk,
                        onClick = { openAppNotificationSettings(context) },
                    )
                    StepRow(
                        index = 2,
                        title = "Accessibility servisi",
                        subtitle = "Makro tıklamalarını yakalamak ve oynatmak için zorunlu.",
                        isOk = accessibilityOk,
                        onClick = { openAccessibilitySettings(context) },
                    )
                    StepRow(
                        index = 3,
                        title = "Pil optimizasyonu kapat",
                        subtitle = "MIUI/HyperOS ve Android Doze altında servisin öldürülmemesi için ZORUNLU.",
                        isOk = batteryOptOk,
                        onClick = { requestIgnoreBatteryOptimizations(context) },
                    )
                    StepRow(
                        index = 4,
                        title = "Exact alarms (Android 12+)",
                        subtitle = "Programlı görevin tam zamanda tetiklenebilmesi için.",
                        isOk = exactAlarmOk,
                        onClick = { openExactAlarmSettings(context) },
                    )
                    StepRow(
                        index = 5,
                        title = "Overlay (opsiyonel)",
                        subtitle = "Kayıt sırasında ekran üstü STOP balonu için.",
                        isOk = overlayOk,
                        onClick = { openOverlaySettings(context) },
                        optional = true,
                    )
                }
            }

            if (isXiaomi) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Xiaomi / MIUI / HyperOS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Servisin sürekli çalışması için aşağıdakileri MANUEL kontrol et. " +
                                "OS bu ayarları uygulamaya programatik vermez.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ManualStepRow(
                            title = "Autostart aç",
                            subtitle = "Security → Permissions → Autostart → TimeMacro",
                            onClick = { openMiuiAutostartSettings(context) },
                        )
                        ManualStepRow(
                            title = "Battery saver: No restrictions",
                            subtitle = "Settings → Apps → TimeMacro → Battery → No restrictions",
                            onClick = { openAppDetails(context) },
                        )
                        ManualStepRow(
                            title = "Background activity: Unrestricted",
                            subtitle = "Settings → Apps → TimeMacro → Battery → Background activity",
                            onClick = { openAppDetails(context) },
                        )
                    }
                }
            }

            val criticalCount = listOf(notificationsOk, accessibilityOk, batteryOptOk, exactAlarmOk).count { it }
            Text(
                text = "Kritik 4 izinden $criticalCount / 4 verildi" +
                    if (criticalCount < 4) " — eksikleri vermeden Get Started kısa süre çalışır." else " ✅",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Button(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .navigationBarsPadding(),
                    onClick = onComplete,
                ) {
                    Text(if (criticalCount >= 3) "Get Started" else "Skip (önerilmez)")
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    subtitle: String,
    isOk: Boolean,
    onClick: () -> Unit,
    optional: Boolean = false,
) {
    val okColor = Color(0xFF2E7D32)
    val errColor = if (optional) Color(0xFFB37800) else Color(0xFFC62828)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (isOk) okColor else errColor,
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (isOk) "✓" else "$index",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (isOk) "Açık" else if (optional) "Atla" else "Ver",
            style = MaterialTheme.typography.labelMedium,
            color = if (isOk) okColor else errColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ManualStepRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(color = Color(0xFFB37800), shape = RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("M", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Aç",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB37800),
            fontWeight = FontWeight.Medium,
        )
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

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isExactAlarmsAllowed(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 31) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    } else {
        true
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

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= 23) {
        val direct =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val ok = runCatching { context.packageManager.resolveActivity(direct, 0) != null }.getOrDefault(false)
        if (ok) {
            context.startActivity(direct)
            return
        }
    }
    context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun openAppDetails(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openMiuiAutostartSettings(context: Context) {
    val candidates =
        listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
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
        candidates.firstOrNull { i ->
            runCatching { context.packageManager.resolveActivity(i, 0) != null }.getOrDefault(false)
        }
    if (launched != null) {
        context.startActivity(launched.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        openAppDetails(context)
    }
}
