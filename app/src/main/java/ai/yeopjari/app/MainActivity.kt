package ai.yeopjari.app

import ai.yeopjari.app.ui.CopilotScreen
import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: CopilotViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshVoiceStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 열 때 한 번만, 아직 없는 권한만 묻는다(화면을 돌릴 때마다 다시 묻지 않는다).
        // 이 빌드의 매니페스트에 없는 권한(가상 주행 빌드의 위치 권한 등)은 묻지 않는다.
        if (savedInstanceState == null) {
            val missing = missingPermissions()
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                CopilotScreen(state = state, actions = viewModel)
            }
        }
    }

    /** 설정에서 권한을 켜거나 음성 엔진을 깔고 돌아오면 안내를 다시 맞춘다. */
    override fun onResume() {
        super.onResume()
        viewModel.refreshVoiceStatus()
    }

    internal fun missingPermissions(): List<String> {
        val declared = declaredPermissions()
        return WANTED.filter { it in declared && ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun declaredPermissions(): Set<String> {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions?.toSet().orEmpty()
    }

    private companion object {
        val WANTED = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
        )
    }
}
