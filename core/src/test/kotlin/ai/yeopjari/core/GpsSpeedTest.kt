package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSpeedTest {
    // 위도 1도 ≈ 111.2km. 1초에 0.00025도 북쪽 ≈ 27.8m/s ≈ 시속 100km
    private fun drive(g: GpsSpeed, seconds: Int, startMs: Long = 0L) {
        for (i in 0..seconds) g.update(36.0 + 0.00025 * i, 128.0, startMs + i * 1_000L)
    }

    @Test
    fun `연속 위치로 시속을 계산한다`() {
        val g = GpsSpeed()
        drive(g, 10)
        assertEquals(100f, g.speedKph(10_000, Gear.DRIVE), 2f)
    }

    @Test
    fun `수신기가 준 속도가 있으면 그것을 쓴다`() {
        val g = GpsSpeed()
        g.update(36.0, 128.0, 0, gpsKph = 87f)
        assertEquals(87f, g.speedKph(500, Gear.DRIVE), 0.01f)
    }

    @Test
    fun `터널에서 위치가 끊겨도 D단이면 마지막 속도를 유지한다`() {
        val g = GpsSpeed()
        drive(g, 10)
        val inTunnel = 10_000L + 4 * 60_000L
        assertEquals(100f, g.speedKph(inTunnel, Gear.DRIVE), 2f)
        assertTrue(g.holding(inTunnel, Gear.DRIVE))
        assertEquals(0f, g.speedKph(10_000L + 11 * 60_000L, Gear.DRIVE), 0f)
    }

    @Test
    fun `P단이면 바로 0이고 기어를 모르면 잠깐만 유지한다`() {
        val g = GpsSpeed()
        drive(g, 10)
        assertEquals(0f, g.speedKph(10_500, Gear.PARK), 0f)
        assertEquals(100f, g.speedKph(18_000, Gear.UNKNOWN), 2f)
        assertEquals(0f, g.speedKph(25_000, Gear.UNKNOWN), 0f)
        assertFalse(g.holding(25_000, Gear.UNKNOWN))
    }

    @Test
    fun `순간이동 같은 튀는 위치는 무시한다`() {
        val g = GpsSpeed()
        drive(g, 5)
        g.update(37.0, 128.0, 6_000) // 1초에 100km 이상
        assertEquals(100f, g.speedKph(6_000, Gear.DRIVE), 2f)
    }

    @Test
    fun `촘촘한 위치(0점3초 미만)도 거리가 쌓여 속도가 나온다`() {
        val g = GpsSpeed()
        // 0.2초마다 한 점, 시속 100km(0.2초에 약 5.6m)
        for (i in 0..50) g.update(36.0 + 0.00005 * i, 128.0, i * 200L)
        assertEquals(100f, g.speedKph(10_000, Gear.DRIVE), 3f)
    }

    @Test
    fun `P단으로 오래 서 있다가 다시 잡히면 예전 속도를 섞지 않는다`() {
        val g = GpsSpeed()
        drive(g, 10)
        assertEquals(0f, g.speedKph(60_000, Gear.PARK), 0f)
        g.update(36.01, 128.0, 120_000)                       // 한참 뒤 첫 위치: 아직 속도를 모른다
        assertEquals(0f, g.speedKph(120_500, Gear.DRIVE), 0.01f)
        g.update(36.01 + 0.00005, 128.0, 121_000)            // 1초에 약 5.6m ≈ 시속 20km
        assertEquals(20f, g.speedKph(121_000, Gear.DRIVE), 2f)
    }
}
