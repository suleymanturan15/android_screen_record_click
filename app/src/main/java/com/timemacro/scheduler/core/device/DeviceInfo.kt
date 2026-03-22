package com.timemacro.scheduler.core.device

import android.os.Build

object DeviceInfo {
    /**
     * Explicit Xiaomi/MIUI detection (best-effort).
     */
    fun isXiaomiFamily(): Boolean {
        val man = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return man.contains("xiaomi") || man.contains("redmi") || man.contains("poco") ||
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    fun isMiui(): Boolean {
        // SystemProperties is hidden API; read via reflection (best-effort).
        val v1 = getSystemProperty("ro.miui.ui.version.name")
        val v2 = getSystemProperty("ro.miui.ui.version.code")
        val v3 = getSystemProperty("ro.miui.internal.storage")
        return !v1.isNullOrBlank() || !v2.isNullOrBlank() || !v3.isNullOrBlank()
    }

    fun manufacturer(): String = Build.MANUFACTURER ?: "unknown"
    fun model(): String = Build.MODEL ?: "unknown"

    private fun getSystemProperty(key: String): String? {
        return runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            (m.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

