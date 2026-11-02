package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EnhancementsTest {
    // 남쪽(180°)으로 달린다. 위도 0.1도 ≈ 11km.
    private fun stop(name: String, lat: Double) = RestStop(name, name, GeoPoint(lat, 127.10), RestStopKind.SERVICE_AREA)
    private val here = GeoPoint(37.00, 127.10)

    @Test
    fun `다음 쉼터 뒤로 한참 없으면 마지막 기회로 알린다`() {
        val stops = listOf(stop("가 휴게소", 36.92), stop("나 휴게소", 36.40))   // 가 다음은 약 58km 뒤
        val gap = lastStopBeforeGap(here, 180.0, stops, maxKm = 30.0, gapKm = 40.0)
        assertNotNull(gap)
        assertEquals("가 휴게소", gap!!.next.stop.name)
        assertEquals(40.0, gap.gapKm, 0.0)
    }

    @Test
    fun `쉼터가 촘촘하거나 방향을 모르면 알리지 않는다`() {
        val stops = listOf(stop("가 휴게소", 36.92), stop("나 휴게소", 36.75))   // 약 19km 간격
        assertNull(lastStopBeforeGap(here, 180.0, stops, maxKm = 30.0, gapKm = 40.0))
        assertNull(lastStopBeforeGap(here, null, listOf(stop("가 휴게소", 36.92)), maxKm = 30.0))
    }

    @Test
    fun `오래 달리면 대화를 말놀이로 바꾸고 판정에는 쓰지 않는다`() {
        val c = Conversation(random = Random(1))
        val early = (1..6).map { c.next(TurnContext(drivingMinutes = 30)) }
        assertFalse(early.any { it.prompt in ACTIVE_TASKS })
        val c2 = Conversation(random = Random(2))
        val late = (1..8).map { c2.next(TurnContext(drivingMinutes = 120)) }
        val active = late.filter { it.kind == TurnKind.CHAT }
        val games = late.filter { it.prompt in ACTIVE_TASKS }
        assertTrue(games.isNotEmpty())
        assertTrue(active.all { !it.measured })
        assertTrue(late.any { it.measured })   // 측정용 확인은 계속 섞는다
    }
}
