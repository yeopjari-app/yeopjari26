package ai.yeopjari.core

/**
 * 가족에게 보내는 문자. 운전자가 미리 등록한 번호로만 보내고, 평소에는 휴식을 시작할 때와 다시 출발할 때 두 번만 보낸다.
 * 깨우기에도 대답이 끊긴 위급한 때에만 한 번 더 알리고, 운전자가 다시 대답하면 바로 안심 문자를 보낸다.
 * 위치는 휴게소 이름까지만 적는다(좌표·경로는 보내지 않는다).
 */
object FamilyNotice {
    /** 깨우기에도 대답이 없을 때. [nearestStop] 은 진행 방향 가장 가까운 쉼터 이름, [km] 는 그곳까지 거리. */
    fun noResponse(nearestStop: String?, km: Int?): String {
        val near = if (nearestStop != null && km != null) " 가까운 쉼터는 ${km}km 앞 ${particle(nearestStop, "이에요", "예요")}." else ""
        return "[옆자리] 운전 중 졸음 신호가 이어지고, 깨워도 대답이 없어요. 전화를 걸어 깨워 주세요(차에서 핸즈프리로 받을 수 있어요).$near"
    }

    /** 위급 알림 뒤 운전자가 다시 대답했을 때. */
    fun respondedAgain(): String = "[옆자리] 다시 대답했어요. 가까운 쉼터에서 쉬어 가도록 안내하고 있어요."

    /** 휴식 시작. [resumeHour]/[resumeMinute] 은 다시 출발할 예정 시각(현지 시각). */
    fun restStarted(stopName: String?, napMinutes: Int, resumeHour: Int, resumeMinute: Int): String {
        val place = stopName?.let { "지금 ${it}에서" } ?: "지금 안전한 곳에 차를 세우고"
        return "[옆자리] $place ${napMinutes}분 쉬어 가는 중이에요. ${clock(resumeHour, resumeMinute)}쯤 다시 출발할 예정이에요.\n" +
            "졸음 신호가 보여서 휴식을 안내했어요. 깨어나면 다시 알려 드릴게요."
    }

    fun resumed(stopName: String?): String =
        "[옆자리] " + (stopName?.let { "${it}에서 " } ?: "") + "푹 쉬고 다시 출발했어요."

    /** "오전 2:47" / "오후 3:05". */
    fun clock(hour: Int, minute: Int): String {
        val h = ((hour % 24) + 24) % 24
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$ampm $h12:${minute.toString().padStart(2, '0')}"
    }

    /**
     * 한국 휴대전화 번호만 받는다(010 등 01로 시작하는 10~11자리). 하이픈·공백·+82 는 정리한다.
     * 형식이 맞지 않으면 null.
     */
    fun normalizePhone(input: String?): String? {
        var digits = input.orEmpty().filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+82")) digits = "0" + digits.removePrefix("+82").trimStart('0')
        digits = digits.filter { it.isDigit() }
        return digits.takeIf { it.length in 10..11 && it.startsWith("01") }
    }
}
