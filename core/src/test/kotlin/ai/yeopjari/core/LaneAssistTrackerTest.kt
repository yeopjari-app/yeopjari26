package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneAssistTrackerTest {
    private fun snap(t: Long, on: Boolean, intervene: Boolean = false) =
        VehicleSnapshot(t, 100f, Gear.DRIVE, laneAssistOn = on, laneIntervention = intervene)

    @Test
    fun `보조가 켜지면 조향 흔들림을 믿지 않고 꺼지면 다시 쓴다`() {
        val l = LaneAssistTracker()
        l.update(snap(0, on = false))
        assertTrue(l.steeringTrusted)
        l.update(snap(1_000, on = true))
        assertFalse(l.steeringTrusted)
        l.update(snap(2_000, on = false))
        assertTrue(l.steeringTrusted)
    }

    @Test
    fun `차로 이탈 개입은 시작되는 순간 한 번만 알린다`() {
        val l = LaneAssistTracker()
        assertFalse(l.update(snap(0, on = true)))
        assertTrue(l.update(snap(1_000, on = true, intervene = true)))
        assertFalse(l.update(snap(2_000, on = true, intervene = true)))
        assertFalse(l.update(snap(3_000, on = true)))
        assertTrue(l.update(snap(4_000, on = true, intervene = true)))
    }

    @Test
    fun `밤에 보조를 30분 넘게 켜고 달리면 확인을 더 자주 한다`() {
        val l = LaneAssistTracker()
        l.update(snap(0, on = true))
        assertEquals(1.0, l.intervalFactor(10 * 60_000L, hour = 2), 0.0)
        assertEquals(0.75, l.intervalFactor(31 * 60_000L, hour = 2), 0.0)
        assertEquals(1.0, l.intervalFactor(31 * 60_000L, hour = 14), 0.0)
        l.update(snap(32 * 60_000L, on = false))
        assertEquals(1.0, l.intervalFactor(33 * 60_000L, hour = 2), 0.0)
    }
}
