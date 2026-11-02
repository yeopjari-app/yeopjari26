package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestStopsTest {
    private val stops = listOf(
        RestStop("a", "A", GeoPoint(37.0, 127.1), RestStopKind.DROWSY_SHELTER),
        RestStop("b", "B", GeoPoint(36.5, 127.1), RestStopKind.SERVICE_AREA),
    )

    @Test
    fun `가장 가까운 곳을 고른다`() {
        val nearby = nearestRestStop(GeoPoint(36.9, 127.1), stops, maxKm = 50.0)!!
        assertEquals("a", nearby.stop.id)
        assertEquals(11.1, nearby.distanceKm, 0.2)
    }

    @Test
    fun `반경 밖이면 없음`() {
        assertNull(nearestRestStop(GeoPoint(35.0, 127.1), stops, maxKm = 30.0))
    }

    @Test
    fun `남쪽으로 달리면 지나온 곳은 빼고 앞쪽을 고른다`() {
        val next = nextRestStop(GeoPoint(36.9, 127.1), headingDeg = 180.0, stops = stops, maxKm = 80.0)!!
        assertEquals("b", next.stop.id)
    }

    @Test
    fun `같은 휴게소가 양쪽 방향에 있으면 진행 방향 오른쪽을 고른다`() {
        // 남쪽으로 달릴 때 오른쪽은 서쪽(경도가 작은 쪽)
        val pair = listOf(
            RestStop("w", "가나휴게소(부산방향)", GeoPoint(36.50, 127.098), RestStopKind.SERVICE_AREA),
            RestStop("e", "가나휴게소(서울방향)", GeoPoint(36.50, 127.102), RestStopKind.SERVICE_AREA),
        )
        assertEquals("w", nextRestStop(GeoPoint(36.6, 127.1), 180.0, pair, 50.0)!!.stop.id)
        assertEquals("e", nextRestStop(GeoPoint(36.4, 127.1), 0.0, pair, 50.0)!!.stop.id)
    }

    @Test
    fun `안내할 편의시설은 중요한 순서로 세 개까지`() {
        val s = RestStop("x", "X", GeoPoint(36.0, 127.0), RestStopKind.SERVICE_AREA, amenities = setOf(Amenity.FUEL, Amenity.TOILET, Amenity.EV_CHARGER, Amenity.FOOD))
        assertEquals("화장실, 식당, 전기차 충전", s.spokenAmenities())
    }

    @Test
    fun `받침에 맞춰 조사를 붙인다`() {
        assertEquals("김천휴게소가", particle("김천휴게소", "이", "가"))
        assertEquals("청성 졸음쉼터를", particle("청성 졸음쉼터", "을", "를"))
        assertEquals("서울만남의광장을", particle("서울만남의광장", "을", "를"))
        assertEquals("안성휴게소(부산방향)이", particle("안성휴게소(부산방향)", "이", "가"))
        assertEquals("죽전휴게소(서울방향)을", particle("죽전휴게소(서울방향)", "을", "를"))
    }

    @Test
    fun `맞은편 차로에만 있는 쉼터는 행선지로 걸러 낸다`() {
        val here = GeoPoint(36.60, 127.10)
        val seoulBound = RestStop("x", "가 졸음쉼터", GeoPoint(36.50, 127.10), RestStopKind.DROWSY_SHELTER, towards = Destinations.point("서울"))
        val busanBound = RestStop("y", "나 졸음쉼터", GeoPoint(36.40, 127.10), RestStopKind.DROWSY_SHELTER, towards = Destinations.point("부산"))
        // 남쪽(부산 방향)으로 달리면 더 가까운 서울방향 쉼터는 건너편이라 빼고, 부산방향 쉼터를 고른다.
        assertEquals("y", nextRestStop(here, 180.0, listOf(seoulBound, busanBound), 30.0)?.stop?.id)
        // 북쪽으로 달리면 반대.
        assertEquals("x", nextRestStop(GeoPoint(36.30, 127.10), 0.0, listOf(seoulBound, busanBound), 30.0)?.stop?.id)
    }
}
