package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportTest {
    private val min = 60_000L

    @Test
    fun `한 번의 주행을 오늘의 운행 줄로 요약한다`() {
        val trip = TripRecorder()
        trip.start(0)
        repeat(3) { trip.conversation(1_200, measured = true) }
        trip.conversation()                          // 즐거운 대화(판정 제외)
        trip.conversation(2_800, measured = true)
        trip.signal(DrowsySignal.SLOW_ANSWER)
        trip.signal(DrowsySignal.WEAVING)
        trip.stage(AlertStage.CAUTION)
        trip.stage(AlertStage.DANGER)
        trip.wake(WakeAction.WINDOW)
        trip.wake(WakeAction.COLD_AIR_SEAT)
        trip.rest("안성휴게소(부산방향)", 15)
        trip.tick(252 * min)

        val rows = trip.summary(baselineMs = 1_200, remembered = "호두과자").rows().toMap()
        assertEquals("4시간 12분", rows["함께한 시간"])
        assertEquals("5번", rows["먼저 건넨 대화"])
        assertEquals("평소 1.2초 · 가장 늦을 때 2.8초", rows["대답 속도"])
        assertEquals("2번 · 대답 지연, 조향 흔들림", rows["졸음 신호"])
        assertEquals("창문 · 찬 바람·통풍 시트", rows["깨우기"])
        assertEquals("안성휴게소(부산방향) · 파워냅 15분", rows["휴식"])
        assertEquals("호두과자", rows["기억"])
        assertEquals(AlertStage.DANGER, trip.maxStage)
    }

    @Test
    fun `아무 일 없던 주행에는 신호·깨우기·휴식 줄을 만들지 않는다`() {
        val trip = TripRecorder()
        trip.start(10 * min)
        trip.conversation(1_000, measured = true)
        trip.tick(40 * min)
        val rows = trip.summary(1_000, remembered = "").rows().toMap()
        assertEquals("30분", rows["함께한 시간"])
        assertEquals("2시간", duration(120))
        assertFalse(rows.containsKey("졸음 신호"))
        assertFalse(rows.containsKey("깨우기"))
        assertFalse(rows.containsKey("휴식"))
        assertFalse(rows.containsKey("기억"))
    }

    @Test
    fun `저장한 요약을 그대로 다시 읽는다`() {
        val trip = TripRecorder().apply { start(0); conversation(1_500, true); tick(65 * min) }
        val s = trip.summary(1_500, "LG 트윈스")
        assertEquals(s.rows(), TripSummary.parseRows(s.serializeRows()))
        assertTrue(TripSummary.parseRows(null).isEmpty())
    }
}
