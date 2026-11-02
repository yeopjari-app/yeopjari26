package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyNoticeTest {
    @Test
    fun `휴식을 시작하면 휴게소 이름과 다시 출발할 시각을 알린다`() {
        val text = FamilyNotice.restStarted("안성휴게소(부산방향)", 15, resumeHour = 2, resumeMinute = 47)
        assertTrue(text.startsWith("[옆자리] 지금 안성휴게소(부산방향)에서 15분 쉬어 가는 중이에요. 오전 2:47쯤 다시 출발할 예정이에요."))
    }

    @Test
    fun `휴게소를 모르면 이름 없이 알린다`() {
        assertTrue(FamilyNotice.restStarted(null, 15, 14, 5).startsWith("[옆자리] 지금 안전한 곳에 차를 세우고 15분 쉬어 가는 중이에요. 오후 2:05쯤"))
        assertEquals("[옆자리] 푹 쉬고 다시 출발했어요.", FamilyNotice.resumed(null))
        assertEquals("[옆자리] 안성휴게소에서 푹 쉬고 다시 출발했어요.", FamilyNotice.resumed("안성휴게소"))
    }

    @Test
    fun `깨워도 대답이 없으면 전화를 부탁하고 쉼터 이름까지만 알린다`() {
        assertEquals(
            "[옆자리] 운전 중 졸음 신호가 이어지고, 깨워도 대답이 없어요. 전화를 걸어 깨워 주세요(차에서 핸즈프리로 받을 수 있어요). 가까운 쉼터는 7km 앞 추풍령휴게소예요.",
            FamilyNotice.noResponse("추풍령휴게소", 7),
        )
        assertTrue(FamilyNotice.noResponse("문의졸음쉼터", 3).endsWith("3km 앞 문의졸음쉼터예요."))
        assertTrue(FamilyNotice.noResponse("죽전휴게소(서울방향)", 2).endsWith("2km 앞 죽전휴게소(서울방향)이에요."))
        assertTrue(FamilyNotice.noResponse(null, null).endsWith("받을 수 있어요)."))
        assertEquals("[옆자리] 다시 대답했어요. 가까운 쉼터에서 쉬어 가도록 안내하고 있어요.", FamilyNotice.respondedAgain())
    }

    @Test
    fun `자정과 정오를 12시로 적는다`() {
        assertEquals("오전 12:00", FamilyNotice.clock(0, 0))
        assertEquals("오후 12:30", FamilyNotice.clock(12, 30))
        assertEquals("오전 1:05", FamilyNotice.clock(25, 5))
    }

    @Test
    fun `휴대전화 번호만 받고 형식을 정리한다`() {
        assertEquals("01012345678", FamilyNotice.normalizePhone("010-1234-5678"))
        assertEquals("01012345678", FamilyNotice.normalizePhone("+82 10 1234 5678"))
        assertNull(FamilyNotice.normalizePhone("02-123-4567"))
        assertNull(FamilyNotice.normalizePhone(""))
        assertNull(FamilyNotice.normalizePhone(null))
    }
}
