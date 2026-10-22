package ai.yeopjari.core

/** 대답 속에 담긴 운전자의 요청. */
enum class DriverIntent { NONE, QUIET, TIRED }

private val TIRED_WORDS = listOf("피곤", "졸려", "졸리", "졸음", "쉬고 싶", "쉬어야", "쉬자")
private val QUIET_WORDS = listOf("조용히", "그만 말", "말 걸지", "시끄러", "말하지 마")

/**
 * "피곤해"는 바로 휴식 안내로, "조용히 해 줘"는 말수를 줄이는 신호로 읽는다.
 * 둘 다 들어 있으면 안전을 위해 피곤함을 먼저 본다.
 */
fun detectIntent(text: String?): DriverIntent {
    val t = text.orEmpty()
    return when {
        TIRED_WORDS.any { t.contains(it) } -> DriverIntent.TIRED
        QUIET_WORDS.any { t.contains(it) } -> DriverIntent.QUIET
        else -> DriverIntent.NONE
    }
}
