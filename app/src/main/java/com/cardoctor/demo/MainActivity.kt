package com.cardoctor.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardoctor.demo.ui.DashboardActions
import com.cardoctor.demo.ui.DashboardScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // F4/F7 need a position; everything else works without it.
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.filterKeys { it.contains("LOCATION") }.values.any { it }) viewModel.onLocationPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Location for F4/F7, microphone for voice questions; ask once for whatever is missing.
        val wanted = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) locationPermission.launch(wanted.toTypedArray())
        val actions = DashboardActions(
            onCycleNight = viewModel::cycleNight,
            onCycleWeather = viewModel::cycleWeather,
            onToggleHighway = viewModel::toggleHighway,
            onToggleLongDistance = viewModel::toggleLongDistance,
            onOpenDetail = viewModel::openDetail,
            onStartGuidance = viewModel::startGuidance,
            onOpenReport = viewModel::openReport,
            onExportReport = viewModel::exportReport,
            reportJson = viewModel::reportJson,
            onCycleDriving = viewModel::cycleDriving,
            onListen = viewModel::listen,
            onDismissVoice = viewModel::dismissVoice
        )
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DashboardScreen(state = state, actions = actions)
        }
    }
}
