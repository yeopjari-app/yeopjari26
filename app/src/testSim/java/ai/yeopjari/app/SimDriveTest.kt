package ai.yeopjari.app

import ai.yeopjari.app.ports.MediaSource
import ai.yeopjari.core.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 주행: 남은 거리가 줄고, 끝나면 다시 돌아 휴게소가 계속 나온다. */
class SimDriveTest {
    private val drive = SimulatedDrive(object : MediaSource { override fun state(nowMs: Long) = MediaState() })

    @Test fun remainingDistanceDecreasesAndLoops() {
        assertEquals(380.0, drive.remainingKm(), 1e-6)
        drive.advance(100.0)
        assertEquals(280.0, drive.remainingKm(), 1e-6)
        drive.advance(300.0)                                   // 400km: 목적지를 지나 다시 380km 부터
        assertEquals(360.0, drive.remainingKm(), 1e-6)
    }

    @Test fun positionStaysOnTheLoopedStretch() {
        repeat(100) { drive.advance(10.0) }                    // 1000km 를 달려도
        val lat = drive.currentPoint.lat
        assertTrue("위도 $lat", lat <= SimulatedDrive.START.lat && lat >= SimulatedDrive.START.lat - SimulatedDrive.LOOP_KM / 111.0)
    }
}
