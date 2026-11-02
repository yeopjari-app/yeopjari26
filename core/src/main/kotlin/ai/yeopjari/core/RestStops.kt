package ai.yeopjari.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

enum class RestStopKind { DROWSY_SHELTER, SERVICE_AREA }

/** 쉬는 곳의 편의시설. 공공데이터의 "○○유무"·"○○수" 항목에서 읽는다. 앞쪽일수록 안내할 때 먼저 말한다. */
enum class Amenity(val label: String) {
    TOILET("화장실"),
    CONVENIENCE("편의점"),
    FOOD("식당"),
    EV_CHARGER("전기차 충전"),
    FUEL("주유소"),
    LPG("LPG 충전"),
    PHARMACY("약국"),
    NURSING_ROOM("수유실"),
}

data class RestStop(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val kind: RestStopKind,
    /** 도로노선명. 예: 경부선 */
    val route: String? = null,
    /** 도로노선방향. 예: 부산방향, 양방향 */
    val direction: String? = null,
    val amenities: Set<Amenity> = emptySet(),
    val parkingSpaces: Int? = null,
    val signatureFood: String? = null,
    /** 이 쉼터를 쓰는 차가 향하는 행선지 좌표([Destinations]). 맞은편 차로 쉼터를 거르는 데 쓴다. */
    val towards: GeoPoint? = null,
    /** 입점 브랜드 매장(공공데이터 [ServiceAreaBrands]). 자료에 없으면 빈 목록이고, 그때는 가게 이름을 말하지 않는다. */
    val brands: List<String> = emptyList(),
    /** 표준데이터의 도로노선방향 원문(상행·하행·양방향). 휴게소 브랜드 자료와 방향을 맞추는 데 쓴다. */
    val bound: String? = null,
) {
    /** 입점 브랜드 중 첫 번째 커피 전문점. 없으면 null. */
    val cafeBrand: String? get() = brands.firstOrNull { it in ServiceAreaBrands.CAFES }
}

data class NearbyRestStop(val stop: RestStop, val distanceKm: Double)

/** 직선거리 기준 가장 가까운 휴식 장소. 진행 방향을 모를 때 쓴다. */
fun nearestRestStop(from: GeoPoint, stops: List<RestStop>, maxKm: Double): NearbyRestStop? =
    stops.map { NearbyRestStop(it, distanceKm(from, it.point)) }
        .filter { it.distanceKm <= maxKm }
        .minByOrNull { it.distanceKm }

/**
 * 진행 방향 앞쪽에서 가장 가까운 휴식 장소.
 *
 * 뒤쪽이나 옆으로 크게 벗어난 곳([aheadDeg] 밖)은 뺀다. 행선지([RestStop.towards])를 아는 쉼터는 그 방향이
 * 우리 진행 방향과 90도 넘게 어긋나면 맞은편 차로 쉼터로 보고 뺀다. 고속도로 휴게소는 같은 이름이 양쪽 방향에 하나씩 있으므로,
 * 같은 이름이 [pairKm] 안에 둘 이상이면 우측 통행 기준으로 진행 방향 오른쪽에 있는 쪽을 고른다.
 * 진행 방향을 모르면 [nearestRestStop] 과 같다.
 */
fun nextRestStop(
    from: GeoPoint,
    headingDeg: Double?,
    stops: List<RestStop>,
    maxKm: Double,
    aheadDeg: Double = 70.0,
    pairKm: Double = 1.5,
): NearbyRestStop? {
    if (headingDeg == null) return nearestRestStop(from, stops, maxKm)
    val ahead = stops.mapNotNull { stop ->
        val d = distanceKm(from, stop.point)
        val off = angleDiff(bearingDeg(from, stop.point), headingDeg)
        if (d > maxKm || abs(off) > aheadDeg || !sameDirection(stop, headingDeg)) null else Triple(stop, d, d * sin(Math.toRadians(off)))
    }
    val ourSide = ahead.groupBy { baseName(it.first.name) }.values.flatMap { same ->
        same.groupBy { a -> same.first { b -> distanceKm(a.first.point, b.first.point) <= pairKm } }
            .values.map { pair -> pair.maxByOrNull { it.third }!! }
    }
    return ourSide.minByOrNull { it.second }?.let { NearbyRestStop(it.first, it.second) }
}

/** 행선지가 쉼터에서 [TOWARDS_MIN_KM] 안이면 방향이 불안정하므로 판단하지 않는다(통과). */
private fun sameDirection(stop: RestStop, headingDeg: Double): Boolean {
    val t = stop.towards ?: return true
    if (distanceKm(stop.point, t) < TOWARDS_MIN_KM) return true
    return abs(angleDiff(bearingDeg(stop.point, t), headingDeg)) <= 90.0
}

private const val TOWARDS_MIN_KM = 15.0

/** "김천휴게소(부산방향)" → "김천휴게소". 방향 표기만 다른 같은 휴게소를 짝짓는 데 쓴다. */
fun baseName(name: String): String = name.substringBefore('(').substringBefore('[').trim()

/** 안내할 때 말할 편의시설. 많아도 [max] 개까지만. */
fun RestStop.spokenAmenities(max: Int = 3): String? =
    Amenity.entries.filter { it in amenities }.take(max).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }

/** 하버사인 공식으로 두 좌표 사이 거리(km). */
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** a 에서 b 를 바라보는 방위각(도, 북쪽 0 · 동쪽 90). */
fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
    val la = Math.toRadians(a.lat)
    val lb = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lb)
    val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** 두 방위각의 차이(-180~180). 양수면 오른쪽. */
fun angleDiff(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180

/**
 * 받침에 맞춰 조사를 붙인다. 예: particle("김천휴게소", "이", "가") → "김천휴게소가".
 * 끝의 닫는 괄호는 건너뛰고 괄호 안 마지막 글자로 고른다("안성휴게소(부산방향)이" — 소리 내어 읽는 대로). 한글이 아니면 받침 없는 쪽.
 */
fun particle(word: String, afterBatchim: String, afterVowel: String): String {
    val c = word.trimEnd().trimEnd(')', ' ').lastOrNull() ?: return word + afterVowel
    val hasBatchim = c in '가'..'힣' && (c - '가') % 28 != 0
    return word + if (hasBatchim) afterBatchim else afterVowel
}

/** 다음 쉼터와, 그곳을 지나면 쉴 곳이 없는 거리. */
data class RestGap(val next: NearbyRestStop, val gapKm: Double)

/**
 * 다음 쉼터를 지나면 한동안 쉴 곳이 없는지 본다(마지막 기회 안내).
 *
 * 휴게시설 간격은 평균 15km·최대 25km 를 기준으로 삼지만, 실제로는 이를 지키지 못하는 구간이 많다
 * (2015년 국정감사 자료: 48개 노선·방향 중 4개만 충족). 다음 쉼터 뒤로 [gapKm] 안에 우리 쪽 쉼터가 없으면
 * 그 간격을 돌려준다. 앞쪽 쉼터가 [maxKm] 안에 없거나, 뒤로 이어지는 쉼터가 가까우면 null.
 */
fun lastStopBeforeGap(
    from: GeoPoint,
    headingDeg: Double?,
    stops: List<RestStop>,
    maxKm: Double,
    gapKm: Double = 40.0,
): RestGap? {
    if (headingDeg == null) return null
    val next = nextRestStop(from, headingDeg, stops, maxKm) ?: return null
    val rest = stops.filter { baseName(it.name) != baseName(next.stop.name) }
    val after = nextRestStop(next.stop.point, headingDeg, rest, gapKm)
    return if (after == null) RestGap(next, gapKm) else null
}
