package ai.yeopjari.app

import ai.yeopjari.app.ui.CopilotScreen
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: CopilotViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                CopilotScreen(
                    state = state,
                    onLaneChange = viewModel::laneChange,
                    onHardBrake = viewModel::hardBrake,
                    onTogglePassenger = viewModel::togglePassenger,
                    onVehicleWarning = viewModel::vehicleDrowsinessWarning,
                    onParkForRest = viewModel::parkForRest,
                    onResumeDriving = viewModel::resumeDriving,
                )
            }
        }
    }
}
