package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoffeeNapTest {
    @Test
    fun `커피를 좋아한다고 들었고 카페가 있으면 기억과 가게 이름을 함께 말한다`() {
        assertEquals(
            "아까 커피 좋아하신다고 하셨죠? 여기 까페베네 있어요. 커피 한 잔 드시고 바로 15분 주무실래요? 깰 때쯤 카페인이 돌아서 더 개운해요.",
            CoffeeNap.offer(true, "까페베네"),
        )
    }

    @Test
    fun `카페 자료가 없으면 가게 이름은 말하지 않는다`() {
        // 카페 자료가 없으면 "커피 한 잔 드시고"라고 권하지 않고, 가진 커피가 있는지부터 묻는다.
        assertNull(CoffeeNap.offer(true, null))
        assertNull(CoffeeNap.offer(null, null))
        val line = CoffeeNap.carryLine(true)
        assertEquals("아까 커피 좋아하신다고 하셨죠? 혹시 차에 마실 커피 있으세요?", line)
        assertFalse(line.contains("여기"))
        assertEquals(CoffeeNap.ASK_CARRY, CoffeeNap.carryLine(null))
    }

    @Test
    fun `좋아하는지 모르면 먼저 묻고, 안 마시면 커피 없이 낮잠만`() {
        assertNull(CoffeeNap.offer(null, "할리스"))
        assertEquals("여기 할리스 있어요. 커피 한 잔 드시고 바로 15분 주무실래요? 깰 때쯤 카페인이 돌아서 더 개운해요.", CoffeeNap.afterYes("할리스"))
        assertEquals(CoffeeNap.NAP_ONLY, CoffeeNap.offer(false, "할리스"))
    }

    @Test
    fun `대답에서 커피 취향을 읽고 저장한다`() {
        assertEquals(true, coffeePreference("음… 다섯 시간쯤? 커피 마시면 괜찮아."))
        assertEquals(true, coffeePreference("아메리카노 좋아"))
        assertEquals(false, coffeePreference("커피 안 마셔"))
        assertEquals(false, coffeePreference("커피는 못 마셔요"))
        assertEquals(false, coffeePreference("카페인 싫어"))
        assertNull(coffeePreference("다섯 시간쯤"))
        val m = DriverMemory()
        assertTrue(m.noteCoffee("커피 마시면 괜찮아"))
        assertFalse("같은 값이면 바뀌지 않음", m.noteCoffee("커피 좋아"))
        assertEquals(true, DriverMemory.parse(m.serialize()).likesCoffee)
        assertTrue(m.noteCoffee("이제 커피 끊었어"))
        assertEquals(false, DriverMemory.parse(m.serialize()).likesCoffee)
        assertNull(DriverMemory.parse(null).likesCoffee)
    }
}
