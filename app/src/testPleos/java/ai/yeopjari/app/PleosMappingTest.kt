package ai.yeopjari.app

import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.yeopjari.core.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pleos Vehicle SDK 2.3.2 의 실제 값 타입으로 "차가 이렇게 알려 오면 앱은 이렇게 받아들인다"를 확인한다.
 * 실제 차·에뮬레이터 연결은 아니고, 받은 값을 옮기는 규칙의 검사다.
 */
class PleosMappingTest {
    @Test
    fun `기어 P·R·N·D 와 수동 단을 앱 기어로 옮긴다`() {
        assertEquals(Gear.PARK, mapGear(VehicleGear.GEAR_PARK))
        assertEquals(Gear.REVERSE, mapGear(VehicleGear.GEAR_REVERSE))
        assertEquals(Gear.NEUTRAL, mapGear(VehicleGear.GEAR_NEUTRAL))
        assertEquals(Gear.DRIVE, mapGear(VehicleGear.GEAR_DRIVE))
        assertEquals(Gear.DRIVE, mapGear(VehicleGear.GEAR_3))
        assertEquals(Gear.UNKNOWN, mapGear(VehicleGear.GEAR_UNKNOWN))
        assertEquals(Gear.UNKNOWN, mapGear(null))
        // Android Automotive 표준 GEAR_SELECTION 값도 같은 기어로 옮긴다
        assertEquals(Gear.PARK, mapCarGear(4))
        assertEquals(Gear.REVERSE, mapCarGear(2))
        assertEquals(Gear.NEUTRAL, mapCarGear(1))
        assertEquals(Gear.DRIVE, mapCarGear(8))
        assertEquals(Gear.DRIVE, mapCarGear(64))
        assertEquals(Gear.UNKNOWN, mapCarGear(0))
    }

    @Test
    fun `방향지시등은 좌우 어느 쪽이든 켜지면 바쁜 순간이다`() {
        assertTrue(isTurnSignalOn(TurnSignalValue.LEFT))
        assertTrue(isTurnSignalOn(TurnSignalValue.RIGHT))
        assertFalse(isTurnSignalOn(TurnSignalValue.OFF))
        assertFalse(isTurnSignalOn(null))
    }

    @Test
    fun `운전자 주의 경고등이 켜지거나 깜빡이면 차량 졸음 경고다`() {
        fun lamps(vararg l: Pair<TelltaleType, TelltaleState>) = Telltales(l.map { TelltaleData(it.first, it.second, 0, 0, 0) })
        assertTrue(isDriverAttentionWarning(lamps(TelltaleType.DRIVER_ATTENTION to TelltaleState.ON)))
        assertTrue(isDriverAttentionWarning(lamps(TelltaleType.DRIVER_ATTENTION to TelltaleState.BLINK)))
        assertFalse(isDriverAttentionWarning(lamps(TelltaleType.DRIVER_ATTENTION to TelltaleState.OFF)))
        // 다른 경고등만 켜진 것은 졸음 경고가 아니다
        val other = TelltaleType.entries.first { it != TelltaleType.DRIVER_ATTENTION }
        assertFalse(isDriverAttentionWarning(lamps(other to TelltaleState.ON)))
        assertFalse(isDriverAttentionWarning(Telltales()))
        assertFalse(isDriverAttentionWarning(null))
    }

    @Test
    fun `차로 유지 보조와 차로 이탈 개입을 표시등으로 읽는다`() {
        fun lamps(vararg l: Pair<TelltaleType, TelltaleState>) = Telltales(l.map { TelltaleData(it.first, it.second, 0, 0, 0) })
        assertTrue(isLaneAssistOn(lamps(TelltaleType.LKA to TelltaleState.ON)))
        assertTrue(isLaneAssistOn(lamps(TelltaleType.LFA to TelltaleState.ON)))
        assertFalse(isLaneAssistOn(lamps(TelltaleType.LKA to TelltaleState.OFF)))
        assertFalse(isLaneAssistOn(null))
        assertTrue(isLaneIntervention(lamps(TelltaleType.ELK to TelltaleState.ON)))
        assertTrue(isLaneIntervention(lamps(TelltaleType.LKA to TelltaleState.BLINK)))
        assertFalse(isLaneIntervention(lamps(TelltaleType.LKA to TelltaleState.ON)))
        assertFalse(isLaneIntervention(lamps(TelltaleType.LFA to TelltaleState.BLINK)))
    }

    @Test
    fun `급제동은 브레이크를 밟은 채 2초에 시속 12km 이상 줄었을 때만`() {
        assertTrue(isHardBraking(true, listOf(100f, 94f, 86f)))
        assertFalse(isHardBraking(false, listOf(100f, 94f, 86f)))
        assertFalse(isHardBraking(true, listOf(100f, 97f, 92f)))
        assertFalse(isHardBraking(true, listOf(100f, 80f)))
    }
}
