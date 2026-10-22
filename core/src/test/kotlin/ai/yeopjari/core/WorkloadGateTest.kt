package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkloadGateTest {
    private fun snap(t: Long, signal: Boolean = false, brake: Boolean = false, steer: Float = 0f) =
        VehicleSnapshot(t, 100f, Gear.DRIVE, turnSignalOn = signal, hardBraking = brake, steeringAngleDeg = steer)

    @Test
    fun `평소 직진 중에는 말을 건다`() {
        val gate = WorkloadGate()
        gate.update(snap(0))
        assertTrue(gate.canTalk(0))
    }

    @Test
    fun `차선 변경 중에는 조용하고 끝나고도 잠시 기다린다`() {
        val gate = WorkloadGate(cooldownMs = 10_000)
        assertEquals(setOf(Workload.LANE_CHANGE), gate.update(snap(0, signal = true)))
        assertFalse(gate.canTalk(0))

        gate.update(snap(3_000))
        assertFalse(gate.canTalk(5_000))
        assertTrue(gate.canTalk(13_000))
    }

    @Test
    fun `급제동·큰 조향·갈림길도 조용히 한다`() {
        val gate = WorkloadGate()
        assertEquals(setOf(Workload.HARD_BRAKING), gate.update(snap(0, brake = true)))
        assertEquals(setOf(Workload.SHARP_STEERING), gate.update(snap(1, steer = -45f)))
        assertEquals(setOf(Workload.JUNCTION_AHEAD), gate.update(snap(2), junctionAhead = true))
    }

    @Test
    fun `작은 조향은 바쁜 상황이 아니다`() {
        val gate = WorkloadGate()
        assertTrue(gate.update(snap(0, steer = 5f)).isEmpty())
    }
}
