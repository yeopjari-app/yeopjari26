package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test
    fun `저장한 이름으로 캐릭터를 되찾고 모르면 다정한 친구로 시작한다`() {
        Persona.entries.forEach { assertEquals(it, Persona.parse(it.name)) }
        assertEquals(Persona.FRIEND, Persona.parse(null))
        assertEquals(Persona.FRIEND, Persona.parse("UNKNOWN"))
    }

    @Test
    fun `깨우기는 교관 대사로 강하게 말한다`() {
        assertTrue(Persona.DRILL.wakeLine.startsWith("운전자!"))
        assertTrue(Persona.DRILL.rate > Persona.FRIEND.rate)
    }

    @Test
    fun `캐릭터마다 이름과 소개가 다르다`() {
        assertEquals(Persona.entries.size, Persona.entries.map { it.label }.toSet().size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.intro }.toSet().size)
    }
}
