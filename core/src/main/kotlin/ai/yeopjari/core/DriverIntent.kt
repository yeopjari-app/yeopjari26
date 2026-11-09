package ai.yeopjari.core

/** 대답 속에 담긴 운전자의 요청. */
enum class DriverIntent { NONE, QUIET, TIRED, STORY }

// "졸음"만 있는 말("졸음쉼터", "졸음운전 얘기")은 피곤하다는 뜻이 아니라서 넣지 않는다. 모두 띄어쓰기 없이 비교한다.
private val TIRED_WORDS = listOf(
    "피곤", "피로해", "졸려", "졸리", "졸린", "졸립", "졸렸", "졸음이와", "졸음이오", "잠이와", "잠이오", "잠와", "잠온", "잠이쏟아",
    "눈이감", "눈감겨", "눈이자꾸감", "눈이막감", "쉬고싶", "쉬어야", "쉬자",
    "졸았", "졸고있", "졸음이쏟아", "졸음이몰려", "눈꺼풀", "하품",
)
/**
 * "안 피곤해", "하나도 안 졸려", "졸리지 않아", "피곤하지는 않아", "졸리지도 않아", "피곤한 건 아니야", "쉬고 싶지 않아"는
 * 피곤하다는 말이 아니다(띄어쓰기를 뺀 한 구절 안에서 본다).
 */
private val NOT_TIRED = Regex(
    "(안|하나도안|전혀안|별로안)(피곤|졸|피로)|(피곤|졸리|졸립|피로)(하지|지|진|하진)(는|도|가|야)?(않|안)" +
        "|(피곤|졸린|졸려|피로)한?(건|게|거)아니|쉬고싶지(는|도)?(않|안)|쉬(어야|자)(는|하는)?(거|건|게)?아니" +
        "|(피곤|졸리|졸려|피로)(은|는)?안(해|하|함)",
)
/**
 * 가정·걱정으로 한 말("졸리면 말할게", "피곤하면 쉬어야지", "졸릴까 봐 커피 마셨어")은 지금 피곤하다는 뜻이 아니다.
 * 이런 꼴이 든 구절은 피곤함으로 보지 않는다.
 */
private val HYPOTHETICAL = Regex("(피곤하|피곤|졸리|졸립|잠이오|잠오|쉬고싶)(시)?(으)?면|(졸릴|피곤할)까")
/** 구절 경계: "~는데", "~지만", 쉼표·마침표. "아까는 안 졸렸는데 지금은 졸려"는 뒤 구절로 판단한다. */
private val CLAUSE = Regex("(?<=는데|한데|인데|은데|린데|운데|런데|지만|근데|그래도|그리고|니까|하고|않고)|[,.!?…]+")
private val QUIET_WORDS = listOf("조용히", "그만말", "말좀그만", "말걸지", "시끄러", "말하지마")
private val STORY_WORDS = listOf("재밌는얘기", "재미있는얘기", "재밌는이야기", "재미있는이야기", "얘기해줘", "이야기해줘", "얘기해봐", "이야기해봐", "심심해")

/**
 * "피곤해"는 바로 휴식 안내로, "조용히 해 줘"는 말수를 줄이는 신호로, "재밌는 얘기 해 줘"는 이야기 요청으로 읽는다.
 * 여러 개가 들어 있으면 안전을 위해 피곤함을 먼저 본다. 구절마다 따로 보아, 부정한 구절("안 피곤해")은 피곤함으로 보지 않지만
 * 다른 구절에서 피곤하다고 하면("아까는 안 졸렸는데 지금은 졸려") 피곤함으로 본다.
 */
fun detectIntent(text: String?): DriverIntent {
    val raw = text.orEmpty()
    val clauses = raw.split(CLAUSE).map { it.replace(Regex("\\s+"), "") }.filter { it.isNotEmpty() }
    val tired = clauses.any { c -> TIRED_WORDS.any { c.contains(it) } && !NOT_TIRED.containsMatchIn(c) && !HYPOTHETICAL.containsMatchIn(c) }
    val t = raw.replace(Regex("\\s+"), "")
    return when {
        tired -> DriverIntent.TIRED
        QUIET_WORDS.any { t.contains(it) } -> DriverIntent.QUIET
        STORY_WORDS.any { t.contains(it) } -> DriverIntent.STORY
        else -> DriverIntent.NONE
    }
}
