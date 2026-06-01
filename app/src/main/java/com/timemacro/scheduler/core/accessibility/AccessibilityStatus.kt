package com.timemacro.scheduler.core.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.timemacro.scheduler.service.accessibility.MacroAccessibilityService

fun enabledAccessibilityServicesRaw(context: Context): String? {
    return Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
}

fun isAccessibilityEnabledForThisService(context: Context): Boolean {
    // MIUI/HyperOS quirk: ACCESSIBILITY_ENABLED master flag can flip to 0 even when our
    // service is still listed in ENABLED_ACCESSIBILITY_SERVICES and still binds normally.
    // Previously we returned false on this transient state, which showed "Missing" on
    // Settings/Permissions even though the service was active. Treat the per-service list
    // as the source of truth and ignore the master flag.
    val enabledServices = enabledAccessibilityServicesRaw(context) ?: return false

    val cn = ComponentName(context, MacroAccessibilityService::class.java)
    val full = cn.flattenToString() // package/class
    val short = cn.flattenToShortString() // package/.ShortClass
    return enabledServices
        .split(':')
        .any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
}

