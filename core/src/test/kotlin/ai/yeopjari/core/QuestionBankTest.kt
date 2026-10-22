package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuestionBankTest {

    @Test
    fun `숫자 답 읽기`() {
        assertEquals(12, parseKoreanNumber("12"))
        assertEquals(12, parseKoreanNumber("12요"))
        assertEquals(12, parseKoreanNumber("십이"))
        assertEquals(20, parseKoreanNumber("이십"))
        assertEquals(10, parseKoreanNumber("십"))
        assertEquals(12, parseKoreanNumber("열두 개"))
        assertEquals(11, parseKoreanNumber("열하나"))
        assertEquals(7, parseKoreanNumber("일곱"))
        assertEquals(7, parseKoreanNumber("칠"))
        assertEquals(2, parseKoreanNumber("이요"))
        assertNull(parseKoreanNumber("모르겠어요"))
    }

    @Test
    fun `산수 문제는 정답만 맞다고 본다`() {
        val bank = QuestionBank(Random(1))
        val q = generateSequence { bank.next() }.first { it.prompt.contains("더하기") }
        val (a, b) = Regex("(\\d) 더하기 (\\d)").find(q.prompt)!!.destructured
        assertTrue(q.isCorrect("${a.toInt() + b.toInt()}"))
        assertFalse(q.isCorrect("${a.toInt() + b.toInt() + 1}"))
    }

    @Test
    fun `대답 판단`() {
        assertTrue(isYes("응"))
        assertTrue(isYes("네 들를게요"))
        assertFalse(isYes("아니요"))
        assertFalse(isYes("괜찮아요"))
        assertFalse(isYes(null))
        assertFalse(isYes("음"))
    }
}
