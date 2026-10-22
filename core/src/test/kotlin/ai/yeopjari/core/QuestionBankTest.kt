package ai.yeopjari.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuestionBankTest {

    @Test
    fun `산수 문제 없이 가벼운 말만 건넨다`() {
        val bank = QuestionBank(random = Random(7))
        repeat(50) { assertFalse(bank.next().prompt.contains("더하기")) }
    }

    @Test
    fun `고른 주제 안에서만 대화한다`() {
        val bank = QuestionBank(topics = setOf(Topic.FOOD), random = Random(3))
        val prompts = List(60) { bank.next().prompt }.toSet()
        assertTrue(prompts.any { it.contains("휴게소 간식") || it.contains("드시고") })
        assertFalse(prompts.any { it.contains("노래") || it.contains("응원") })
    }

    @Test
    fun `다음 휴게소 이름으로 말을 건넨다`() {
        val bank = QuestionBank(random = Random(11))
        assertTrue(List(30) { bank.next("천안삼거리휴게소").prompt }.any { it.contains("천안삼거리휴게소") })
    }

    @Test
    fun `조용히 모드에서는 확인 멘트만`() {
        val bank = QuestionBank(random = Random(5))
        val prompts = List(30) { bank.next("천안삼거리휴게소", checkInOnly = true).prompt }
        assertTrue(prompts.all { it.contains("응") || it.contains("네") || it.contains("괜찮") })
    }

    @Test
    fun `머뭇거림만 있으면 대답이 아니다`() {
        assertTrue(isMeaningfulAnswer("응"))
        assertTrue(isMeaningfulAnswer("음 호두과자요"))
        assertFalse(isMeaningfulAnswer("음..."))
        assertFalse(isMeaningfulAnswer("어 어"))
        assertFalse(isMeaningfulAnswer(""))
        assertFalse(isMeaningfulAnswer(null))
    }

    @Test
    fun `제안에 대한 대답 판단`() {
        assertTrue(isYes("응"))
        assertTrue(isYes("네 들를게요"))
        assertFalse(isYes("아니요"))
        assertFalse(isYes("괜찮아요"))
        assertFalse(isYes(null))
        assertFalse(isYes("음"))
    }
}
