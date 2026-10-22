package ai.yeopjari.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

enum class RestStopKind { DROWSY_SHELTER, SERVICE_AREA }

data class RestStop(val id: String, val name: String, val point: GeoPoint, val kind: RestStopKind)

data class NearbyRestStop(val stop: RestStop, val distanceKm: Double)

/** 직선거리 기준 가장 가까운 휴식 장소. 진행 방향 고려는 내비 경로 연동 단계에서 추가한다. */
fun nearestRestStop(from: GeoPoint, stops: List<RestStop>, maxKm: Double): NearbyRestStop? =
    stops.map { NearbyRestStop(it, distanceKm(from, it.point)) }
        .filter { it.distanceKm <= maxKm }
        .minByOrNull { it.distanceKm }

/** 하버사인 공식으로 두 좌표 사이 거리(km). */
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
