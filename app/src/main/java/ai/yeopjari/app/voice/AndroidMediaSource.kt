package ai.yeopjari.app.voice

import ai.yeopjari.app.ports.MediaSource
import ai.yeopjari.core.MediaState
import android.content.Context
import android.media.AudioManager

/**
 * 권한 없이 알 수 있는 소리 상태: 음악 재생 여부와 통화 여부.
 * 곡이 끝날 때까지 남은 시간·곡 넘기기는 알 수 없어 비워 둔다(그때는 곡 사이를 기다리지 않고 소리만 줄인다).
 * TODO(pleos): Pleos 미디어 정보나 MediaSession(알림 접근 권한)으로 곡 남은 시간·곡 넘기기·곡 제목을 채운다.
 */
class AndroidMediaSource(context: Context) : MediaSource {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun state(nowMs: Long): MediaState = MediaState(
        playing = audio.isMusicActive,
        inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION,
    )
}
