package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DriverIntentTest {

    @Test
    fun `피곤하다는 말은 휴식 요청`() {
        assertEquals(DriverIntent.TIRED, detectIntent("좀 피곤하네"))
        assertEquals(DriverIntent.TIRED, detectIntent("졸려"))
        assertEquals(DriverIntent.TIRED, detectIntent("쉬고 싶어"))
        assertEquals(DriverIntent.TIRED, detectIntent("좀 졸린데"))
    }

    @Test
    fun `조용히 해 달라는 말`() {
        assertEquals(DriverIntent.QUIET, detectIntent("조용히 해 줘"))
        assertEquals(DriverIntent.QUIET, detectIntent("이제 그만 말해"))
    }

    @Test
    fun `둘 다 있으면 피곤함이 먼저`() {
        assertEquals(DriverIntent.TIRED, detectIntent("피곤하니까 조용히 해"))
    }

    @Test
    fun `재밌는 얘기를 청하는 말`() {
        assertEquals(DriverIntent.STORY, detectIntent("재밌는 얘기 해 줘"))
        assertEquals(DriverIntent.STORY, detectIntent("아 심심해"))
        assertEquals(DriverIntent.TIRED, detectIntent("졸린데 재밌는 얘기 해 줘"))
    }

    @Test
    fun `평범한 대답`() {
        assertEquals(DriverIntent.NONE, detectIntent("호두과자요"))
        assertEquals(DriverIntent.NONE, detectIntent(null))
    }
}
