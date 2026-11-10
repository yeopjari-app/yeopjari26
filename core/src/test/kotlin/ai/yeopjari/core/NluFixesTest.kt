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
        // 감사 보완: "그만 물어봐"·"그만해"도 거절이고, 이야기 제안에 "아뇨·별로·싫은데·안 들을래"는 거절이다.
        listOf("그만 물어봐", "그만해", "아뇨 괜찮아요").forEach { assertFalse(it, isYes(it)) }
        listOf("아뇨", "별로", "싫은데", "안 들을래").forEach { assertTrue(it, isNo(it)) }
        assertFalse(isNo("응 해 봐"))
        // 3차 감사: "필요 없어", "관심 없어", "안 볼래"도 거절이다.
        listOf("쉴 필요 없어", "관심 없어", "안 볼래", "사양할게요").forEach { assertTrue(it, isNo(it)); assertFalse(it, isYes(it)) }
        // 흔한 긍정: "그럼요", 커피가 "있어요", "잘게요", "마실게요", "ㅇㅇ".
        listOf("그럼요", "있어요", "잘게요", "눈 붙일게요", "마실게요", "찾아 줘", "ㅇㅇ").forEach { assertTrue(it, isYes(it)) }
        // 5차 점검: 졸린 운전자의 긍정과 거절(말뭉치 약 1,900문장으로 확인한 대표 문장).
        listOf("졸려요", "눈이 감겨요", "쉬어야 돼요", "쉬죠", "쉬다 가지 뭐", "그럴게요", "자야겠어요", "가입시더", "마시고 잘게요").forEach { assertTrue(it, isYes(it)) }
        listOf("그냥 갈게요", "계속 갈게요", "아직 갈 수 있어요", "안 잘래요", "눈 안 붙일게요", "이따 쉴게요", "다음에 들를게요", "네 괜찮은데요",
            "마실 거 없어요", "마실 생각 없어", "찾아봐야 돼", "마실까 말까", "아이다").forEach { assertFalse(it, isYes(it)) }
        // "앞이 안 보여"는 거절이 아니라 증상이다: 같이 한 "쉴게"를 그대로 긍정으로 읽는다.
        assertFalse(isNo("눈이 침침해서 앞이 안 보여"))
        assertTrue(isYes("앞이 잘 안 보여, 쉴게"))
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
        // 되풀이한 대답("네네")과 동사형 긍정("쉴게요")도 긍정이다. "그만 쉬자"는 거절이 아니다.
        listOf("네네", "응응", "네에", "맞아요", "쉴게요", "쉬어 갈래요", "갈게요", "그래 이제 그만 쉬자").forEach { assertTrue(it, isYes(it)) }
        // 부정 꼴의 긍정: 못 참겠다·더는 못 가겠다·싫지 않다·안 쉬면 안 된다는 그렇다는 뜻이다.
        listOf("네, 졸려서 못 참겠어요", "더는 못 가겠어", "싫지 않아요", "나쁘지 않네요", "그래 안 쉬면 안 되지").forEach {
            assertTrue(it, isYes(it)); assertFalse(it, isNo(it))
        }
    }

    @Test
    fun `안 피곤하다는 말은 휴식 요청이 아니다`() {
        listOf("안 피곤해", "하나도 안 졸려", "졸리지 않아", "피곤하진 않아", "전혀 안 피곤한데").forEach {
            assertEquals(it, DriverIntent.NONE, detectIntent(it))
        }
        // 여러 꼴의 부정도 피곤함이 아니다.
        listOf("피곤하지는 않아", "피곤하지도 않아", "졸리지도 않아", "피곤한 건 아니야", "쉬고 싶지 않아").forEach {
            assertEquals(it, DriverIntent.NONE, detectIntent(it))
        }
        // 가정·걱정("졸리면 말할게")과 "피곤 안 해", "쉬어야 하는 건 아니야"도 피곤하다는 말이 아니다.
        listOf("안 졸려, 졸리면 말할게", "졸리면 말해 줄게요", "졸릴까 봐 커피 마셨어", "피곤 안 해", "쉬어야 하는 건 아니야").forEach {
            assertEquals(it, DriverIntent.NONE, detectIntent(it))
        }
    }

    @Test
    fun `졸음이라는 낱말만으로는 피곤함이 아니고, 졸립·잠이 와·눈이 감은 피곤함이다`() {
        assertEquals(DriverIntent.NONE, detectIntent("졸음쉼터가 몇 개야"))
        listOf("졸립네", "잠이 와", "눈이 감겨", "졸려 안 되겠다").forEach { assertEquals(it, DriverIntent.TIRED, detectIntent(it)) }
        // 구절마다 본다: 앞 구절에서 부정해도 뒤 구절에서 피곤하다고 하면 피곤함. 띄어쓰기 없이 말해도 알아듣는다.
        listOf("아까는 안 졸렸는데 지금은 졸려", "별로 안 피곤한데 눈이 자꾸 감겨", "잠 온다", "졸린데 쉬어야겠다").forEach {
            assertEquals(it, DriverIntent.TIRED, detectIntent(it))
        }
        // 한 구절 안의 부정이 뒤의 피곤함을 덮지 않는다. 졸았다·하품·졸음이 쏟아진다도 피곤함이다.
        listOf("안 피곤하고 졸려", "피곤하지 않고 좀 졸려", "방금 깜빡 졸았어", "졸음이 쏟아져", "하품이 계속 나와").forEach {
            assertEquals(it, DriverIntent.TIRED, detectIntent(it))
        }
        assertEquals(DriverIntent.NONE, detectIntent("안 졸았어"))
        assertEquals(DriverIntent.STORY, detectIntent("얘기해줘"))
        assertEquals(DriverIntent.QUIET, detectIntent("말 좀 그만 걸어"))
        // 4차 점검: 흔한 피곤함·쉬자는 말, 부정한 말, 조용히·이야기 요청의 여러 꼴.
        listOf("잠 오네", "졸음 와요", "눈이 무거워", "나른해", "쉬어가자", "좀 쉴래", "자고 싶어").forEach { assertEquals(it, DriverIntent.TIRED, detectIntent(it)) }
        listOf("졸린 것 같지는 않아", "하품 안 나와요", "안 쉬어도 돼", "자고 싶지 않아").forEach { assertEquals(it, DriverIntent.NONE, detectIntent(it)) }
        listOf("조용히 하지 마", "조용히 안 해도 돼").forEach { assertEquals(it, DriverIntent.NONE, detectIntent(it)) }
        listOf("말 시키지 마", "얘기 그만").forEach { assertEquals(it, DriverIntent.QUIET, detectIntent(it)) }
        listOf("얘기 좀 해 줘", "이야기 들려줘", "웃긴 얘기", "심심하네").forEach { assertEquals(it, DriverIntent.STORY, detectIntent(it)) }
        listOf("잠이 온다", "졸음이 온다", "졸음이 밀려와", "눈이 풀려", "잠들 뻔했어", "쉬고 갈게요").forEach { assertEquals(it, DriverIntent.TIRED, detectIntent(it)) }
        listOf("아까 쉬어가서 괜찮아", "쉬어갈 필요 없어", "나른한 오후 노래 틀어줘", "심심하지 않아", "잠이 막 깼어", "졸음이 확 달아났어").forEach { assertEquals(it, DriverIntent.NONE, detectIntent(it)) }
        listOf("조용히 하지 말고 얘기해줘", "얘기 그만하지 마").forEach { assertEquals(it, false, detectIntent(it) == DriverIntent.QUIET) }
    }

    @Test
    fun `부정한 좋은 잠은 짧은 잠이고 밤을 샌 것도 읽는다`() {
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("푹 못 잤어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("잘 안 잤어"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("충분하지 않아"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("밤을 샜어"))
        assertEquals(SleepBand.ENOUGH, SleepAnswer.parse("푹 잤어"))
        // "네,"는 숫자 4가 아니라 맞장구다. "네 시간"은 4시간이다.
        assertEquals(SleepBand.ENOUGH, SleepAnswer.parse("네, 일곱 시간 잤어요"))
        assertEquals(7.0, SleepAnswer.hours("네 일곱 시간요")!!, 0.0)
        assertEquals(4.0, SleepAnswer.hours("네 시간")!!, 0.0)
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("밤을 꼬박 샜어"))
        // "한 여섯 시간"은 약 6시간, "N시간도 못 잤어"는 그보다 적게, 분으로 말한 잠도 읽는다.
        assertEquals(6.0, SleepAnswer.hours("한 여섯 시간 잤어요")!!, 0.0)
        assertEquals(1.0, SleepAnswer.hours("한두 시간")!!, 0.0)
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("5시간도 못 잤어요"))
        assertEquals(SleepBand.SHORT, SleepAnswer.parse("일곱 시간도 못 잤어요"))
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("삼십분 잤어요"))
        assertEquals(0.5, SleepAnswer.hours("삼십분 잤어요")!!, 0.0)
        assertEquals(SleepBand.VERY_SHORT, SleepAnswer.parse("밤샘했어요"))
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
        assertEquals(true, coffeePreference("커피는 좋은데 라떼는 싫어"))
        listOf("커피 마시지 않아요", "커피 마시고 싶지 않아", "커피 생각 없어", "커피 됐어요", "커피 마시면 안 돼요").forEach { assertEquals(it, false, coffeePreference(it)) }
        assertEquals(true, coffeePreference("커피 마시지 않으면 졸려"))
        listOf("커피 대신 물 마실게요", "카페인은 피하고 있어요", "커피 마실 기분 아니에요").forEach { assertEquals(it, false, coffeePreference(it)) }
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
        listOf("라면 먹지 않아요", "우동 먹고 싶지 않아", "우동이랑 라면 둘 다 싫어요", "라면 아니에요", "호두과자 질렸어요").forEach {
            val e = conversation(); e.reply(Turn("휴게소 간식 중에 뭐 좋아하세요?", TurnKind.CHAT, Topic.FOOD), it); assertNull(it, e.memory.food)
        }
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
        // 부정한 팀은 기억하지 않고, "말고" 뒤의 팀을 고른다. 별명("트윈스")도 알아듣는다.
        val n = conversation()
        n.reply(Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS), "엘지 싫어해요")
        assertNull(n.memory.team)
        n.reply(Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS), "엘지 말고 두산")
        assertEquals("두산 베어스", n.memory.team)
        val m = conversation()
        m.reply(Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS), "트윈스요")
        assertEquals("LG 트윈스", m.memory.team)
        // "싫지 않아"·"질리지 않아"는 좋아한다는 말이다.
        val f = conversation()
        f.reply(Turn("휴게소 간식 중에 뭐 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "어묵 싫지 않아요")
        assertEquals("어묵", f.memory.food)
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
