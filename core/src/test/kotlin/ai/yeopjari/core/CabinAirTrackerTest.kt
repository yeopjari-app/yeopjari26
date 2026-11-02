package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinAirTrackerTest {
    private val min = 60_000L
    private fun snap(t: Long, recirc: Boolean, gear: Gear = Gear.DRIVE) = VehicleSnapshot(t, 100f, gear, recirculationOn = recirc)

    @Test
    fun `내기 순환이 30분 넘게 이어지면 한 번만 알린다`() {
        val c = CabinAirTracker()
        assertFalse(c.update(snap(0, true), hour = 10))
        assertFalse(c.update(snap(29 * min, true), hour = 10))
        assertTrue(c.update(snap(30 * min, true), hour = 10))
        assertFalse(c.update(snap(45 * min, true), hour = 10))
        assertEquals(45, c.minutes(45 * min))
    }

    @Test
    fun `새벽에는 20분, 외기로 바꾸면 처음부터 다시 잰다`() {
        val c = CabinAirTracker()
        c.update(snap(0, true), hour = 2)
        assertTrue(c.update(snap(20 * min, true), hour = 2))
        assertFalse(c.update(snap(21 * min, false), hour = 2))
        assertFalse(c.update(snap(22 * min, true), hour = 2))
        assertTrue(c.update(snap(42 * min, true), hour = 2))
    }

    @Test
    fun `정차 중에는 알리지 않고, 다시 달릴 때부터 잰다`() {
        val c = CabinAirTracker()
        c.update(snap(0, true), hour = 10)
        assertFalse(c.update(snap(40 * min, true, Gear.PARK), hour = 10))
        assertFalse(c.update(snap(41 * min, true), hour = 10))
        assertFalse(c.update(snap(70 * min, true), hour = 10))
        assertTrue(c.update(snap(71 * min, true), hour = 10))
    }
}
