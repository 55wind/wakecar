package kr.contest.wakecar.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 음성 안내. Android 기본 TTS 엔진을 쓰고, 엔진이 없으면 조용히 넘어간다(화면 문구가 같은 내용을 보여 준다).
 * Pleos TextToSpeech SDK가 승인되면 이 클래스 안만 바꾸면 된다.
 */
class Speaker(context: Context) {

    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = runCatching { tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE }
                        .getOrDefault(false)
                }
            }
        }
    }

    fun speak(text: String) {
        if (ready) runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wakecar") }
    }

    fun close() {
        if (::tts.isInitialized) runCatching { tts.shutdown() }
    }
}
