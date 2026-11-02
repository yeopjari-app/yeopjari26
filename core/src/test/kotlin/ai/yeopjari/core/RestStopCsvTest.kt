package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestStopCsvTest {
    @Test
    fun `휴게소 표준데이터를 열 이름으로 읽는다`() {
        val csv = "﻿휴게소명,도로노선명,도로노선방향,위도,경도,주차면수,주유소유무,전기차충전소유무,화장실유무,주유소전화번호,휴게소대표음식명\r\n" +
            "가나휴게소,경부선,부산방향,36.1,128.0,120,Y,Y,Y,054-000-0000,\"국밥, 돈가스\"\r\n"
        val r = RestStopCsv.parse(csv, RestStopKind.SERVICE_AREA)
        val s = r.stops.single()
        assertEquals("가나휴게소", s.name)
        assertEquals("부산방향", s.direction)
        assertEquals(120, s.parkingSpaces)
        assertEquals("국밥, 돈가스", s.signatureFood)
        assertEquals(setOf(Amenity.FUEL, Amenity.EV_CHARGER, Amenity.TOILET), s.amenities)
    }

    @Test
    fun `졸음쉼터는 화장실 수로 화장실 유무를 안다`() {
        val csv = "졸음쉼터명,도로노선명,도로노선방향,위도,경도,주차면수,화장실수\n다라졸음쉼터,경부선,서울방향,36.5,127.6,25,2\n마바졸음쉼터,경부선,부산방향,36.6,127.6,10,0\n"
        val r = RestStopCsv.parse(csv, RestStopKind.DROWSY_SHELTER)
        assertEquals(setOf(Amenity.TOILET), r.stops[0].amenities)
        assertTrue(r.stops[1].amenities.isEmpty())
    }

    @Test
    fun `좌표가 없거나 국내 밖이면 건너뛴다`() {
        val csv = "졸음쉼터명,위도,경도\nA,,127.0\nB,51.5,0.1\nC,36.0,127.0\n"
        val r = RestStopCsv.parse(csv, RestStopKind.DROWSY_SHELTER)
        assertEquals(listOf("C 졸음쉼터"), r.stops.map { it.name })
        assertEquals(2, r.skipped)
    }

    @Test
    fun `필수 열이 없으면 무엇이 빠졌는지 알려 준다`() {
        val r = RestStopCsv.parse("이름,X,Y\nA,1,2\n", RestStopKind.SERVICE_AREA)
        assertTrue(r.stops.isEmpty())
        assertEquals(listOf("이름", "위도", "경도"), r.missing)
        assertNull(r.stops.firstOrNull())
    }

    @Test
    fun `실제 표준데이터 형식이면 이름을 읽기 좋게 고치고 행선지를 방향으로 옮긴다`() {
        val sa = "휴게소명,도로종류,도로노선번호,도로노선명,도로노선방향,위도,경도\n" +
            "건천(부산),고속국도,1,경부선,하행,35.85,129.07\n고삼호수,고속국도,40,평택제천선,양방향,37.07,127.29\n"
        val a = RestStopCsv.parse(sa, RestStopKind.SERVICE_AREA).stops
        assertEquals(listOf("건천휴게소", "고삼호수휴게소"), a.map { it.name })
        assertEquals("부산방향", a[0].direction)
        assertEquals(Destinations.point("부산"), a[0].towards)
        assertEquals("양방향", a[1].direction)
        assertNull(a[1].towards)
        val ds = "졸음쉼터명,도로노선명,도로노선방향,위도,경도\n오창,중부선,통영기점 + 하남종점,36.73,127.45\n"
        val b = RestStopCsv.parse(ds, RestStopKind.DROWSY_SHELTER).stops.single()
        assertEquals("오창 졸음쉼터", b.name)
        assertEquals("하남방향", b.direction)
        assertEquals(Destinations.point("하남"), b.towards)
    }
}
