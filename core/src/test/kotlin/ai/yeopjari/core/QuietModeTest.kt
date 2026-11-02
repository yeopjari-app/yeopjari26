package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietModeTest {
    private val min = 60_000L

    @Test
    fun `낮에는 정해 둔 시간만큼 조용히 하고 끝나면 한 번 알린다`() {
        val quiet = QuietMode(normalMs = 20 * min, riskyMs = 10 * min)
        assertEquals(20 * min, quiet.start(nowMs = 0, hour = 14, drivingMs = 30 * min))
        assertTrue(quiet.active(19 * min))
        assertNull(quiet.poll(19 * min))
        assertEquals(QuietEnd.TIME_UP, quiet.poll(20 * min))
        assertNull(quiet.poll(21 * min))
        assertFalse(quiet.active(21 * min))
    }

    @Test
    fun `새벽이나 오래 운전한 뒤에는 짧게만 조용히 한다`() {
        val quiet = QuietMode(normalMs = 20 * min, riskyMs = 10 * min, longDriveMs = 120 * min)
        assertEquals(10 * min, quiet.start(nowMs = 0, hour = 0, drivingMs = 10 * min))
        assertEquals(10 * min, quiet.start(nowMs = 0, hour = 22, drivingMs = 150 * min))
    }

    @Test
    fun `조향 흔들림이 보이면 남은 시간과 관계없이 바로 끝낸다`() {
        val quiet = QuietMode(normalMs = 20 * min, riskyMs = 10 * min)
        quiet.start(nowMs = 0, hour = 22, drivingMs = 0)
        assertTrue(quiet.interrupt(QuietEnd.WEAVING))
        assertFalse(quiet.active(min))
        assertNull(quiet.poll(30 * min))
    }

    @Test
    fun `조용히 모드가 아닐 때의 신호는 깨운 것으로 치지 않는다`() {
        val quiet = QuietMode(normalMs = 20 * min, riskyMs = 10 * min)
        assertFalse(quiet.interrupt(QuietEnd.VEHICLE_WARNING))
    }
}
