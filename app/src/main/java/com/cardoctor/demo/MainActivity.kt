package com.cardoctor.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardoctor.demo.ui.DashboardScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DashboardScreen(
                state = state,
                onCycleNight = viewModel::cycleNight,
                onCycleWeather = viewModel::cycleWeather,
                onToggleHighway = viewModel::toggleHighway,
                onToggleLongDistance = viewModel::toggleLongDistance
            )
        }
    }
}
