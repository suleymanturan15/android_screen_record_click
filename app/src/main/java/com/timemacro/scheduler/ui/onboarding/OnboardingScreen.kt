package com.timemacro.scheduler.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "TimeMacro Scheduler",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Record macros (screen + touch) and run them automatically on schedules.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text("Setup checklist:")
            Text("1) Allow notifications (Android 13+) for foreground service status")
            Text("2) Enable Accessibility for macro playback")
            Text("3) (Recommended) Ignore battery optimizations for reliability")
            Text("4) (Optional) Overlay permission for floating controls")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Button(
                    modifier = Modifier
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    onClick = onComplete,
                ) {
                    Text("Get Started")
                }
            }
        }
    }
}

