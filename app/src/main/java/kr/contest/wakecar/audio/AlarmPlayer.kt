package kr.contest.wakecar.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** 기상 알람. 단계가 오를수록 크고 빠르게 울린다. 0 = 끔. */
class AlarmPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var level = 0

    private val beep = object : Runnable {
        override fun run() {
            val setting = SETTINGS[level] ?: return
            runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS) }
            handler.postDelayed(this, setting.intervalMs)
        }
    }

    fun setLevel(newLevel: Int) {
        if (newLevel == level) return
        level = newLevel
        handler.removeCallbacks(beep)
        runCatching { tone?.release() }
        tone = null
        val setting = SETTINGS[level] ?: return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, setting.volume) }.getOrNull()
        handler.post(beep)
    }

    private class Setting(val volume: Int, val intervalMs: Long)

    private companion object {
        const val BEEP_MS = 400
        val SETTINGS = mapOf(
            1 to Setting(volume = 50, intervalMs = 2_000),
            2 to Setting(volume = 80, intervalMs = 1_200),
            3 to Setting(volume = ToneGenerator.MAX_VOLUME, intervalMs = 700),
        )
    }
}
