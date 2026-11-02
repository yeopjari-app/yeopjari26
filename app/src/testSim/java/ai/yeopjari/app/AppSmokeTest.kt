package ai.yeopjari.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * 시연 빌드를 실제 MainActivity 로 띄워 전체 흐름의 버튼을 눌러 보고, 화면마다 스크린샷을 남긴다.
 * 에뮬레이터 없이 "앱이 뜨고, 버튼에 반응하고, 죽지 않는다"를 확인하는 스모크 테스트다.
 * 스크린샷: app/build/smoke 폴더의 PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val out = File("build/smoke").apply { mkdirs() }

    @Before fun grant() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS,
        )
        compose.mainClock.autoAdvance = false
    }

    /** 가상 시간을 흘린다: 코루틴 지연(메인 루퍼)과 화면 프레임을 함께 진행한다. */
    private fun run(seconds: Long) {
        repeat((seconds * 4).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.mainClock.advanceTimeBy(250)
        }
    }

    /** 화면 전체(액티비티 뷰)를 그대로 그려 PNG 로 남긴다. 무한 애니메이션이 있어도 기다리지 않는다. */
    private fun shot(name: String) {
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        run(1)
    }

    @Test fun fullDemoFlowRunsWithoutCrashing() {
        run(2)
        compose.onNodeWithText("같이 가기").assertExistsCompat()
        shot("01_start")

        tap("같이 가기")
        run(5)
        assertEquals("동행이 시작되면 '같이 가기'가 사라진다", 0, compose.onAllNodesWithText("같이 가기").fetchSemanticsNodes().size)
        shot("02_accompanying")

        tap("차선 변경")
        run(3)
        shot("03_lane_change_quiet")

        tap("차량 졸음 경고")
        run(8)
        shot("04_vehicle_warning")

        tap("휴식 정차")
        run(8)
        shot("05_rest_stop")

        tap("다시 출발")
        run(5)
        shot("06_resume")
        // 화면이 끝까지 살아 있는지
        compose.onNodeWithText("차선 변경").assertExistsCompat()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = apply { assertExists() }
}
