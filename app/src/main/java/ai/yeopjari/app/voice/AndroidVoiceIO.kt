package ai.yeopjari.app.voice

import ai.yeopjari.app.ports.ListenResult
import ai.yeopjari.app.ports.VoiceIO
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android 기본 TextToSpeech·SpeechRecognizer 로 말하고 듣는다.
 * Pleos TextToSpeech/SpeechToText SDK 로 바꿀 때는 이 클래스만 교체하면 된다.
 */
class AndroidVoiceIO(context: Context) : VoiceIO {
    private val appContext = context.applicationContext
    private val ttsReady = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(appContext) { status ->
        ttsReady.complete(status == TextToSpeech.SUCCESS)
    }

    override val canListen: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    override suspend fun speak(text: String) {
        if (!ttsReady.await()) return
        tts.setLanguage(Locale.KOREAN)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    /**
     * SpeechRecognizer 는 메인 스레드에서만 동작한다. 지연은 듣기 시작 → 말하기 시작까지.
     * 시간 초과·인식 결과 없음은 [ListenResult.Silent], 그 밖의 오류(권한·네트워크·인식기 사용 중 등)는
     * [ListenResult.Unavailable] 로 돌려준다.
     */
    override suspend fun listen(timeoutMs: Long): ListenResult {
        if (!canListen) return ListenResult.Unavailable
        return withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            try {
                withTimeoutOrNull(timeoutMs) { recognize(recognizer) } ?: ListenResult.Silent
            } finally {
                recognizer.destroy()
            }
        }
    }

    private suspend fun recognize(recognizer: SpeechRecognizer): ListenResult =
        suspendCancellableCoroutine { cont ->
            val startedAt = SystemClock.elapsedRealtime()
            var speechAt: Long? = null
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onBeginningOfSpeech() {
                    speechAt = SystemClock.elapsedRealtime()
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    val result = if (text.isNullOrBlank()) {
                        ListenResult.Silent
                    } else {
                        ListenResult.Heard(text, (speechAt ?: SystemClock.elapsedRealtime()) - startedAt)
                    }
                    if (cont.isActive) cont.resume(result)
                }
                override fun onError(error: Int) {
                    val result = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> ListenResult.Silent
                        else -> ListenResult.Unavailable
                    }
                    if (cont.isActive) cont.resume(result)
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            }
            recognizer.startListening(intent)
            cont.invokeOnCancellation { recognizer.cancel() }
        }

    override fun release() {
        tts.stop()
        tts.shutdown()
    }
}
