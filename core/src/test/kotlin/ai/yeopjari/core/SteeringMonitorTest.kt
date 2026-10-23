package ai.yeopjari.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SteeringMonitorTest {

    /** 직진 중의 아주 작은 조향 떨림. */
    private fun calm(i: Int) = (sin(i * 0.3) * 1.0).toFloat()

    /** 좌우로 흔들리다 바로잡기를 반복. */
    private fun weave(i: Int) = if (i % 2 == 0) 6f else -6f

    @Test
    fun `평소 직진하다 흔들리기 시작하면 감지한다`() {
        val m = SteeringMonitor(baselineSamples = 30, windowSamples = 10)
        repeat(30) { assertFalse(m.update(calm(it))) }
        repeat(10) { m.update(calm(30 + it)) }
        var detected = false
        repeat(12) { detected = m.update(weave(it)) }
        assertTrue(detected)
    }

    @Test
    fun `보정 전에는 흔들려도 판단하지 않는다`() {
        val m = SteeringMonitor(baselineSamples = 30, windowSamples = 10)
        repeat(20) { assertFalse(m.update(weave(it))) }
    }

    @Test
    fun `원래 조향을 자주 고치는 운전자는 본인 기준으로 본다`() {
        val m = SteeringMonitor(baselineSamples = 30, windowSamples = 10)
        repeat(30) { m.update(weave(it)) }
        var detected = false
        repeat(12) { detected = m.update(weave(it)) }
        assertFalse(detected)
    }

    @Test
    fun `한 번의 차선 변경은 흔들림이 아니다`() {
        val m = SteeringMonitor(baselineSamples = 30, windowSamples = 10)
        repeat(30) { m.update(calm(it)) }
        val lane = listOf(0f, 8f, 15f, 8f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        var detected = false
        lane.forEach { detected = detected || m.update(it) }
        assertFalse(detected)
    }
}
