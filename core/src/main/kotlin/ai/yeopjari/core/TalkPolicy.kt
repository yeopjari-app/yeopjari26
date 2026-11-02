package ai.yeopjari.core

/**
 * 차 안 소리 상태. [trackRemainingMs] 는 지금 곡이 끝날 때까지 남은 시간(알 수 없으면 null),
 * [lastInteractionMs] 는 운전자가 곡 넘기기·볼륨 조절을 한 마지막 시각, [title] 은 지금 곡 제목(알 수 없으면 null).
 */
data class MediaState(
    val playing: Boolean = false,
    val title: String? = null,
    val inCall: Boolean = false,
    val trackRemainingMs: Long? = null,
    val lastInteractionMs: Long? = null,
)

/** 말하거나 듣는 동안 음악을 어떻게 할지. 순서대로 강해진다. */
enum class AudioMode { NONE, DUCK, PAUSE }

enum class WaitReason { CALL, SONG_GAP, DRIVER_ACTIVE }

sealed interface TalkDecision {
    /** 지금 말을 건다. 말할 때는 [speak], 대답을 들을 때는 [listen] 으로 음악을 다룬다. */
    data class Talk(val speak: AudioMode, val listen: AudioMode) : TalkDecision

    /** [ms] 뒤에 다시 판단한다. */
    data class Wait(val ms: Long, val reason: WaitReason) : TalkDecision
}

/**
 * 음악·통화 중에 언제, 어떻게 말을 걸지 정한다. 라디오 진행자처럼:
 * - 통화 중에는 끼어들지 않는다(통화 자체가 깨어 있게 하는 대화다). 단, 위험 단계이거나 차량 경고(차로 이탈·
 *   주의 경고 등)가 켜져 있으면 통화가 길어져도 [urgentCallCapMs](약 20초)까지만 기다리고 말을 건다.
 *   통화 중 무응답은 대답 속도로 쓰지 않으므로, 이때 말하는 것은 짧은 안전 확인뿐이다.
 * - 정상 단계에서 곡이 곧 끝나면 곡 사이 빈틈까지 기다린다.
 * - 운전자가 방금 곡을 넘기거나 볼륨을 만졌다면 깨어 있다는 신호이니 조금 미룬다.
 * - 통화를 뺀 미루기는 모두 합쳐 [maxDeferMs] 를 넘기지 않는다.
 * - 주의·위험 단계는 곡 사이를 기다리지 않는다.
 * 말할 때는 음악을 줄이고(DUCK), 대답을 들을 때는 마이크가 노래 대신 운전자를 듣도록 잠깐 멈춘다(PAUSE).
 */
class TalkPolicy(
    private val songGapWaitMs: Long = 60_000,
    private val interactionGraceMs: Long = 120_000,
    private val maxDeferMs: Long = 5 * 60_000,
    private val pollMs: Long = 1_000,
    private val urgentCallCapMs: Long = 20_000,
) {
    /**
     * [waitedMs] 는 이번 확인을 위해 이미 기다린 시간, [nowMs] 는 [MediaState.lastInteractionMs] 와 같은 시계.
     * [vehicleWarning] 은 차량이 지금 졸음·차로 이탈 같은 경고를 내고 있으면 true.
     */
    fun decide(stage: AlertStage, media: MediaState, waitedMs: Long, nowMs: Long, vehicleWarning: Boolean = false): TalkDecision {
        if (media.inCall) {
            val urgent = stage == AlertStage.DANGER || vehicleWarning
            if (!urgent || waitedMs < urgentCallCapMs) return TalkDecision.Wait(pollMs, WaitReason.CALL)
        }
        val talk = TalkDecision.Talk(speakMode(media), listenMode(media))
        if (stage != AlertStage.NORMAL || waitedMs >= maxDeferMs) return talk

        val sinceInteraction = media.lastInteractionMs?.let { nowMs - it }
        if (sinceInteraction != null && sinceInteraction in 0 until interactionGraceMs) {
            return TalkDecision.Wait(minOf(pollMs, interactionGraceMs - sinceInteraction), WaitReason.DRIVER_ACTIVE)
        }
        val remaining = media.trackRemainingMs
        if (media.playing && remaining != null && remaining in 1..songGapWaitMs && waitedMs < songGapWaitMs) {
            return TalkDecision.Wait(minOf(pollMs, remaining), WaitReason.SONG_GAP)
        }
        return talk
    }

    /** 말하는 동안: 음악이 나오면 소리만 줄인다. */
    fun speakMode(media: MediaState): AudioMode = if (media.playing) AudioMode.DUCK else AudioMode.NONE

    /** 대답을 듣는 동안: 음악이 나오면 마이크가 운전자 목소리를 듣도록 잠깐 멈춘다. */
    fun listenMode(media: MediaState): AudioMode = if (media.playing) AudioMode.PAUSE else AudioMode.NONE
}
