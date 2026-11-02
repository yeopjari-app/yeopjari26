package ai.yeopjari.core

import kotlin.math.roundToInt

/** 어젯밤 잠. 출발할 때 한 번 묻는다. */
enum class SleepBand(val label: String) {
    ENOUGH("7시간 이상"),
    SHORT("5~7시간"),
    VERY_SHORT("5시간 미만"),
    UNKNOWN("모름"),
}

/**
 * "어젯밤 몇 시간 주무셨어요?"에 대한 대답을 읽는다. 숫자("5시간", "다섯 시간 반")를 먼저 보고,
 * 숫자가 없으면 "푹 잤어"·"못 잤어"·"밤샜어" 같은 말로 판단한다.
 */
object SleepAnswer {
    private val KO = mapOf(
        "서너" to 3, "너덧" to 4, "대여섯" to 5, "예닐곱" to 6, "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8,
        "일곱" to 7, "여섯" to 6, "다섯" to 5, "네" to 4, "세" to 3, "두" to 2, "한" to 1,
    )
    // 긴 말을 먼저 둬서 "열두 시간"이 "두 시간"으로 읽히지 않게 한다. "6~7시간"·"6에서 7시간"·"다섯 여섯 시간"은 앞(적은 쪽)을 쓴다.
    private val KO_ALT = KO.keys.joinToString("|")
    private val WORDS = Regex("($KO_ALT)(?:(?:에서|~|-|,|이나|or)?(?:$KO_ALT))?시간")
    private val DIGITS = Regex("""(\d+(?:\.\d+)?)(?:(?:[~-]|에서)\d+(?:\.\d+)?)?시간""")
    /** "푹 못 잤어", "잘 안 잤어", "충분하지 않아", "개운하지 않네"처럼 부정된 좋은 말. */
    private val NEGATED_GOOD = Regex("(푹|잘|충분|많이|개운)\\S{0,3}(못|안|않|아니)")

    fun hours(text: String): Double? {
        val t = text.replace(" ", "")
        val half = if (t.contains("시간반")) .5 else 0.0
        // "일곱 시간 자려다 세 시간 잤어"처럼 여럿이면 적은 쪽(안전한 쪽)을 쓴다.
        val found = DIGITS.findAll(t).map { it.groupValues[1].toDouble() } + WORDS.findAll(t).map { KO.getValue(it.groupValues[1]).toDouble() }
        return found.minOrNull()?.plus(half)
    }

    fun parse(text: String): SleepBand {
        val t = text.replace(" ", "")
        if ("전에일어" in t || "시간전" in t) return SleepBand.UNKNOWN  // "한 시간 전에 일어났어"는 잔 시간이 아니다
        hours(t)?.let { h ->
            return when {
                h >= 7 -> SleepBand.ENOUGH
                h >= 5 -> SleepBand.SHORT
                else -> SleepBand.VERY_SHORT
            }
        }
        return when {
            "잘못잤" in t || "잘못자" in t -> SleepBand.SHORT
            NEGATED_GOOD.containsMatchIn(t) -> SleepBand.SHORT
            listOf("밤샜", "밤새", "밤을샜", "밤을새", "한숨도", "거의못", "하나도못", "못잤", "못자", "자지못", "안잤", "못잠").any { it in t } -> SleepBand.VERY_SHORT
            listOf("조금밖에", "좀못", "별로", "부족", "설쳤", "덜잤").any { it in t } -> SleepBand.SHORT
            listOf("푹", "잘잤", "충분", "많이잤", "개운").any { it in t } -> SleepBand.ENOUGH
            else -> SleepBand.UNKNOWN
        }
    }
}

/**
 * 출발 전에 첫 휴식 시점을 정한다(졸린 뒤가 아니라 졸리기 전에).
 *
 * 기준은 2시간 운전마다 한 번 쉬기(사업용 차량의 "2시간 연속운전 후 15분 휴게" 규정과 같은 간격)이고,
 * 어젯밤 잠이 짧으면 앞당긴다. 전날 잠이 5~6시간이면 7시간 이상일 때보다 사고 위험이 약 2배,
 * 4~5시간이면 약 4배라는 연구(AAA Foundation, Tefft 2016)를 따라 [shortMin]·[veryShortMin] 으로 줄인다.
 * 새벽(0~6시)·오후(14~16시) 졸음 시간대에 달리면 [vulnerableFactor] 를 곱한다. 값은 15분 단위로 맞춘다.
 */
class RestPlanner(
    private val baseMin: Int = 120,
    private val shortMin: Int = 90,
    private val veryShortMin: Int = 60,
    private val vulnerableFactor: Double = .75,
    private val minMin: Int = 45,
    private val avgKph: Double = 90.0,
) {
    /**
     * [riskAt]: 졸음 예보에서 위험(KSS ≥ 8)이 처음 오는 때, 출발 기준 분. 예보가 없거나 도착 전에 안 오면 null.
     * [peakAt]: 예보에서 가장 졸린 때(출발 기준 분). 위험이 있을 때만 채운다.
     */
    data class Plan(val firstRestMin: Int, val vulnerable: Boolean, val riskAt: Int? = null, val peakAt: Int? = null) {
        /** 약속한 휴식이 위험보다 늦거나 같으면(위험이 너무 일찍 온다) true. 이때는 "그 전에"가 아니라 "바로" 쉰다. */
        val restAfterRisk: Boolean get() = riskAt != null && firstRestMin >= riskAt
    }

    /** 출발 시각 [hour], 남은 거리 [remainingKm](모르면 null). 쉬기 전에 도착하면 null. */
    fun plan(sleep: SleepBand, hour: Int, remainingKm: Double?): Plan? {
        val base = when (sleep) {
            SleepBand.VERY_SHORT -> veryShortMin
            SleepBand.SHORT -> shortMin
            SleepBand.ENOUGH, SleepBand.UNKNOWN -> baseMin
        }
        val vulnerable = isVulnerable(hour)
        val min = roundTo15(if (vulnerable) base * vulnerableFactor else base.toDouble()).coerceAtLeast(minMin)
        if (remainingKm != null && remainingKm / avgKph * 60 <= min) return null
        return Plan(min, vulnerable)
    }

    /**
     * 졸음 예보를 반영한다. 예보상 위험이 오기 [leadMin]분 전까지는 쉬도록, 그보다 늦은 약속이면 앞당긴다.
     * 위험이 오기 전에 이미 쉬게 되어 있으면 약속은 그대로 두고 언제 위험한지만 함께 알려 준다.
     */
    fun withForecast(plan: Plan, forecast: FatigueForecast.Forecast, leadMin: Int = 30): Plan {
        val risk = forecast.riskAt ?: return plan
        val latest = ((risk - leadMin) / 15 * 15).coerceAtLeast(minMin)
        return plan.copy(firstRestMin = minOf(plan.firstRestMin, latest), riskAt = risk, peakAt = forecast.peak.minute)
    }

    /**
     * [plan]에 예보까지 반영한 계획. 짧은 길이라 [plan]이 null 이어도, 도착 전에 예보상 위험이 오면 그 전에 쉬는 계획을 만든다.
     * 예보를 반영한 뒤에도 쉬기 전에 도착하면 null.
     */
    fun plan(sleep: SleepBand, hour: Int, remainingKm: Double?, forecast: FatigueForecast.Forecast?): Plan? {
        val base = plan(sleep, hour, null) ?: return null
        val p = forecast?.let { withForecast(base, it) } ?: base
        val tripMin = remainingKm?.let { it / avgKph * 60 }
        if (tripMin != null) {
            val riskBeforeArrival = p.riskAt != null && p.riskAt < tripMin
            if (tripMin <= p.firstRestMin && !riskBeforeArrival) return null
            if (tripMin <= p.firstRestMin) return p.copy(firstRestMin = minOf(p.firstRestMin, p.riskAt!!.coerceAtLeast(0)))
        }
        return p
    }

    /** 휴식을 마친 뒤 다음 휴식까지. 잠이 많이 부족했던 날은 계속 짧게 간다. */
    fun nextAfterRestMin(sleep: SleepBand, hour: Int): Int {
        val base = if (sleep == SleepBand.VERY_SHORT) shortMin else baseMin
        return roundTo15(if (isVulnerable(hour)) base * vulnerableFactor else base.toDouble()).coerceAtLeast(minMin)
    }

    /** 잠이 짧았던 날은 확인 간격도 줄인다. */
    fun checkIntervalFactor(sleep: SleepBand): Double = when (sleep) {
        SleepBand.VERY_SHORT -> .7
        SleepBand.SHORT -> .85
        else -> 1.0
    }

    private fun isVulnerable(hour: Int) = hour in 0..5 || hour in 14..15
    private fun roundTo15(min: Double) = ((min / 15).roundToInt() * 15)
}

/** 60 → "한 시간", 90 → "한 시간 반", 75 → "한 시간 15분", 45 → "45분". */
fun spokenDuration(min: Int): String {
    val h = min / 60
    val m = min % 60
    val hours = listOf("", "한", "두", "세", "네", "다섯")
    val hs = if (h in 1..5) "${hours[h]} 시간" else if (h > 0) "${h}시간" else ""
    val ms = when {
        m == 0 -> ""
        m == 30 && h > 0 -> "반"
        else -> "${m}분"
    }
    return listOf(hs, ms).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 출발할 때 들려줄 휴식 계획 한마디. [departMinuteOfDay]를 주면 예보한 위험 시각도 말한다. */
fun restPlanLine(sleep: SleepBand, plan: RestPlanner.Plan, departMinuteOfDay: Int? = null): String {
    val whenText = "${spokenDuration(plan.firstRestMin)}쯤 뒤"
    val why = when (sleep) {
        SleepBand.VERY_SHORT -> "잠이 많이 짧았네요. "
        SleepBand.SHORT -> "조금 부족하게 주무셨네요. "
        SleepBand.ENOUGH -> "잘 주무셨네요. "
        SleepBand.UNKNOWN -> ""
    }
    val risk = plan.riskAt
    if (risk != null && departMinuteOfDay != null) {
        val at = FatigueForecast.spokenClock(departMinuteOfDay + risk)
        val peak = plan.peakAt?.takeIf { it > risk }?.let { FatigueForecast.spokenClock(departMinuteOfDay + it) }
        val forecastText = if (peak != null && peak != at) "${at}쯤부터 졸음이 심해지고, ${peak}쯤 가장 졸릴 거예요." else "${at}쯤 가장 졸릴 거예요."
        // 위험이 너무 일찍 와서 약속한 휴식이 그보다 늦으면 "그 전에"라고 하지 않고 바로 쉬자고 한다.
        if (plan.restAfterRisk) return "$why$forecastText 졸음이 곧 올 수 있으니, 가까운 휴게소에서 바로 한 번 쉬어 가요. 지금 찾아 드릴게요."
        return "$why$forecastText 그 전에, ${whenText}에 한 번 쉬어 가요. 그때 가까운 휴게소를 찾아 드릴게요."
    }
    val time = if (plan.vulnerable) "졸리기 쉬운 시간대라 " else ""
    return "$why${time}${whenText}에 한 번 쉬어 가요. 그때 가까운 휴게소를 찾아 드릴게요."
}
