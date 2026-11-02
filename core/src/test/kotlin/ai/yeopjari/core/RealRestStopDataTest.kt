package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 앱에 넣은 실제 공공데이터(한국도로공사 표준데이터 CSV)를 그대로 읽어 본다. */
class RealRestStopDataTest {
    private val assets = File("../app/src/main/assets")
    private fun load(file: String, kind: RestStopKind) = RestStopCsv.parse(File(assets, file).readText(), kind)
    private val serviceAreas = load("service_areas.csv", RestStopKind.SERVICE_AREA)
    private val shelters = load("drowsy_shelters.csv", RestStopKind.DROWSY_SHELTER)
    private val all = serviceAreas.stops + shelters.stops

    @Test
    fun `모든 줄을 읽고 행선지를 안다`() {
        assertTrue(serviceAreas.missing.isEmpty() && shelters.missing.isEmpty())
        assertEquals(0, serviceAreas.skipped + shelters.skipped)
        assertTrue(serviceAreas.stops.size >= 200)
        assertTrue(shelters.stops.size >= 200)
        // 괄호에 행선지가 있는 휴게소와 "○○종점"이 있는 졸음쉼터는 모두 좌표를 찾는다(목록 누락 방지).
        val unknown = all.filter { it.direction?.endsWith("방향") == true && it.direction != "양방향" && it.towards == null }
        assertTrue("행선지 좌표 없음: ${unknown.map { it.direction }.toSet()}", unknown.isEmpty())
    }

    @Test
    fun `경부선 안성 남쪽에서 부산 방향이면 건너편 원곡 졸음쉼터가 아니라 연곡 졸음쉼터`() {
        val next = nextRestStop(GeoPoint(37.010, 127.147), 165.0, all, 30.0)
        assertEquals("연곡 졸음쉼터", next?.stop?.name)
        assertEquals("부산방향", next?.stop?.direction)
    }

    @Test
    fun `같은 곳을 서울 방향으로 달리면 원곡 졸음쉼터`() {
        val next = nextRestStop(GeoPoint(36.990, 127.157), 345.0, all, 30.0)
        assertEquals("원곡 졸음쉼터", next?.stop?.name)
    }
}
