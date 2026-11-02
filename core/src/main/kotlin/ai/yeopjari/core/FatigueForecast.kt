package ai.yeopjari.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln

/**
 * 졸음 예보. 출발할 때 어젯밤 잠·일어난 시각·지금 시각으로 이번 주행 동안의 졸음(KSS 1~9)을 미리 그린다.
 * 졸린 뒤에 경고하는 대신, 졸음이 가장 심해질 때를 먼저 알려 주고 그 전에 쉴 곳을 정하는 데 쓴다.
 *
 * 모형은 각성의 세 과정 모형(Three-Process Model, Åkerstedt & Folkard 1997, Chronobiology International 14(2))이고,
 * 값은 항공 승무원 자료로 다시 검증한 Ingre 외 2014(PLoS ONE 9(10): e108679)의 기본값을 쓴다.
 * - S(잠 압력): 깨어 있는 동안 la 쪽으로 지수 감소, 자는 동안 bl 까지 직선 회복 뒤 ha 쪽으로 지수 회복
 * - C(24시간 일주기), U(12시간 주기, 오후 처짐), W(잠 관성: 막 깼을 때의 멍함)
 * - KSS = 10.6 − 0.6 × (S + C + U + W)
 * 같은 모형으로 예측한 졸음이 한 단계 오를 때마다 실제 교통사고 위험이 1.72배였다(Åkerstedt 외 2008, AAP 40(4)).
 *
 * 사람마다 차이가 크므로 예보는 출발점일 뿐이다. 주행 중에는 대답 속도·핸들·차량 경고가 실제 상태를 알려 준다.
 */
object FatigueForecast {
    private const val LA = 2.4
    private const val HA = 14.3
    private const val D = -0.0353
    private val G = ln((14.3 - 14) / (14.3 - 7.96)) / 8          // 약 −0.381
    private const val BL = 12.2
    private const val CA = 2.5
    private const val PHASE = 16.8
    private const val UM = -0.5
    private const val UA = 0.5
    private const val WC = -5.72
    private const val WD = -1.51
    private const val S_AT_BEDTIME = 7.96                          // 평소 하루를 보내고 잠들 때의 S(모형 기본값)

    /** 이 이상이면 "졸림"(KSS 7: 졸리지만 버틸 만함). */
    const val CAUTION_KSS = 7.0

    /** 이 이상이면 "위험"(KSS 8~9에서 차로 이탈·사고 위험이 가파르게 오른다). */
    const val RISK_KSS = 8.0

    /** [minute]은 출발 기준 분(0 = 출발). */
    data class Point(val minute: Int, val kss: Double)

    data class Forecast(
        val points: List<Point>,
        /** KSS ≥ 7 이 처음 되는 때(출발 기준 분). 없으면 null. */
        val cautionAt: Int?,
        /** KSS ≥ 8 이 처음 되는 때(출발 기준 분). 없으면 null. */
        val riskAt: Int?,
        val peak: Point,
        /** 잠 시간을 몰라 7시간으로 가정했으면 true. */
        val assumedSleep: Boolean,
    )

    /**
     * @param sleepHours 어젯밤 잔 시간(모르면 null → 7시간으로 가정)
     * @param wakeMinuteOfDay 일어난 시각(자정부터 분). 모르면 평소 기상 시각(설정값, 기본 7시)
     * @param departMinuteOfDay 출발 시각(자정부터 분)
     * @param durationMin 예상 주행 시간(분)
     */
    fun forecast(sleepHours: Double?, wakeMinuteOfDay: Int, departMinuteOfDay: Int, durationMin: Int, stepMin: Int = 5): Forecast {
        val slept = (sleepHours ?: 7.0).coerceIn(0.0, 14.0)
        val sAtWake = sleepS(S_AT_BEDTIME, slept)
        val awakeAtDepart = Math.floorMod(departMinuteOfDay - wakeMinuteOfDay, 1440) / 60.0
        val points = (0..durationMin.coerceAtLeast(0) step stepMin.coerceAtLeast(1)).map { m ->
            val taw = awakeAtDepart + m / 60.0
            val tod = (departMinuteOfDay + m) / 60.0
            Point(m, kss(sAtWake, taw, tod))
        }
        return Forecast(
            points = points,
            cautionAt = points.firstOrNull { it.kss >= CAUTION_KSS }?.minute,
            riskAt = points.firstOrNull { it.kss >= RISK_KSS }?.minute,
            peak = points.maxBy { it.kss },
            assumedSleep = sleepHours == null,
        )
    }

    /** 깨어 있은 지 [taw]시간, 시각 [tod](시, 24 넘어도 됨)의 KSS. 1~9 로 자른다. */
    internal fun kss(sAtWake: Double, taw: Double, tod: Double): Double {
        val s = LA + (sAtWake - LA) * exp(D * taw)
        val c = CA * cos(2 * PI / 24 * (tod - PHASE))
        val u = UM + UA * cos(2 * PI / 12 * (tod - PHASE - 3))
        val w = WC * exp(WD * taw)
        return (10.6 - 0.6 * (s + c + u + w)).coerceIn(1.0, 9.0)
    }

    /** 잠든 때 S가 [sAtBed]이고 [hours]시간 잤을 때 깨는 순간의 S. */
    internal fun sleepS(sAtBed: Double, hours: Double): Double {
        val breakTime = (BL - sAtBed) / (G * (BL - HA))
        return if (hours <= breakTime) sAtBed + hours * G * (BL - HA)
        else HA - (HA - BL) * exp(G * (hours - breakTime))
    }

    /** 1437(23:57) + 135분 → "새벽 2시 10분". 10분 단위로 반올림한다. */
    fun spokenClock(minuteOfDay: Int): String {
        val m = Math.floorMod((minuteOfDay + 5) / 10 * 10, 1440)
        val h = m / 60
        val mm = m % 60
        val part = when (h) { 0 -> "밤"; in 1..5 -> "새벽"; in 6..11 -> "오전"; in 12..17 -> "오후"; else -> "밤" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (mm == 0) "$part ${h12}시" else "$part ${h12}시 ${mm}분"
    }
}
