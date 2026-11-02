package ai.yeopjari.core

import kotlin.random.Random

/** 음성 체크 한 번에 건넬 말. 주제 대화면 [topic] 이 있다. */
class Question(val prompt: String, val topic: Topic? = null)

/** 운전자가 고른 대화 주제. */
enum class Topic { MUSIC, SPORTS, TRAVEL, FOOD }

/**
 * 운전에 부담이 없는 짧은 말을 만든다. 정답을 요구하지 않고, 대답 속도와 대답 여부만 본다.
 * 3번 중 1번은 "괜찮으시면 '응' 해 주세요" 같은 확인이고, 나머지는 고른 주제의 가벼운 대화다.
 */
class QuestionBank(
    private val topics: Set<Topic> = Topic.entries.toSet(),
    private val random: Random = Random.Default,
) {
    private val checkIns = listOf(
        "괜찮으시면 '응' 해 주세요.",
        "듣고 계시면 '네'라고 해 주세요.",
        "운전 괜찮으세요?",
    )

    private val chats = mapOf(
        Topic.MUSIC to listOf("요즘 즐겨 듣는 노래 있으세요?", "운전할 때 신나는 노래가 좋으세요, 잔잔한 노래가 좋으세요?"),
        Topic.SPORTS to listOf("요즘 응원하는 팀 있으세요?", "직접 하는 운동 있으세요?"),
        Topic.TRAVEL to listOf("목적지 도착하면 제일 먼저 뭐 하실 거예요?", "다음에 가 보고 싶은 여행지 있으세요?"),
        Topic.FOOD to listOf("휴게소 간식 중에 뭐 좋아하세요?", "도착하면 뭐 드시고 싶으세요?"),
    )

    /**
     * [nextRestStop] 을 알면 가끔 그 이름으로 말을 건다.
     * [checkInOnly] 면 운전자가 "조용히 해 줘"라고 한 동안처럼 짧은 확인 멘트만 쓴다.
     */
    fun next(nextRestStop: String? = null, checkInOnly: Boolean = false): Question {
        val roll = if (checkInOnly) 0 else random.nextInt(3)
        if (roll == 0) return Question(checkIns[random.nextInt(checkIns.size)])
        if (nextRestStop != null && roll == 1) return Question("곧 ${particle(nextRestStop, "이", "가")} 나와요. 들러 보신 적 있으세요?")
        return chat()
    }

    /** 고른 주제 안의 가벼운 대화 한마디. 고른 주제가 없으면 확인 멘트. */
    fun chat(): Question {
        val pool = topics.flatMap { topic -> chats.getValue(topic).map { Question(it, topic) } }
        if (pool.isEmpty()) return Question(checkIns[random.nextInt(checkIns.size)])
        return pool[random.nextInt(pool.size)]
    }
}

private val FILLERS = setOf("음", "어", "으음", "아", "흠", "그")

/** "음…", "어" 같은 머뭇거림만 있으면 대답으로 보지 않는다. */
fun isMeaningfulAnswer(text: String?): Boolean {
    val words = text.orEmpty()
        .replace(Regex("[.,!?…~]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return words.any { it !in FILLERS }
}
