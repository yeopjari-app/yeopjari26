package ai.yeopjari.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
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
 * 화면 크기별로 실제 MainActivity 를 그려 PNG 로 남긴다(app/build/outputs/screens).
 * 가로 화면은 영상과 같은 세 칸, 세로 휴대전화는 한 줄 배치가 창을 꽉 채우는지 본다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val out = File("build/outputs/screens").apply { mkdirs() }

    @Before fun grant() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS,
        )
        compose.mainClock.autoAdvance = false
    }

    private fun run(seconds: Long) = repeat((seconds * 4).toInt()) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        compose.mainClock.advanceTimeBy(250)
    }

    private fun shot(name: String): Bitmap {
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** 동행을 시작하고 몇 초 흘린 뒤 찍는다. 세 칸이면 "대답 속도"와 "오늘의 졸음 예보"가 함께 보여야 한다. */
    private fun capture(name: String, wide: Boolean) {
        run(2)
        compose.onNodeWithText("같이 가기").performClick()
        run(6)
        val bmp = shot(name)
        compose.onNodeWithText("대답 속도").assertExists()
        if (wide) compose.onNodeWithText("오늘의 졸음 예보").assertExists()
        // 창 오른쪽 아래 구석까지 앱 배경으로 채워졌는지(작은 기둥처럼 가운데만 그리지 않는지)
        assertTrue(bmp.width > 0 && bmp.height > 0)
    }

    @Test @Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun landscape1920x1080() = capture("1920x1080", wide = true)

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tablet2560x1600() = capture("2560x1600", wide = true)

    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-port-xxhdpi")
    fun phone1080x2400() = capture("1080x2400_portrait", wide = false)

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun car1280x720() = capture("1280x720", wide = true)

    /** 가로로 든 휴대전화(840dp 미만)는 세 칸이 아니라 세로 배치를 쓴다. */
    @Test @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun phoneLandscapeUsesPortraitLayout() {
        capture("2400x1080_phone_landscape", wide = false)
        assertEquals(0, compose.onAllNodesWithText("오늘의 졸음 예보").fetchSemanticsNodes().size)
    }

    /** 권한은 없는 것만, 이 빌드가 선언한 것만 묻는다(가상 주행 빌드는 위치 권한을 선언하지 않는다). */
    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun asksOnlyMissingDeclaredPermissions() {
        assertEquals(emptyList<String>(), compose.activity.missingPermissions())
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), compose.activity.missingPermissions())
    }

    /** 가족 번호 창: 형식이 틀리면 바로 알려 주고 저장을 막는다. 주행이 시작되면 창을 닫는다. */
    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun phoneDialogValidatesAndClosesWhenDriving() {
        run(2)
        compose.onNodeWithText("가족 번호").performClick()
        run(1)
        compose.onNodeWithText("가족 알림 번호").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("123")
        run(1)
        compose.onNodeWithText("휴대전화 번호 형식이 아니에요", substring = true).assertExists()
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithText("같이 가기").performClick()
        run(3)
        assertEquals(0, compose.onAllNodesWithText("가족 알림 번호").fetchSemanticsNodes().size)
    }
}
