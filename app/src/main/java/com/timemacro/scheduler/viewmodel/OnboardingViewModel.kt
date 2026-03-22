package com.timemacro.scheduler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timemacro.scheduler.data.prefs.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    val onboardingSeen: StateFlow<Boolean> =
        userPreferences.onboardingSeen
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setOnboardingSeen(seen: Boolean) {
        viewModelScope.launch {
            userPreferences.setOnboardingSeen(seen)
        }
    }
}

class OnboardingViewModelFactory(
    private val userPreferences: UserPreferences,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(userPreferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

