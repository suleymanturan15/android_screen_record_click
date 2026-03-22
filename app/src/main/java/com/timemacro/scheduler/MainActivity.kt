package com.timemacro.scheduler

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.timemacro.scheduler.ui.TimeMacroRoot
import com.timemacro.scheduler.ui.theme.TimeMacroTheme
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.lifecycleScope
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private val pendingOpenRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val start = SystemClock.elapsedRealtime()
        val firstComposeDone = AtomicBoolean(false)

        splash.setKeepOnScreenCondition {
            val elapsed = SystemClock.elapsedRealtime() - start
            !firstComposeDone.get() && elapsed < 2000
        }

        super.onCreate(savedInstanceState)

        val container = (application as App).container
        pendingOpenRoute.value = resolveOpenRoute(intent)
        intent.getStringExtra(EXTRA_TOAST)?.let { msg ->
            if (msg.isNotBlank()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        container.accessibilityStateRepository.onAppForegrounded()

        lifecycleScope.launch {
            runCatching { container.userPreferences.onboardingSeen.first() }
                .onSuccess { seen -> Log.d("Onboarding", "seen=$seen") }
        }

        setContent {
            // Mark "loaded" as soon as we can compose at least once.
            LaunchedEffect(Unit) { firstComposeDone.set(true) }

            TimeMacroTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TimeMacroRoot(
                        container = container,
                        pendingMainRoute = pendingOpenRoute.value,
                        onPendingMainRouteConsumed = { pendingOpenRoute.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenRoute.value = resolveOpenRoute(intent)
        intent.getStringExtra(EXTRA_TOAST)?.let { msg ->
            if (msg.isNotBlank()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        val container = (application as App).container
        container.accessibilityStateRepository.onAppForegrounded()
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
        const val EXTRA_NAV_TARGET = "nav_target"
        const val EXTRA_MACRO_ID = "extra_macro_id"
        const val EXTRA_TOAST = "extra_toast"

        const val NAV_TARGET_MACRO_RECORD = "macro_record"
        const val NAV_TARGET_MACRO_DETAIL = "macro_detail"

        private fun resolveOpenRoute(intent: Intent?): String? {
            val navTarget = intent?.getStringExtra(EXTRA_NAV_TARGET)
            return when (navTarget) {
                NAV_TARGET_MACRO_RECORD -> com.timemacro.scheduler.ui.Routes.MacroRecord
                NAV_TARGET_MACRO_DETAIL -> {
                    val macroId = intent.getStringExtra(EXTRA_MACRO_ID) ?: return null
                    "${com.timemacro.scheduler.ui.Routes.MacroDetail}/$macroId"
                }
                else -> intent?.getStringExtra(EXTRA_OPEN_ROUTE)
            }
        }
    }
}

