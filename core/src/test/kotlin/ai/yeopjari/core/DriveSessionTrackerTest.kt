package ai.yeopjari.core

import ai.yeopjari.core.DriveSessionTracker.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSessionTrackerTest {

    private fun tracker(hour: Int = 21) = DriveSessionTracker(hourOf = { hour })

    /** [minutes] 분 동안 1분 간격으로 같은 상태를 넣고, 발생한 이벤트를 모은다. */
    private fun DriveSessionTracker.run(startMin: Long, minutes: Int, speed: Float, gear: Gear): List<DriveEvent> =
        (0..minutes).flatMap { update(VehicleSnapshot((startMin + it) * MINUTE, speed, gear)) }

    @Test
    fun `고속 주행 30분이 쌓이면 동행을 시작한다`() {
        val t = tracker()
        assertTrue(t.run(0, 29, 100f, Gear.DRIVE).isEmpty())
        assertEquals(listOf(DriveEvent.Activated), t.run(30, 1, 100f, Gear.DRIVE))
    }

    @Test
    fun `시내 저속 주행만으로는 시작하지 않는다`() {
        val t = tracker()
        t.run(0, 120, 40f, Gear.DRIVE)
        assertFalse(t.active)
        assertEquals(120 * MINUTE, t.drivingMs)
    }

    @Test
    fun `졸음 취약 시간대에는 15분 만에 시작한다`() {
        val t = tracker(hour = 2)
        assertTrue(t.run(0, 15, 100f, Gear.DRIVE).contains(DriveEvent.Activated))
    }

    @Test
    fun `10분 이상 정차하면 휴식으로 인정하고 다시 출발을 알린다`() {
        val t = tracker()
        t.run(0, 40, 100f, Gear.DRIVE)
        assertTrue(t.active)

        val parked = t.run(41, 12, 0f, Gear.PARK)
        assertEquals(listOf(DriveEvent.Parked, DriveEvent.RestCredited), parked)
        assertEquals(0, t.drivingMs)

        assertEquals(listOf(DriveEvent.ResumedAfterRest), t.run(54, 0, 30f, Gear.DRIVE))
    }

    @Test
    fun `짧은 정차는 휴식으로 인정하지 않는다`() {
        val t = tracker()
        t.run(0, 40, 100f, Gear.DRIVE)
        val events = t.run(41, 5, 0f, Gear.PARK) + t.run(47, 0, 30f, Gear.DRIVE)
        assertEquals(listOf(DriveEvent.Parked), events)
        assertTrue(t.drivingMs > 0)
    }
}
