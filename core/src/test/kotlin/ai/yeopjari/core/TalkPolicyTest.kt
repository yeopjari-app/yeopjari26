package ai.yeopjari.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkPolicyTest {

    private val policy = TalkPolicy(songGapWaitMs = 60_000, interactionGraceMs = 120_000, maxDeferMs = 300_000)
    private val now = 1_000_000L

    private fun decide(stage: AlertStage = AlertStage.NORMAL, media: MediaState, waited: Long = 0) =
        policy.decide(stage, media, waited, now)

    @Test
    fun `조용한 차 안에서는 바로 말한다`() {
        assertEquals(TalkDecision.Talk(AudioMode.NONE, AudioMode.NONE), decide(media = MediaState()))
    }

    @Test
    fun `음악이 나오면 말할 때는 줄이고 들을 때는 잠깐 멈춘다`() {
        assertEquals(TalkDecision.Talk(AudioMode.DUCK, AudioMode.PAUSE), decide(media = MediaState(playing = true)))
    }

    @Test
    fun `통화 중에는 끼어들지 않고, 위험 단계나 차량 경고면 20초까지만 기다린다`() {
        val calling = MediaState(inCall = true)
        assertEquals(WaitReason.CALL, (decide(media = calling) as TalkDecision.Wait).reason)
        assertEquals(WaitReason.CALL, (decide(AlertStage.CAUTION, calling, waited = 999_999) as TalkDecision.Wait).reason)
        assertEquals(WaitReason.CALL, (decide(AlertStage.DANGER, calling, waited = 10_000) as TalkDecision.Wait).reason)
        assertTrue(decide(AlertStage.DANGER, calling, waited = 20_000) is TalkDecision.Talk)
        assertTrue(policy.decide(AlertStage.NORMAL, calling, 20_000, now, vehicleWarning = true) is TalkDecision.Talk)
        assertTrue(policy.decide(AlertStage.NORMAL, calling, 5_000, now, vehicleWarning = true) is TalkDecision.Wait)
    }

    @Test
    fun `곡이 곧 끝나면 곡 사이까지 기다린다`() {
        val d = decide(media = MediaState(playing = true, trackRemainingMs = 20_000))
        assertEquals(WaitReason.SONG_GAP, (d as TalkDecision.Wait).reason)
    }

    @Test
    fun `곡이 한참 남았거나 길이를 모르면 줄이고 바로 말한다`() {
        assertTrue(decide(media = MediaState(playing = true, trackRemainingMs = 180_000)) is TalkDecision.Talk)
        assertTrue(decide(media = MediaState(playing = true, trackRemainingMs = null)) is TalkDecision.Talk)
    }

    @Test
    fun `곡 사이 기다리기는 한 번까지만`() {
        assertTrue(decide(media = MediaState(playing = true, trackRemainingMs = 20_000), waited = 60_000) is TalkDecision.Talk)
    }

    @Test
    fun `주의 단계부터는 곡 사이를 기다리지 않는다`() {
        val d = decide(AlertStage.CAUTION, MediaState(playing = true, trackRemainingMs = 5_000))
        assertEquals(TalkDecision.Talk(AudioMode.DUCK, AudioMode.PAUSE), d)
    }

    @Test
    fun `방금 곡을 넘겼다면 깨어 있다는 신호라 조금 미룬다`() {
        val d = decide(media = MediaState(playing = true, lastInteractionMs = now - 30_000))
        assertEquals(WaitReason.DRIVER_ACTIVE, (d as TalkDecision.Wait).reason)
        assertTrue(decide(media = MediaState(playing = true, lastInteractionMs = now - 200_000)) is TalkDecision.Talk)
    }

    @Test
    fun `미루기는 합쳐서 최대 시간을 넘기지 않는다`() {
        val busy = MediaState(playing = true, trackRemainingMs = 10_000, lastInteractionMs = now - 1_000)
        assertTrue(decide(media = busy, waited = 300_000) is TalkDecision.Talk)
    }
}
