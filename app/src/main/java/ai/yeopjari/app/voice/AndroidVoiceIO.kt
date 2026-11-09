package ai.yeopjari.app.voice

import ai.yeopjari.app.ports.ListenResult
import ai.yeopjari.app.ports.VoiceIO
import ai.yeopjari.core.AudioMode
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 *
 * 음악은 Android 오디오 포커스로 다룬다. 내비게이션 안내처럼 말할 때는 음악 소리를 줄이고(DUCK),
 * 대답을 들을 때는 마이크가 노래 대신 운전자를 듣도록 음악 앱이 잠깐 멈추게 한다(PAUSE).
 * 모든 요청이 같은 리스너를 써서, 줄이기 → 멈추기로 바뀔 때 음악이 잠깐 원래대로 돌아오지 않는다.
 */
class AndroidVoiceIO(context: Context) : VoiceIO {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val speech = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    private var held = AudioMode.NONE
    private var focus: AudioFocusRequest? = null
    private var paused = false

    private val ttsReady = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(appContext) { status ->
        ttsReady.complete(status == TextToSpeech.SUCCESS)
    }.apply { setAudioAttributes(speech) }

    override val canListen: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    override suspend fun koreanTtsReady(): Boolean =
        // 음성 엔진이 끝내 준비 신호를 주지 않아도 점검이 멈춰 있지 않게 5초까지만 기다린다.
        (withTimeoutOrNull(5_000) { ttsReady.await() } ?: false) && tts.isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE

    override fun holdMedia(mode: AudioMode) {
        if (mode <= held) return
        val gain = if (mode == AudioMode.PAUSE) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(speech)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focus = request
            held = mode
            paused = mode == AudioMode.PAUSE
        }
    }

    override fun releaseMedia() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        held = AudioMode.NONE
        paused = false
    }

    @Volatile private var pitch = 1.0f
    @Volatile private var rate = 1.0f

    override fun setStyle(pitch: Float, rate: Float) {
        this.pitch = pitch
        this.rate = rate
    }

    /**
     * 말이 끝나면 돌아온다. 엔진이 말하기를 거부(ERROR 반환)하거나, 다른 앱이 멈추게 하거나(onStop),
     * 콜백이 오지 않아도 글자 수로 잡은 시간([speakTimeoutMs]) 뒤에는 반드시 돌아와 대화가 멈추지 않게 한다.
     */
    override suspend fun speak(text: String) {
        // 엔진이 초기화 결과를 끝내 알려 주지 않아도 대화가 영원히 멈추지 않게 5초까지만 기다린다.
        if (withTimeoutOrNull(5_000) { ttsReady.await() } != true) return
        tts.setLanguage(Locale.KOREAN)
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        val finished = withTimeoutOrNull(speakTimeoutMs(text)) {
            suspendCancellableCoroutine<Unit> { cont ->
                val done = { u: String? -> if (u == id && cont.isActive) cont.resume(Unit) }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = done(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = done(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = done(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = done(utteranceId)
                })
                cont.invokeOnCancellation { tts.stop() }
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR && cont.isActive) cont.resume(Unit)
            }
        }
        if (finished == null) tts.stop()
    }

    /** 말 길이에 맞춘 넉넉한 최대 시간: 기본 4초 + 글자당 0.25초(느린 말투면 더), 최대 60초. */
    private fun speakTimeoutMs(text: String): Long =
        (4_000L + (text.length * 250L / rate.coerceAtLeast(0.5f)).toLong()).coerceAtMost(60_000L)

    /**
     * SpeechRecognizer 는 메인 스레드에서만 동작한다. 지연은 듣기 시작 → 말하기 시작까지.
     * 시간 초과·인식 결과 없음은 [ListenResult.Silent], 그 밖의 오류(권한·네트워크·인식기 사용 중 등)는
     * [ListenResult.Unavailable] 로 돌려준다.
     */
    override suspend fun listen(timeoutMs: Long): ListenResult {
        if (!canListen) return ListenResult.Unavailable
        val result = withContext(Dispatchers.Main) {
            // 인식기 연결이 거부되는 기기(SecurityException 등)에서도 앱이 죽지 않고 화면 대답으로 넘어가게 한다.
            val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
                ?: return@withContext ListenResult.Unavailable
            try {
                recognize(recognizer, timeoutMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                ListenResult.Unavailable
            } finally {
                runCatching { recognizer.destroy() }
            }
        }
        // 음악을 멈추지 못해 노래가 계속 나왔다면, 대답이 없던 게 아니라 들을 수 없었던 것이다.
        return if (result is ListenResult.Silent && !paused && audio.isMusicActive) ListenResult.Unavailable else result
    }

    /**
     * 듣는 시간 안에 말을 시작하면, 그 말이 끝날 때까지 최대 [SPEECH_GRACE_MS] 더 기다린다(느리게 말한 대답을 무응답으로 자르지 않게).
     */
    private suspend fun recognize(recognizer: SpeechRecognizer, timeoutMs: Long): ListenResult {
        var speechAt: Long? = null
        val startedAt = SystemClock.elapsedRealtime()
        val deferred = kotlinx.coroutines.CompletableDeferred<ListenResult>()
        recognizeInto(recognizer, startedAt, { speechAt = it }, deferred)
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: if (speechAt != null) withTimeoutOrNull(SPEECH_GRACE_MS) { deferred.await() } ?: ListenResult.Silent
            else ListenResult.Silent
    }

    private fun recognizeInto(
        recognizer: SpeechRecognizer,
        startedAt: Long,
        onSpeech: (Long) -> Unit,
        out: kotlinx.coroutines.CompletableDeferred<ListenResult>,
    ) {
        run {
            var speechAt: Long? = null
            val cont = object { val isActive get() = !out.isCompleted; fun resume(r: ListenResult) { out.complete(r) } }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onBeginningOfSpeech() {
                    speechAt = SystemClock.elapsedRealtime().also(onSpeech)
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
                    // 알아들은 말이 없으면(소음·음악이 말처럼 잡힌 경우 포함) 대답으로 보지 않는다:
                    // 소음이 깨우기를 멈추면 안 된다. 소음 때문일 수 있으니 앱은 "제 말 들리세요?"로 한 번 더 묻는다.
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
            out.invokeOnCompletion { if (it != null) runCatching { recognizer.cancel() } }
        }
    }

    override fun release() {
        releaseMedia()
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        /** 말을 시작했으면 듣는 시간이 끝나도 이만큼 더 기다린다. */
        const val SPEECH_GRACE_MS = 4_000L
    }
}
