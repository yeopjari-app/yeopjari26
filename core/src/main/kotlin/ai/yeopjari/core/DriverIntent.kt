package ai.yeopjari.core

/** 대답 속에 담긴 운전자의 요청. */
enum class DriverIntent { NONE, QUIET, TIRED, STORY }

// "졸음"만 있는 말("졸음쉼터", "졸음운전 얘기")은 피곤하다는 뜻이 아니라서 넣지 않는다.
private val TIRED_WORDS = listOf(
    "피곤", "졸려", "졸리", "졸린", "졸립", "졸음이 와", "졸음이 오", "잠이 와", "잠이 오", "잠 와", "잠와", "잠이 쏟아",
    "눈이 감", "눈 감겨", "눈이 자꾸 감", "쉬고 싶", "쉬어야", "쉬자",
)
/** "안 피곤해", "하나도 안 졸려", "졸리지 않아", "피곤하진 않아"는 피곤하다는 말이 아니다. */
private val NOT_TIRED = Regex("(^|\\s)(안|하나도\\s*안|전혀\\s*안|별로\\s*안)\\s?(피곤|졸)|(피곤|졸리|졸립)(하지|지|진|하진)\\s*(않|안)")
private val QUIET_WORDS = listOf("조용히", "그만 말", "말 걸지", "시끄러", "말하지 마")
private val STORY_WORDS = listOf("재밌는 얘기", "재미있는 얘기", "재밌는 이야기", "재미있는 이야기", "얘기 해 줘", "얘기해 줘", "이야기 해 줘", "이야기해 줘", "심심해")

/**
 * "피곤해"는 바로 휴식 안내로, "조용히 해 줘"는 말수를 줄이는 신호로, "재밌는 얘기 해 줘"는 이야기 요청으로 읽는다.
 * 여러 개가 들어 있으면 안전을 위해 피곤함을 먼저 본다. "안 피곤해"처럼 부정하면 피곤함으로 보지 않는다.
 */
fun detectIntent(text: String?): DriverIntent {
    val t = text.orEmpty()
    val tired = TIRED_WORDS.any { t.contains(it) } && !NOT_TIRED.containsMatchIn(t)
    return when {
        tired -> DriverIntent.TIRED
        QUIET_WORDS.any { t.contains(it) } -> DriverIntent.QUIET
        STORY_WORDS.any { t.contains(it) } -> DriverIntent.STORY
        else -> DriverIntent.NONE
    }
}
