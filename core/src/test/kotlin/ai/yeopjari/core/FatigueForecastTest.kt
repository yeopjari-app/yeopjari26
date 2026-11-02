package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FatigueForecastTest {
    private val wake7 = 7 * 60
    private val depart2357 = 23 * 60 + 57

    @Test
    fun `5시간 자고 밤 11시 57분에 떠나면 새벽 2시 10분쯤 위험해진다`() {
        val fc = FatigueForecast.forecast(5.0, wake7, depart2357, 300)
        val risk = fc.riskAt!!
        assertEquals("새벽 2시 10분", FatigueForecast.spokenClock(depart2357 + risk))
        assertTrue(fc.peak.kss in 8.0..9.0)
        assertTrue(fc.peak.minute > risk)                      // 가장 졸린 때는 위험이 시작된 뒤(새벽 4~5시 무렵)
    }

    @Test
    fun `잠을 충분히 자면 같은 시간에 떠나도 위험 구간이 오지 않는다`() {
        val fc = FatigueForecast.forecast(8.0, wake7, depart2357, 300)
        assertNull(fc.riskAt)
        assertNotNull(fc.cautionAt)                             // 새벽 운전은 그래도 졸리다
    }

    @Test
    fun `잠이 짧을수록 졸음이 더 일찍 온다`() {
        val short = FatigueForecast.forecast(4.0, wake7, depart2357, 300).cautionAt!!
        val long = FatigueForecast.forecast(8.0, wake7, depart2357, 300).cautionAt!!
        assertTrue(short < long)
    }

    @Test
    fun `낮 운전은 오후 처짐 말고는 졸리지 않다`() {
        val fc = FatigueForecast.forecast(7.5, wake7, 10 * 60, 180)
        assertNull(fc.cautionAt)
    }

    @Test
    fun `잠 시간을 모르면 7시간으로 가정했다고 표시한다`() {
        assertTrue(FatigueForecast.forecast(null, wake7, depart2357, 120).assumedSleep)
    }

    @Test
    fun `예보 위험보다 늦은 휴식 약속은 30분 앞당기고, 이미 이르면 그대로 둔다`() {
        val planner = RestPlanner()
        val fc = FatigueForecast.forecast(5.0, wake7, depart2357, 300)       // 위험 135분 뒤
        val plan = planner.plan(SleepBand.SHORT, 23, null)!!                  // 기본 90분
        assertEquals(90, planner.withForecast(plan, fc).firstRestMin)
        assertEquals(135, planner.withForecast(plan, fc).riskAt)
        val late = RestPlanner.Plan(firstRestMin = 120, vulnerable = false)
        assertEquals(105, planner.withForecast(late, fc).firstRestMin)      // 135 − 30 → 15분 단위로 내림
    }

    @Test
    fun `출발 안내에 예보한 위험 시각을 말한다`() {
        val planner = RestPlanner()
        val plan = planner.withForecast(planner.plan(SleepBand.SHORT, 23, null)!!, FatigueForecast.forecast(5.0, wake7, depart2357, 300))
        val line = restPlanLine(SleepBand.SHORT, plan, depart2357)
        assertTrue(line, line.startsWith("조금 부족하게 주무셨네요. 새벽 2시 10분쯤부터 졸음이 심해지고, "))
        assertTrue(line, line.endsWith("쯤 가장 졸릴 거예요. 그 전에, 한 시간 반쯤 뒤에 한 번 쉬어 가요. 그때 가까운 휴게소를 찾아 드릴게요."))
    }

    @Test
    fun `시각을 10분 단위로 자연스럽게 말한다`() {
        assertEquals("새벽 2시 10분", FatigueForecast.spokenClock(2 * 60 + 12))
        assertEquals("밤 12시", FatigueForecast.spokenClock(23 * 60 + 57))
        assertEquals("오후 3시 30분", FatigueForecast.spokenClock(15 * 60 + 28))
        assertEquals("새벽 1시", FatigueForecast.spokenClock(24 * 60 + 60))
    }

    @Test
    fun `위험이 약속한 휴식보다 먼저 오면 그 전에가 아니라 바로 쉬자고 한다`() {
        val plan = RestPlanner.Plan(firstRestMin = 45, vulnerable = true, riskAt = 20, peakAt = 120)
        val line = restPlanLine(SleepBand.VERY_SHORT, plan, depart2357)
        assertTrue(line, !line.contains("그 전에"))
        assertTrue(line, line.contains("바로"))
    }

    @Test
    fun `가장 졸릴 때는 예보의 정점 시각으로 말한다`() {
        val planner = RestPlanner()
        val fc = FatigueForecast.forecast(5.0, wake7, depart2357, 300)
        val plan = planner.plan(SleepBand.SHORT, 23, null, fc)!!
        assertEquals(fc.peak.minute, plan.peakAt)
        val line = restPlanLine(SleepBand.SHORT, plan, depart2357)
        assertTrue(line, line.contains("${FatigueForecast.spokenClock(depart2357 + fc.peak.minute)}쯤 가장 졸릴"))
        assertTrue(line, line.contains("새벽 2시 10분쯤부터"))
    }

    @Test
    fun `짧은 길이어도 도착 전에 예보 위험이 오면 계획한다`() {
        val planner = RestPlanner()
        // 거의 못 자고 새벽 3시에 출발하는 60km(40분) 길: 기본 계획은 쉬기 전에 도착하지만 예보상 위험이 먼저 온다.
        val fc = FatigueForecast.forecast(2.0, wake7, 3 * 60, 40)
        val risk = fc.riskAt!!
        assertTrue(risk < 40)
        assertNull(planner.plan(SleepBand.VERY_SHORT, 3, 60.0))
        val p = planner.plan(SleepBand.VERY_SHORT, 3, 60.0, fc)
        assertNotNull(p)
        assertTrue(p!!.firstRestMin <= risk)
        assertTrue(p.restAfterRisk)
    }

    @Test
    fun `간격이 0이어도 멈추지 않는다`() {
        assertTrue(FatigueForecast.forecast(5.0, wake7, depart2357, 30, stepMin = 0).points.size > 1)
    }
}
