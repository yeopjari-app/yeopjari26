package ai.yeopjari.core

import kotlin.random.Random

/**
 * 대화 한 번의 종류.
 * [MEASURE] 는 바로 답할 수 있는 가벼운 말로, 대답 속도를 졸음 판정에 쓴다.
 * [CHAT]·[STORY] 는 즐겁게 이어 가는 대화라 생각할 시간이 필요할 수 있어 속도를 판정에 쓰지 않는다
 * (무응답은 어떤 종류든 "제 말 들리세요?"로 한 번 더 묻고 판정한다).
 */
enum class TurnKind { MEASURE, CHAT, STORY }

data class Turn(val prompt: String, val kind: TurnKind, val topic: Topic? = null) {
    val measured: Boolean get() = kind == TurnKind.MEASURE
}

/**
 * 이번 대화를 고를 때 필요한 상황. [nowPlaying] 은 지금 나오는 곡 제목(알 수 없으면 null),
 * [calibrating] 은 아직 평소 대답 속도를 정하는 중인지(그동안은 측정용 말만 한다).
 */
data class TurnContext(
    val stage: AlertStage = AlertStage.NORMAL,
    val calibrating: Boolean = false,
    val quiet: Boolean = false,
    val nextRestStop: String? = null,
    val nowPlaying: String? = null,
    val storyRequested: Boolean = false,
    /** 쉬지 않고 달린 시간(분). 길어지면 대화를 머리를 쓰는 쪽으로 바꾼다. */
    val drivingMinutes: Long = 0,
)

/**
 * 짧은 이야기. 끝은 부담 없는 열린 질문으로 맺는다.
 * [food] 가 있으면 운전자가 그 간식을 좋아한다고 할 때 이어서 꺼내고, 대답에 [choices] 중 하나가 있으면 기억한다.
 */
data class Story(val id: String, val text: String, val food: String? = null, val choices: List<String> = emptyList())

/**
 * 주행이 끝나도 남겨 두는 운전자 이야기(응원하는 팀, 좋아하는 휴게소 간식).
 * 음성 원문은 남기지 않고 알아낸 사실만 저장한다.
 */
class DriverMemory(facts: Map<String, String> = emptyMap()) {
    private val facts = facts.toMutableMap()

    var team: String?
        get() = facts[TEAM]
        set(value) = put(TEAM, value)
    var food: String?
        get() = facts[FOOD]
        set(value) = put(FOOD, value)
    /** 그 간식에서 고른 맛(예: 호두과자 → 팥). */
    var foodChoice: String?
        get() = facts[FOOD_CHOICE]
        set(value) = put(FOOD_CHOICE, value)

    /** 커피를 좋아하는지. 대답에서 알게 되면 기록한다(모르면 null). 커피 냅을 권할 때 쓴다. */
    var likesCoffee: Boolean?
        get() = facts[COFFEE]?.toBooleanStrictOrNull()
        set(value) = put(COFFEE, value?.toString())

    /** 대답에 커피 이야기가 있으면 좋아하는지 기록한다. 바뀌었으면 true. */
    fun noteCoffee(answer: String?): Boolean {
        val v = coffeePreference(answer) ?: return false
        if (v == likesCoffee) return false
        likesCoffee = v
        return true
    }

    /** 다시 꺼낼 때 부르는 이름. 예: "팥 호두과자" */
    val foodLabel: String?
        get() = food?.let { f -> foodChoice?.let { "$it $f" } ?: f }

    private fun put(key: String, value: String?) {
        if (value == null) facts.remove(key) else facts[key] = value
    }

    fun serialize(): String = facts.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        private const val TEAM = "team"
        private const val FOOD = "food"
        private const val FOOD_CHOICE = "food_choice"
        private const val COFFEE = "coffee"

        fun parse(text: String?): DriverMemory = DriverMemory(
            text.orEmpty().split(";").mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i) to pair.substring(i + 1)
            }.toMap()
        )
    }
}

private val COFFEE_WORDS = listOf("커피", "아메리카노", "라떼", "카페인")
private val COFFEE_NO = Regex(
    "(커피|아메리카노|라떼|카페인)(는|은|도|을|를)?\\s*(안|못|싫|끊|별로|말고|안\\s?마|잘\\s?안|대신|피하)" +
        // "커피 마시지 않아요", "커피 마시고 싶지 않아", "커피 생각 없어", "커피 됐어요", "커피 마시면 안 돼요"
        "|(커피|아메리카노|라떼|카페인)(는|은|도|을|를)?\\s*(\\S{0,4}\\s?)?(지\\s?(않|말|못)|싶지\\s?않|생각\\s?없|필요\\s?없|됐|안\\s?돼|기분\\s?(이\\s?)?아니)",
)
/** "커피 못 끊어", "커피 없으면 안 돼"는 커피를 좋아한다는 말이다. */
private val COFFEE_HOOKED = Regex("못\\s?끊|없으면\\s*안|없으면\\s*못|없이는\\s*못|달고\\s?살")
/** "커피 안 마시면 졸려" 처럼 조건으로 쓴 부정("…면")은 거절이 아니다. */
private val COFFEE_CONDITIONAL = Regex("(안|못)\\s?마시면|마시지\\s?않으면|안\\s?먹으면")
private val COFFEE_NOT = Regex("(커피|아메리카노|라떼|카페인)\\s*말고")
private val COFFEE_WANT_NOT = Regex("(커피|아메리카노|라떼|카페인)[^,.!?]{0,10}싶지\\s?않")
private val CLAUSE_SPLIT = Regex("고\\s+|,|지만|[는은인한]데|그리고|[.!?]")

/**
 * "커피 마시면 괜찮아" → true, "커피 안 마셔"·"커피는 못 마셔"·"커피 싫어"·"커피 말고 물" → false,
 * "커피 못 끊어"·"커피 안 마시면 졸려" → true, 커피 이야기가 없으면 null.
 * 여러 마디면 마디마다 보고, 커피를 좋다고 한 마디가 하나라도 있으면 true 로 본다("라떼는 싫고 아메리카노는 좋아").
 */
fun coffeePreference(answer: String?): Boolean? {
    val t = answer?.trim().orEmpty()
    if (COFFEE_WORDS.none { t.contains(it) }) return null
    if (COFFEE_HOOKED.containsMatchIn(t) || COFFEE_CONDITIONAL.containsMatchIn(t)) return true
    if (COFFEE_NOT.containsMatchIn(t)) return false
    // "커피 마시고 싶지 않아"는 "고"에서 마디가 나뉘어도 거절이다.
    if (COFFEE_WANT_NOT.containsMatchIn(t)) return false
    val clauses = t.split(CLAUSE_SPLIT).map { it.trim() }.filter { c -> COFFEE_WORDS.any { c.contains(it) } }
    if (clauses.isEmpty()) return !COFFEE_NO.containsMatchIn(t)
    return clauses.any { !COFFEE_NO.containsMatchIn(it) && !NEG_TAIL.containsMatchIn(it) }
}

/** 마디 끝의 부정("…는 싫어", "…는 별로"). */
private val NEG_TAIL = Regex("(싫|별로|안\\s?마|못\\s?마|안\\s?좋)")

private val TEAMS = linkedMapOf(
    "LG" to "LG 트윈스", "엘지" to "LG 트윈스", "두산" to "두산 베어스", "KIA" to "KIA 타이거즈", "기아" to "KIA 타이거즈",
    "삼성" to "삼성 라이온즈", "롯데" to "롯데 자이언츠", "한화" to "한화 이글스", "SSG" to "SSG 랜더스",
    "NC" to "NC 다이노스", "엔씨" to "NC 다이노스", "KT" to "KT 위즈", "케이티" to "KT 위즈", "키움" to "키움 히어로즈",
    "트윈스" to "LG 트윈스", "베어스" to "두산 베어스", "타이거즈" to "KIA 타이거즈", "라이온즈" to "삼성 라이온즈",
    "자이언츠" to "롯데 자이언츠", "이글스" to "한화 이글스", "랜더스" to "SSG 랜더스", "다이노스" to "NC 다이노스", "히어로즈" to "키움 히어로즈",
)
/** 주행이 이만큼 길어지면 가벼운 대화 대신 말놀이를 섞는다. */
const val ACTIVE_AFTER_MIN = 90L

internal val ACTIVE_TASKS = listOf(
    "끝말잇기 해요. 제가 먼저, 기차!",
    "ㄱ으로 시작하는 과일, 하나만 대 볼래요?",
    "지금까지 들른 휴게소 이름, 하나만 말해 볼래요?",
    "오늘 점심 메뉴, 세 글자로 말해 볼래요?",
    "좋아하는 노래 제목, 첫 소절만 불러 줄래요?",
)

private val FOOD_CLAUSE_NEG = Regex("싫|별로|아니|질렸|(먹|좋아하|땡기)지\\s?않|싶지\\s?않|질려|맛없|안\\s?(먹|좋)|못\\s?먹")
/** "싫지 않아", "질리지 않아"는 좋다는 말이라 부정을 찾기 전에 뺀다. */
private val FOOD_DOUBLE_NEG = Regex("(싫|질리|나쁘)지\\s?(는\\s?|도\\s?)?않")
/** 음식 이름 바로 뒤의 부정("우동은 별로", "라면 말고", "커피 안 마셔"). */
private val FOOD_NEG = Regex("^(은|는|도|을|를|이|가)?\\s*(안|못|별로|싫|말고|빼고|아니|질렸)")
/** 팀 이름 뒤에 올 수 있는 말(경계). "삼성전자", "기아차", "롯데마트"는 팀으로 보지 않는다. */
private val TEAM_TAIL = Regex("^($|[\\s,.!?~]|팬|이|가|은|는|요|를|을|의|예요|이에요|입니다|이요|야|죠|지|랑|하고|트윈스|베어스|타이거즈|라이온즈|자이언츠|이글스|랜더스|다이노스|위즈|히어로즈)")
/** 팀 이름 바로 뒤의 부정("엘지 싫어", "엘지 말고 두산", "엘지 응원 안 해"). */
private val TEAM_NEG = Regex("^\\S*\\s*(싫|별로|말고|안\\s?(좋|봐|해)|응원\\s?안)")

private val FOODS = listOf("호두과자", "알감자", "통감자", "소떡소떡", "핫바", "우동", "라면", "국밥", "어묵", "커피", "아메리카노")

private val TEAM_PROMPT = listOf("응원", "팀", "야구")

private fun teamMentioned(answer: String, key: String): Boolean {
    var i = answer.indexOf(key, ignoreCase = true)
    while (i >= 0) {
        val before = if (i == 0) ' ' else answer[i - 1]
        val startOk = !before.isLetterOrDigit()
        val tail = answer.substring(i + key.length)
        if (startOk && TEAM_TAIL.containsMatchIn(tail) && !TEAM_NEG.containsMatchIn(tail)) return true
        i = answer.indexOf(key, i + 1, ignoreCase = true)
    }
    return false
}

/** 대답에 [food]가 나오고 바로 뒤에서 부정하지 않으면 true. 커피는 커피를 싫다고 한 대답이면 false. */
private fun foodLiked(answer: String, food: String): Boolean {
    if ((food == "커피" || food == "아메리카노") && coffeePreference(answer) == false) return false
    var i = answer.indexOf(food)
    while (i >= 0) {
        // "휴게소라면", "졸음쉼터라면"의 '-라면'은 음식이 아니다: 바로 앞이 한글이면 건너뛴다.
        val ending = food == "라면" && i > 0 && answer[i - 1] in '가'..'힣'
        val rest = answer.substring(i + food.length)
        // 같은 마디 안에서 부정하면("라면 먹지 않아요", "우동 먹고 싶지 않아", "우동이랑 라면 둘 다 싫어요") 좋아하는 것이 아니다.
        val clause = rest.split(Regex("[,.!?]|지만|는데|그리고"))[0]
        if (!ending && !FOOD_NEG.containsMatchIn(rest.replace(FOOD_DOUBLE_NEG, " ")) && !FOOD_CLAUSE_NEG.containsMatchIn(clause.replace(FOOD_DOUBLE_NEG, " "))) return true
        i = answer.indexOf(food, i + 1)
    }
    return false
}

/**
 * 정해진 질문을 돌려 묻는 대신 이어지는 대화를 만든다.
 * - 평소 속도를 정하는 동안은 가벼운 확인만, 그 뒤 정상 단계에서는 가벼운 확인(속도 측정)과 즐거운 대화를 번갈아 건넨다.
 * - 대답에서 응원하는 팀·좋아하는 간식을 알아 두었다가 다음 대화와 다음 주행에 쓴다.
 * - "재밌는 얘기 해 줘"라고 하면 짧은 이야기를 들려준다(같은 이야기는 다 돌기 전까지 반복하지 않는다).
 * - 좋아하는 간식을 말하면, 그 간식 이야기가 있을 때 "들어 볼래요?" 하고 먼저 묻고, 좋다고 하면 바로 이어서 들려준다.
 * - 주의·위험 단계와 "조용히" 모드에서는 속도를 재는 짧은 말만 한다.
 * 생성형 AI 대화를 붙일 때도 이 규칙(측정용 말과 대화용 말의 구분)은 그대로 둔다.
 */
class Conversation(
    val memory: DriverMemory = DriverMemory(),
    private val questions: QuestionBank = QuestionBank(),
    private val stories: List<Story> = STORIES,
    private val random: Random = Random.Default,
) {
    private var turns = 0
    private val told = mutableSetOf<String>()
    private var followUp: Story? = null
    private var offered: Story? = null
    private var lastStory: Story? = null

    /** 방금 대답에서 이어 들려줄 이야기가 생겼으면 true. 다음 확인을 앞당기는 데 쓴다. */
    val hasFollowUp: Boolean get() = followUp != null

    /** 이야기를 들려줄지 물어 둔 상태면 true. 앱은 바로 대답을 듣고 [answerOffer]로 넘긴다. */
    val hasOffer: Boolean get() = offered != null

    /**
     * "얘기 해 드릴까요?"에 대한 대답. 좋다고 하면 다음 차례에 그 이야기를 들려주고(null 반환, 이야기가 곧 이어지므로),
     * 싫다거나 대답이 없으면 접는다. 뜻이 분명하지 않은 대답은 좋다는 뜻으로 본다("음…" 하고 기다리는 경우가 많다).
     */
    fun answerOffer(answer: String?): String? {
        val story = offered ?: return null
        offered = null
        if (answer.isNullOrBlank() || isNo(answer)) return "알겠어요. 듣고 싶을 때 '재밌는 얘기 해 줘'라고 해 주세요."
        followUp = story
        return null
    }

    fun next(ctx: TurnContext): Turn {
        turns++
        if (ctx.stage == AlertStage.NORMAL && !ctx.quiet) followUp?.let { s -> followUp = null; return tell(s) }
        followUp = null
        if (ctx.storyRequested && ctx.stage == AlertStage.NORMAL) story()?.let { return it }
        if (ctx.stage != AlertStage.NORMAL || ctx.quiet || ctx.calibrating || turns % 2 == 1) {
            // 속도를 재는 차례는 늘 짧은 확인만: 대화형 질문은 생각할 시간이 들어 대답 속도 비교가 공정하지 않다.
            val q = questions.next(ctx.nextRestStop, checkInOnly = true)
            return Turn(q.prompt, TurnKind.MEASURE, q.topic)
        }
        if (ctx.drivingMinutes >= ACTIVE_AFTER_MIN) return active()
        return chat(ctx)
    }

    /**
     * 오래 달린 뒤에는 듣기만 하는 대화보다 머리를 쓰는 짧은 말놀이가 낫다: 주행 후반에 말 과제를 한 운전자는
     * 차로 유지와 각성이 나았고, 처음부터 계속 말을 건 경우나 라디오만 들은 경우는 그만 못했다(Atchley 외 2014, Human Factors).
     * 생각할 시간이 필요하므로 대답 속도는 판정에 쓰지 않는다.
     */
    private fun active(): Turn {
        val pool = ACTIVE_TASKS + listOfNotNull(memory.food?.let { "아까 좋아한다던 휴게소 간식, 뭐였죠?" })
        return Turn(pool[random.nextInt(pool.size)], TurnKind.CHAT)
    }

    /** 운전자의 대답을 듣고 옆자리가 한마디 돌려준다. 할 말이 없으면 null. 대답에서 알게 된 것은 기억한다. */
    fun reply(turn: Turn, answer: String?): String? {
        if (answer.isNullOrBlank() || !isMeaningfulAnswer(answer)) return null
        // 팀은 팀을 물었을 때나 "응원"·"팬"을 말했을 때만, 낱말 경계로 본다("삼성 냉장고", "기아차"는 팀이 아니다).
        val aboutTeams = (turn.topic == Topic.SPORTS && TEAM_PROMPT.any { turn.prompt.contains(it) }) || answer.contains("응원") || answer.contains("팬")
        val team = if (!aboutTeams) null else TEAMS.entries.firstOrNull { (key, _) -> teamMentioned(answer, key) }?.value
        if (team != null && team != memory.team) {
            memory.team = team
            return "$team 팬이시군요! 다음에 경기 얘기도 해요."
        }
        if (turn.kind == TurnKind.STORY) {
            val story = lastStory
            val choice = story?.choices?.firstOrNull { answer.contains(it) }
            if (story?.food != null && choice != null) {
                memory.food = story.food
                memory.foodChoice = choice
                return "역시! 기억해 둘게요. ${particle(story.food, "은", "는")} $choice."
            }
        }
        val food = FOODS.firstOrNull { f -> foodLiked(answer, f) }
        if (food != null && food != memory.food) {
            memory.food = food
            memory.foodChoice = null
            val story = stories.firstOrNull { it.food == food && it.id !in told }
            if (story != null) {
                offered = story
                return "오, ${food}요! 그럼 ${food}에 얽힌 재미있는 얘기 하나 해 드릴까요?"
            }
            return "${food} 좋죠. 다음 휴게소에 있는지 봐 둘게요."
        }
        return when (turn.kind) {
            TurnKind.MEASURE -> null
            TurnKind.STORY -> "재밌죠? 또 듣고 싶으시면 '재밌는 얘기 해 줘'라고 해 주세요."
            TurnKind.CHAT -> CHAT_REPLIES[random.nextInt(CHAT_REPLIES.size)]
        }
    }

    private fun story(): Turn? {
        if (stories.isEmpty()) return null
        if (told.size == stories.size) told.clear()
        val pick = stories.filter { it.id !in told }.let { it[random.nextInt(it.size)] }
        return tell(pick)
    }

    private fun tell(story: Story): Turn {
        told += story.id
        lastStory = story
        return Turn(story.text, TurnKind.STORY)
    }

    private fun chat(ctx: TurnContext): Turn {
        val personal = listOfNotNull(
            memory.team?.let { Turn("요즘 $it 경기 보세요?", TurnKind.CHAT, Topic.SPORTS) },
            memory.food?.let { Turn("이따 휴게소 들르면 $it 드실 거예요?", TurnKind.CHAT, Topic.FOOD) },
            ctx.nowPlaying?.let { Turn("지금 나오는 '$it' 좋네요. 이 노래에 얽힌 추억 있으세요?", TurnKind.CHAT, Topic.MUSIC) },
        )
        if (personal.isNotEmpty() && random.nextInt(2) == 0) return personal[random.nextInt(personal.size)]
        val q = questions.chat()
        return Turn(q.prompt, TurnKind.CHAT, q.topic)
    }

    private companion object {
        val CHAT_REPLIES = listOf("그렇군요!", "오, 좋네요.", "말씀 들으니 저도 궁금해지네요.", "좋은데요?")
    }
}

/**
 * 시연용 이야기. 널리 알려진 사실만 담았고, 실제 서비스에서는 경로 주변 지역·지금 나오는 곡·관심 분야
 * 콘텐츠로 넓힌다(생성형 AI 연결 시 같은 형식: 20~30초 이야기 + 열린 질문).
 */
val STORIES = listOf(
    Story("gyeongbu", "경부고속도로는 1970년 7월 7일에 개통했어요. 서울에서 부산까지 428킬로미터를 2년 5개월 만에 지었대요. 처음 고속도로 타셨던 기억 나세요?"),
    Story("walnut", "호두과자는 1934년에 천안역 앞 작은 제과점에서 처음 만들었대요. 진짜 호두 모양으로 굽고, 안에 호두랑 팥을 넣어서요. 그래서 지금도 천안이 호두과자의 고장이에요. 팥이 좋으세요, 슈크림이 좋으세요?", food = "호두과자", choices = listOf("팥", "슈크림")),
    Story("coffee-nap", "졸릴 때 커피를 마시고 바로 15분쯤 자면 더 개운하다는 연구가 있어요. 카페인이 효과를 내기까지 20분쯤 걸려서, 깰 때쯤 딱 효과가 난대요. 커피 냅이라고 해요. 해 보신 적 있으세요?"),
    Story("shelter", "고속도로 졸음쉼터는 2011년부터 생기기 시작했대요. 졸릴 때 잠깐이라도 차를 세울 수 있게요. 졸음쉼터에 들러 보신 적 있으세요?"),
    Story("yawn", "하품은 옮는다는 게 연구로도 확인됐대요. 다른 사람이 하품하는 걸 보거나 듣기만 해도 따라 하게 된대요. 혹시 지금 하품 나오셨어요?"),
)
