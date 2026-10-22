package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertnessEngineTest {

    @Test
    fun `기획서 시나리오 - 정상에서 주의를 거쳐 위험으로`() {
        val engine = AlertnessEngine()
        repeat(3) { assertEquals(AlertStage.NORMAL, engine.record(CheckResult(1_200, correct = true))) }
        assertEquals(1_200, engine.baselineMs)

        // 응답 2.8초 + 오답 두 번 → 주의
        assertEquals(AlertStage.CAUTION, engine.record(CheckResult(2_800, correct = false)))
        assertEquals(AlertStage.CAUTION, engine.record(CheckResult(2_800, correct = false)))

        // 무응답 → 위험
        assertEquals(AlertStage.DANGER, engine.record(CheckResult(null, correct = false)))
    }

    @Test
    fun `한 번 놓친 응답은 위험이 아니라 주의`() {
        val engine = AlertnessEngine()
        repeat(3) { engine.record(CheckResult(1_000, correct = true)) }
        assertEquals(AlertStage.CAUTION, engine.record(CheckResult(null, correct = false)))
    }

    @Test
    fun `평소 느린 운전자는 본인 기준으로 판단해 경고하지 않는다`() {
        val engine = AlertnessEngine()
        repeat(5) { engine.record(CheckResult(2_500, correct = true)) }
        assertEquals(AlertStage.NORMAL, engine.record(CheckResult(2_900, correct = true)))
    }

    @Test
    fun `느린 응답은 기준선을 올리지 않는다`() {
        val engine = AlertnessEngine()
        repeat(3) { engine.record(CheckResult(1_000, correct = true)) }
        repeat(3) { engine.record(CheckResult(3_000, correct = true)) }
        assertEquals(1_000, engine.baselineMs)
    }

    @Test
    fun `휴식 후에는 정상으로 돌아가고 기준선은 유지된다`() {
        val engine = AlertnessEngine()
        repeat(3) { engine.record(CheckResult(1_000, correct = true)) }
        engine.record(CheckResult(null, correct = false))
        engine.record(CheckResult(null, correct = false))
        assertEquals(AlertStage.DANGER, engine.stage)

        engine.resetAfterRest()
        assertEquals(AlertStage.NORMAL, engine.stage)
        assertEquals(1_000, engine.baselineMs)
    }
}
