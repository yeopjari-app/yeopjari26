package ai.yeopjari.core

/**
 * 거절·부정 표현. "안 들를래", "못 가", "가지 마", "말고", "싫어" 처럼 긍정 낱말이 섞여도 거절로 읽는다.
 * "안"은 낱말 앞에서만 본다("안녕", "안성"은 거절이 아니다).
 */
private val NEGATION = Regex(
    "(^|\\s)안\\s?(할|해|돼|될|들|쉴|쉬|가|갈|켜|자|먹|마|원|필요)|(^|\\s)안\\s*$|(^|\\s)못|싫|말고|지\\s?마|지\\s?않|별로|아니|아뇨|됐어|됐습|괜찮아|괜찮습|나중에|그만|패스",
)

/** 그 자체로 대답이 되는 짧은 긍정 낱말(낱말 전체가 같아야 한다: "예전", "응원"은 아니다). */
private val YES_TOKENS = setOf(
    "응", "웅", "어", "엉", "네", "넵", "넹", "예", "그래", "그래요", "좋아", "좋아요", "좋지", "좋죠", "오케이", "ok", "okay", "ㅇㅇ", "ㅇㅋ", "콜",
    "해", "해요", "해줘", "해줘요", "가요", "그럼", "물론", "당연하지", "그렇게", "그러자", "그러죠", "그럽시다",
)

/** 이렇게 시작하는 낱말은 긍정("좋아요", "부탁해", "들를게", "가자", "켜 줘", "알았어"). */
private val YES_PREFIXES = listOf("좋아", "좋지", "좋네", "좋습", "부탁", "들를", "들르", "들러", "가자", "갑시다", "켜", "그래", "그러", "알았", "알겠", "당연", "물론")

/** 긍정 접두어로 시작하지만 긍정이 아닌 낱말. */
private val NOT_YES = setOf("그래도", "그래서", "그러니까", "그러면", "그런데", "그러나", "켜져", "켜있")

/**
 * 제안에 대한 짧은 대답을 판단한다. 거절·부정 표현을 먼저 본다("괜찮아요"는 거절, "안 들를래"도 거절).
 * 긍정은 낱말 단위로만 본다: "응원", "예전", "네비" 같은 말 속의 글자로는 긍정이 되지 않는다.
 */
fun isYes(answer: String?): Boolean {
    val text = answer?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return false
    if (NEGATION.containsMatchIn(text)) return false
    val words = text.split(Regex("[\\s,.!?~…]+")).filter { it.isNotEmpty() }
    // "해 줘", "해 봐"처럼 띄어 쓴 짧은 말도 한 낱말로 본다.
    val joined = words.joinToString("")
    if (joined in setOf("해줘", "해봐", "해줘요", "해봐요", "해주세요", "켜줘", "가줘")) return true
    return words.any { w -> w !in NOT_YES && (w in YES_TOKENS || YES_PREFIXES.any { w.startsWith(it) }) }
}
