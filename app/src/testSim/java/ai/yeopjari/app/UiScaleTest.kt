package ai.yeopjari.app

import ai.yeopjari.app.ui.layoutScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 배율: 늘 유한하고 0.7~3 사이, 좁은 가로 창은 세로 배치. */
class UiScaleTest {
    @Test fun wideWindowsUseThreeColumns() {
        assertTrue(layoutScale(1920f, 1080f).first)
        assertTrue(layoutScale(1280f, 720f).first)
        assertEquals(1.6f, layoutScale(1920f, 1080f).second, .01f)
    }

    @Test fun landscapePhoneUsesPortraitLayout() {
        assertFalse(layoutScale(800f, 360f).first)
        assertFalse(layoutScale(900f, 400f).first)                  // 넓어도 세 칸이 0.7배 아래로 줄면 세로 배치
    }

    @Test fun scaleIsAlwaysFiniteAndClamped() {
        for ((w, h) in listOf(0f to 0f, Float.POSITIVE_INFINITY to 800f, 360f to Float.NaN, 10_000f to 10_000f, 100f to 100f)) {
            val s = layoutScale(w, h).second
            assertTrue("$w×$h → $s", s.isFinite() && s in .7f..3f)
        }
    }
}
