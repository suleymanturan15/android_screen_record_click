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
    val enabledFlag =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    if (!enabledFlag) return false

    val enabledServices = enabledAccessibilityServicesRaw(context) ?: return false

    val cn = ComponentName(context, MacroAccessibilityService::class.java)
    val full = cn.flattenToString() // package/class
    val short = cn.flattenToShortString() // package/.ShortClass
    return enabledServices
        .split(':')
        .any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
}

