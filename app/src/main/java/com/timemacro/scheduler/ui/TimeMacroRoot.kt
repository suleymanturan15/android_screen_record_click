package com.timemacro.scheduler.ui

import androidx.compose.runtime.Composable
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.ui.onboarding.OnboardingScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.timemacro.scheduler.viewmodel.OnboardingViewModel
import com.timemacro.scheduler.viewmodel.OnboardingViewModelFactory

@Composable
fun TimeMacroRoot(
    container: AppContainer,
    pendingMainRoute: String? = null,
    onPendingMainRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModelFactory(container.userPreferences))
    val seen by vm.onboardingSeen.collectAsState()

    NavHost(
        navController = navController,
        startDestination = if (seen) RootRoutes.Main else RootRoutes.Onboarding,
    ) {
        composable(RootRoutes.Onboarding) {
            OnboardingScreen(
                onComplete = {
                    vm.setOnboardingSeen(true)
                    navController.navigate(RootRoutes.Main) {
                        popUpTo(RootRoutes.Onboarding) { inclusive = true }
                    }
                },
            )
        }
        composable(RootRoutes.Main) {
            MainScaffold(
                container = container,
                openRoute = pendingMainRoute,
                onOpenRouteConsumed = onPendingMainRouteConsumed,
            )
        }
    }
}

private object RootRoutes {
    const val Onboarding = "onboarding"
    const val Main = "main"
}

