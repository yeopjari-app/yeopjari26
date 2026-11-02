package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConversationTest {

    private fun conversation(memory: DriverMemory = DriverMemory(), seed: Int = 1) =
        Conversation(memory = memory, questions = QuestionBank(random = Random(seed)), random = Random(seed))

    @Test
    fun `정상 단계에서는 측정용 말과 대화를 번갈아 한다`() {
        val c = conversation()
        val kinds = List(6) { c.next(TurnContext()).kind }
        assertEquals(listOf(TurnKind.MEASURE, TurnKind.CHAT, TurnKind.MEASURE, TurnKind.CHAT, TurnKind.MEASURE, TurnKind.CHAT), kinds)
    }

    @Test
    fun `평소 속도를 정하는 동안은 측정용 말만 한다`() {
        val c = conversation()
        assertTrue(List(6) { c.next(TurnContext(calibrating = true)) }.all { it.measured })
    }

    @Test
    fun `주의 단계와 조용히 모드에서는 측정용 말만 한다`() {
        val c = conversation()
        assertTrue(List(10) { c.next(TurnContext(stage = AlertStage.CAUTION)) }.all { it.measured })
        assertTrue(List(10) { c.next(TurnContext(quiet = true)) }.all { it.measured })
    }

    @Test
    fun `이야기를 청하면 들려주고 다 돌기 전에는 반복하지 않는다`() {
        val c = conversation()
        val told = List(STORIES.size) { c.next(TurnContext(storyRequested = true)) }
        assertTrue(told.all { it.kind == TurnKind.STORY })
        assertEquals(STORIES.size, told.map { it.prompt }.toSet().size)
        assertEquals(TurnKind.STORY, c.next(TurnContext(storyRequested = true)).kind)
    }

    @Test
    fun `위험할 때는 이야기 대신 측정용 말`() {
        assertTrue(conversation().next(TurnContext(stage = AlertStage.DANGER, storyRequested = true)).measured)
    }

    @Test
    fun `응원하는 팀을 기억하고 다음 대화에 쓴다`() {
        val c = conversation()
        val sports = Turn("요즘 응원하는 팀 있으세요?", TurnKind.CHAT, Topic.SPORTS)
        assertEquals("LG 트윈스 팬이시군요! 다음에 경기 얘기도 해요.", c.reply(sports, "LG요"))
        assertEquals("LG 트윈스", c.memory.team)
        val chats = List(40) { c.next(TurnContext()) }.filter { !it.measured }.map { it.prompt }
        assertTrue(chats.any { it.contains("LG 트윈스") })
    }

    @Test
    fun `스포츠 얘기가 아니면 회사 이름을 팀으로 착각하지 않는다`() {
        val c = conversation()
        c.reply(Turn("도착하면 뭐 하실 거예요?", TurnKind.CHAT, Topic.TRAVEL), "삼성 다니는 친구 만나요")
        assertNull(c.memory.team)
    }

    @Test
    fun `좋아하는 간식을 기억한다`() {
        val c = conversation()
        assertNotNull(c.reply(Turn("휴게소 간식 중에 뭐 좋아하세요?", TurnKind.MEASURE, Topic.FOOD), "호두과자요"))
        assertEquals("호두과자", c.memory.food)
    }

    @Test
    fun `무응답이나 머뭇거림에는 대꾸하지 않는다`() {
        val c = conversation()
        val chat = Turn("다음에 가 보고 싶은 여행지 있으세요?", TurnKind.CHAT, Topic.TRAVEL)
        assertNull(c.reply(chat, null))
        assertNull(c.reply(chat, "음..."))
        assertNotNull(c.reply(chat, "제주도요"))
    }

    @Test
    fun `측정용 확인에는 짧게 끝낸다`() {
        assertNull(conversation().reply(Turn("운전 괜찮으세요?", TurnKind.MEASURE), "네"))
    }

    @Test
    fun `지금 나오는 곡으로 말을 건넨다`() {
        val c = conversation()
        val chats = List(40) { c.next(TurnContext(nowPlaying = "밤양갱")) }.filter { !it.measured }
        assertTrue(chats.any { it.prompt.contains("밤양갱") })
    }

    @Test
    fun `기억은 저장했다 다시 불러올 수 있다`() {
        val m = DriverMemory()
        m.team = "KIA 타이거즈"
        m.food = "알감자"
        val back = DriverMemory.parse(m.serialize())
        assertEquals("KIA 타이거즈", back.team)
        assertEquals("알감자", back.food)
        assertNull(DriverMemory.parse(null).team)
    }

    @Test
    fun `좋아하는 간식을 말하면 그 간식 이야기로 이어 가고 고른 맛을 기억한다`() {
        val c = conversation()
        val reply = c.reply(Turn("휴게소 간식 중에 뭐 제일 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "음 호두과자?")
        assertEquals("오, 호두과자요! 그럼 호두과자에 얽힌 재미있는 얘기 하나 해 드릴까요?", reply)
        assertTrue(c.hasOffer)
        assertFalse(c.hasFollowUp)
        assertNull(c.answerOffer("어, 해 봐."))
        assertFalse(c.hasOffer)
        assertTrue(c.hasFollowUp)
        val story = c.next(TurnContext())
        assertEquals(TurnKind.STORY, story.kind)
        assertTrue(story.prompt.startsWith("호두과자는 1934년에"))
        assertFalse(c.hasFollowUp)
        assertEquals("역시! 기억해 둘게요. 호두과자는 팥.", c.reply(story, "당연히 팥이지!"))
        assertEquals("팥 호두과자", c.memory.foodLabel)
        assertEquals("팥 호두과자", DriverMemory.parse(c.memory.serialize()).foodLabel)
    }

    @Test
    fun `이야기를 권했는데 싫다고 하거나 대답이 없으면 들려주지 않는다`() {
        for (answer in listOf("아니, 나중에", null)) {
            val c = conversation()
            c.reply(Turn("휴게소 간식 중에 뭐 제일 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "호두과자")
            assertNotNull(c.answerOffer(answer))
            assertFalse(c.hasOffer)
            assertFalse(c.hasFollowUp)
            assertEquals("호두과자", c.memory.food)
        }
    }

    @Test
    fun `주의 단계면 간식 이야기는 미루지 않고 버린다`() {
        val c = conversation()
        c.reply(Turn("휴게소 간식 중에 뭐 제일 좋아하세요?", TurnKind.CHAT, Topic.FOOD), "호두과자")
        c.answerOffer("응")
        assertTrue(c.next(TurnContext(stage = AlertStage.CAUTION)).measured)
        assertFalse(c.hasFollowUp)
    }
}
