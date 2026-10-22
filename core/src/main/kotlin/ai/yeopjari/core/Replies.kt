package ai.yeopjari.core

private val NO_WORDS = listOf("아니", "싫어", "괜찮아", "괜찮습니다", "됐어", "안 할래", "나중에")
private val YES_WORDS = listOf("응", "네", "예", "그래", "좋아", "들를", "가자", "켜", "부탁")

/** 제안에 대한 짧은 대답을 판단한다. 거절 표현을 먼저 본다("괜찮아요"는 거절로 본다). */
fun isYes(answer: String?): Boolean {
    val text = answer?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (NO_WORDS.any { text.contains(it) }) return false
    return YES_WORDS.any { text.contains(it) }
}
