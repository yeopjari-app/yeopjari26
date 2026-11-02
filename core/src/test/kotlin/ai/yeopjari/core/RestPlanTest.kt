package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestPlanTest {
    @Test
    fun `숫자와 말로 한 잠 시간을 읽는다`() {
        assertEquals(SleepBand.ENOUGH, SleepAnswer.parse("일곱 시간쯤 잤어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("다섯 시간 반"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("6시간"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("네 시간밖에 못 잤어"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("어제 밤샜어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("잘 못 잤어"))
        assertEquals(SleepBand.ENOUGH, SleepAnswer.parse("푹 잤지"))
        assertEquals(SleepBand.UNKNOWN, SleepAnswer.parse("글쎄"))
        assertEquals(10.0, SleepAnswer.hours("열 시간")!!, 0.0)
        assertEquals(12.0, SleepAnswer.hours("열두 시간")!!, 0.0)
        assertEquals(6.0, SleepAnswer.hours("6~7시간")!!, 0.0)
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("일곱 시간 자려다 세 시간 잤어"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("서너 시간"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("푹 자지 못했어"))      // 부정한 좋은 잠("푹 … 못")은 짧은 잠
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("잘 못 자서"))
        assertEquals(SleepBand.UNKNOWN, SleepAnswer.parse("한 시간 전에 일어났어"))
        assertEquals(1.5, SleepAnswer.hours("한 시간 반")!!, 0.0)
    }

    @Test
    fun `잠이 짧을수록, 졸린 시간대일수록 첫 휴식을 앞당긴다`() {
        val p = RestPlanner()
        assertEquals(120, p.plan(SleepBand.ENOUGH, 10, null)!!.firstRestMin)
        assertEquals(90, p.plan(SleepBand.SHORT, 10, null)!!.firstRestMin)
        assertEquals(60, p.plan(SleepBand.VERY_SHORT, 10, null)!!.firstRestMin)
        val night = p.plan(SleepBand.ENOUGH, 1, null)!!
        assertEquals(90, night.firstRestMin)
        assertTrue(night.vulnerable)
        assertEquals(45, p.plan(SleepBand.VERY_SHORT, 2, null)!!.firstRestMin)
        assertEquals(75, p.plan(SleepBand.SHORT, 15, null)!!.firstRestMin)
    }

    @Test
    fun `쉬기 전에 도착하는 짧은 길이면 계획하지 않는다`() {
        val p = RestPlanner()
        assertNull(p.plan(SleepBand.ENOUGH, 10, 150.0))
        assertEquals(120, p.plan(SleepBand.ENOUGH, 10, 380.0)!!.firstRestMin)
    }

    @Test
    fun `휴식 시간을 자연스럽게 말한다`() {
        assertEquals("한 시간", spokenDuration(60))
        assertEquals("한 시간 반", spokenDuration(90))
        assertEquals("한 시간 15분", spokenDuration(75))
        assertEquals("두 시간", spokenDuration(120))
        assertEquals("45분", spokenDuration(45))
        assertEquals(
            "잠이 많이 짧았네요. 졸리기 쉬운 시간대라 45분쯤 뒤에 한 번 쉬어 가요. 그때 가까운 휴게소를 찾아 드릴게요.",
            restPlanLine(SleepBand.VERY_SHORT, RestPlanner().plan(SleepBand.VERY_SHORT, 2, null)!!),
        )
    }
}
