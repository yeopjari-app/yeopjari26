package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 감사에서 찾은 말 이해 오류(부정을 긍정으로, 낱말 속 글자를 대답으로 읽던 것)를 막는다. */
class NluFixesTest {

    @Test
    fun `부정이 섞인 대답은 긍정이 아니다`() {
        listOf("안 들를래", "안 할래요", "못 가요", "가지 마", "들르지 말고 그냥 가자", "싫어요", "아뇨", "별로요", "그만", "안 돼").forEach {
            assertFalse(it, isYes(it))
        }
    }

    @Test
    fun `짧은 긍정은 낱말 전체로만 본다`() {
        listOf("응", "네", "네 들를게요", "좋아요", "그래", "어, 해 봐", "부탁해", "가자", "켜 줘", "알았어", "오케이").forEach {
            assertTrue(it, isYes(it))
        }
        // 낱말 속 글자("응원", "예전", "네비", "그래서")로는 긍정이 되지 않는다.
        listOf("응원하는 팀 얘기 중이었는데", "예전에 가 봤어", "네비가 이상해", "그래서 뭐", "안녕").forEach {
            assertFalse(it, isYes(it))
        }
    }

    @Test
    fun `안 피곤하다는 말은 휴식 요청이 아니다`() {
        listOf("안 피곤해", "하나도 안 졸려", "졸리지 않아", "피곤하진 않아", "전혀 안 피곤한데").forEach {
            assertEquals(it, DriverIntent.NONE, detectIntent(it))
        }
    }

    @Test
    fun `졸음이라는 낱말만으로는 피곤함이 아니고, 졸립·잠이 와·눈이 감은 피곤함이다`() {
        assertEquals(DriverIntent.NONE, detectIntent("졸음쉼터가 몇 개야"))
        listOf("졸립네", "잠이 와", "눈이 감겨", "졸려 안 되겠다").forEach { assertEquals(it, DriverIntent.TIRED, detectIntent(it)) }
    }

    @Test
    fun `부정한 좋은 잠은 짧은 잠이고 밤을 샌 것도 읽는다`() {
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("푹 못 잤어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("잘 안 잤어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("충분하지 않아"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("밤을 샜어"))
        assertEquals(SleepBand.ENOUGH, SleepAnswer.parse("푹 잤어"))
    }

    @Test
    fun `잠 시간이 범위면 적은 쪽을 쓴다`() {
        assertEquals(5.0, SleepAnswer.hours("다섯 여섯 시간")!!, 0.0)
        assertEquals(5.0, SleepAnswer.hours("다섯에서 여섯 시간")!!, 0.0)
        assertEquals(6.0, SleepAnswer.hours("6에서 7시간")!!, 0.0)
        assertEquals(12.0, SleepAnswer.hours("열두 시간")!!, 0.0)
    }

    @Test
    fun `커피 취향은 마디와 조건을 본다`() {
        assertEquals(true, coffeePreference("커피 못 끊어"))
        assertEquals(true, coffeePreference("커피 없으면 안 돼"))
        assertEquals(true, coffeePreference("커피 안 마시면 졸려"))
        assertEquals(false, coffeePreference("커피 말고 물 마실래"))
        assertEquals(true, coffeePreference("라떼는 싫고 아메리카노는 좋아"))
        assertEquals(false, coffeePreference("커피는 별로고 녹차 마셔요"))
        assertNull(coffeePreference("녹차 좋아"))
    }

    private fun conversation() = Conversation(questions = QuestionBank(random = Random(3)), random = Random(3))

    @Test
    fun `부정한 음식은 좋아하는 간식으로 기억하지 않는다`() {
        val c = conversation()
        c.reply(Turn("휴게소 간식 중에 뭐 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "우동은 별로고 라면이 좋아요")
        assertEquals("라면", c.memory.food)
        val d = conversation()
        d.reply(Turn("휴게소 간식 중에 뭐 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "커피 안 마셔요")
        assertNull(d.memory.food)
    }

    @Test
    fun `팀은 팀을 물었을 때만, 낱말 경계로 읽는다`() {
        val c = conversation()
        c.reply(Turn("직접 하는 운동 있으세요?", TurnKind.CHAT, Topic.SPORTS), "삼성 헬스로 걷기 해요")
        assertNull(c.memory.team)
        c.reply(Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS), "기아차 타는데 팀은 없어요")
        assertNull(c.memory.team)
        c.reply(Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS), "롯데 팬이에요")
        assertEquals("롯데 자이언츠", c.memory.team)
    }

    @Test
    fun `속도를 재는 차례는 늘 짧은 확인 멘트다`() {
        val c = Conversation(questions = QuestionBank(random = Random(5)), random = Random(5))
        val checkIns = setOf("괜찮으시면 '응' 해 주세요.", "듣고 계시면 '네'라고 해 주세요.", "운전 괜찮으세요?")
        val measured = List(20) { c.next(TurnContext(nextRestStop = "천안삼거리휴게소")) }.filter { it.measured }
        assertTrue(measured.isNotEmpty())
        assertTrue(measured.all { it.prompt in checkIns })
    }
}
