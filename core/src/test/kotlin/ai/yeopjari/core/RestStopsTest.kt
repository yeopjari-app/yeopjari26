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
}
