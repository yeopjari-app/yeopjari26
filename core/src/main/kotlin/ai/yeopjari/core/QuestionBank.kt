package ai.yeopjari.core

import kotlin.random.Random

/** 음성 체크 질문. [isCorrect] 는 인식된 답이 맞는지 판단한다. */
class Question(val prompt: String, val isCorrect: (answer: String) -> Boolean)

/**
 * 짧은 음성 체크 질문을 만든다. 산수 문제는 정답을 확인하고,
 * 대화형 질문은 답한 것만으로 정답 처리해 응답 속도만 본다.
 */
class QuestionBank(private val random: Random = Random.Default) {
    private val conversation = listOf(
        "오늘 목적지에 도착하면 제일 먼저 뭐 하실 거예요?",
        "요즘 즐겨 듣는 노래 있으세요?",
        "점심은 뭐 드셨어요?",
        "지금 차 안 온도는 괜찮으세요?",
    )

    fun next(): Question = if (random.nextInt(3) < 2) arithmetic() else chat()

    private fun arithmetic(): Question {
        val a = random.nextInt(2, 10)
        val b = random.nextInt(2, 10)
        return Question("$a 더하기 $b 는 얼마일까요?") { parseKoreanNumber(it) == a + b }
    }

    private fun chat(): Question =
        Question(conversation[random.nextInt(conversation.size)]) { it.isNotBlank() }
}

private val SINO = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
private val NATIVE_UNITS = listOf(
    "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "넷" to 4, "네" to 4,
    "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
)

/** "12", "십이", "열둘", "열두 개" 같은 답에서 0~99 숫자를 읽는다. 못 읽으면 null. */
fun parseKoreanNumber(text: String): Int? {
    val compact = text.replace(" ", "")
    Regex("\\d+").find(compact)?.let { return it.value.toInt() }
    parseSino(compact)?.let { return it }
    parseNative(compact)?.let { return it }
    // "칠", "이요"처럼 한 글자 한자어 수만 답한 경우
    val bare = compact.trimEnd('.', '?', '!').removeSuffix("입니다").removeSuffix("이에요").removeSuffix("예요").removeSuffix("요")
    return if (bare.length == 1) SINO[bare[0]] else null
}

private fun parseSino(s: String): Int? {
    val match = Regex("([일이삼사오육칠팔구]?)십([일이삼사오육칠팔구]?)").find(s)
    if (match != null) {
        val tens = match.groupValues[1].firstOrNull()?.let { SINO[it] } ?: 1
        val ones = match.groupValues[2].firstOrNull()?.let { SINO[it] } ?: 0
        return tens * 10 + ones
    }
    return null
}

private fun parseNative(s: String): Int? {
    val tens = when {
        s.startsWith("스물") || s.startsWith("스무") -> 20
        s.startsWith("열") -> 10
        else -> 0
    }
    val rest = s.removePrefix("스물").removePrefix("스무").removePrefix("열")
    val ones = NATIVE_UNITS.firstOrNull { rest.startsWith(it.first) }?.second ?: 0
    return if (tens + ones > 0) tens + ones else null
}
