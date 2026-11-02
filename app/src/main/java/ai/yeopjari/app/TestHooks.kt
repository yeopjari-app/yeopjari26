package ai.yeopjari.app

import ai.yeopjari.app.ports.FamilyNotifier
import ai.yeopjari.app.ports.Platform
import ai.yeopjari.app.ports.VoiceIO
import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * 자동 테스트가 가짜 차량·가짜 목소리를 끼워 넣는 자리. 앱에서는 비어 있어 실제 플랫폼과 Android 음성을 쓴다.
 */
@VisibleForTesting
object TestHooks {
    @Volatile var platform: ((Context) -> Platform)? = null
    @Volatile var voice: ((Context) -> VoiceIO)? = null
    @Volatile var family: ((Context) -> FamilyNotifier)? = null
}
