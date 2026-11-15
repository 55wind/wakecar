package kr.contest.wakecar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kr.contest.wakecar.R
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.settings.AlarmSound
import kr.contest.wakecar.settings.AppSettings
import kr.contest.wakecar.settings.SleepTrack

/**
 * 소리 설계(design/SPEC.md "소리 설계 v2")
 * - 잠들 때: 느린 수면 음악. 잠들고 나면 낮잠 길이의 절반(최대 10분) 동안 30%까지 서서히 줄인다.
 * - 깨울 때: 선율형 알람을 깨우기 단계(1~4)에 맞춰 키운다. 1단계(깨우기 3분 전)는 아주 작고 멀게(잔향 많음),
 *   4단계는 크고 가깝고 빠르게. 단계마다 따로 구운 반복 음원(res/raw/alarm_*_단계)을 엇갈려 흐리게 바꾼다.
 * - "점점 크게 깨우기"를 끄면 1단계에는 소리를 내지 않고 2단계부터 최대 음량으로 울린다.
 * 음원은 디자인 시안의 합성기를 그대로 구운 것이다(design/scripts/render_audio.py).
 * 모든 호출은 메인 스레드에서 한다.
 */
class SoundEngine(private val context: Context, private val settings: () -> AppSettings) {

    private val scope = MainScope()

    /** 반복 재생 한 층. level(0~1)에 사용자 음량을 곱해 실제 음량을 정한다. */
    private inner class Loop(resId: Int, alarm: Boolean, private val gain: () -> Float) {
        val player: MediaPlayer? = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (alarm) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                context.resources.openRawResourceFd(resId).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        }.getOrNull()
        var level = 0f
            private set
        private var ramp: Job? = null

        fun apply() { val v = (level * gain()).coerceIn(0f, 1f); runCatching { player?.setVolume(v, v) } }

        /** level을 ms 동안 target으로 옮긴다. */
        fun rampTo(target: Float, ms: Long, then: (() -> Unit)? = null) {
            ramp?.cancel()
            val from = level
            ramp = scope.launch {
                val steps = (ms / STEP_MS).coerceAtLeast(1)
                for (i in 1..steps) {
                    level = from + (target - from) * i / steps
                    apply()
                    delay(STEP_MS)
                }
                then?.invoke()
            }
        }

        fun release(fadeMs: Long = 800) = rampTo(0f, fadeMs) { runCatching { player?.stop(); player?.release() } }
    }

    private var music: Loop? = null
    private var alarm: Loop? = null
    private var alarmStage = 0
    private var phase = Phase.SETUP
    private var stage = 0
    private var musicFade: Job? = null

    data class Preview(val kind: String, val stage: Int = 0)
    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview
    private var previewLoop: Loop? = null
    private var previewJob: Job? = null

    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val fx = mapOf(
        "tap" to R.raw.fx_tap, "start" to R.raw.fx_start, "done" to R.raw.fx_done,
        "pet0" to R.raw.fx_pet0, "pet1" to R.raw.fx_pet1, "pet2" to R.raw.fx_pet2, "pet3" to R.raw.fx_pet3, "pet4" to R.raw.fx_pet4,
    ).mapValues { (_, id) -> runCatching { pool.load(context, id, 1) }.getOrDefault(0) }

    private fun musicGain() = settings().musicVolume / 100f
    private fun alarmGain() = settings().alarmVolume / 100f

    /** 낮잠 상태가 바뀔 때마다 부른다. napMsLeft: 깨울 때까지 남은 시간, preWakeMs: 1단계 길이. */
    fun update(newPhase: Phase, newStage: Int, napMsLeft: Long, preWakeMs: Long) {
        val oldPhase = phase
        val oldStage = stage
        phase = newPhase; stage = newStage
        if (newPhase == oldPhase && newStage == oldStage) return
        stopPreview()
        val s = settings()
        when (newPhase) {
            Phase.SLEEPING -> {
                if (newStage == 0) {
                    if (oldPhase != Phase.SLEEPING) cue("start")
                    stopAlarm(1000)
                    if (music == null && s.music) startMusic(napMsLeft)
                } else if (oldStage == 0) {
                    // 1단계: 수면 음악을 알람으로 넘긴다
                    music?.release(maxOf(2_000, preWakeMs / 3)); music = null; musicFade?.cancel()
                    if (s.ramp) setAlarmStage(1, preWakeMs)
                }
            }
            Phase.WAKING -> {
                music?.release(1_200); music = null; musicFade?.cancel()
                setAlarmStage(newStage.coerceIn(2, 4), if (alarmStage == 1) 1_500 else 2_500)
            }
            else -> {
                music?.release(1_200); music = null; musicFade?.cancel()
                stopAlarm(600)
                if ((newPhase == Phase.GUARD || newPhase == Phase.DONE) && oldPhase == Phase.WAKING) cue("done")
            }
        }
    }

    private fun startMusic(napMsLeft: Long) {
        val m = Loop(trackRes(settings().track), alarm = false, gain = ::musicGain)
        music = m
        m.rampTo(1f, 6_000)
        musicFade?.cancel()
        if (settings().fade) {
            val span = (napMsLeft / 2).coerceIn(12_000, 600_000)
            musicFade = scope.launch { delay(6_000); if (music === m) m.rampTo(0.3f, span) }
        }
    }

    private fun setAlarmStage(s: Int, rampMs: Long) {
        if (s == alarmStage && alarm != null) return
        val target = if (settings().ramp) STAGE_LEVEL[s] else 1f
        alarm?.release(1_200)
        val next = Loop(alarmRes(settings().alarm, s), alarm = true, gain = ::alarmGain)
        if (s == 1) next.rampTo(0.01f, 50)
        next.rampTo(target, rampMs)
        alarm = next; alarmStage = s
    }

    private fun stopAlarm(fadeMs: Long) { alarm?.release(fadeMs); alarm = null; alarmStage = 0 }

    /** 설정이 바뀌면 음량을 다시 맞추고, 듣는 중인 곡이나 알람 종류가 바뀌었으면 바꿔 튼다. */
    fun onSettingsChanged(old: AppSettings, new: AppSettings) {
        listOfNotNull(music, alarm, previewLoop).forEach { it.apply() }
        if (old.music && !new.music) { music?.release(); music = null; musicFade?.cancel() }
        if (!old.music && new.music && phase == Phase.SLEEPING && stage == 0 && music == null) startMusic(300_000)
        if (old.track != new.track && music != null) {
            music?.release(1_500); music = Loop(trackRes(new.track), alarm = false, gain = ::musicGain).also { it.rampTo(0.6f, 1_500) }
        }
        if (old.alarm != new.alarm && alarm != null) { val s = alarmStage; alarmStage = 0; setAlarmStage(s, 800) }
        if (old.track != new.track && _preview.value?.kind == "music") { stopPreview(); previewMusic() }
        if (old.alarm != new.alarm && _preview.value?.kind == "alarm") { stopPreview(); previewAlarm() }
    }

    fun cue(name: String) {
        if (!settings().effects) return
        val id = fx[name] ?: return
        val v = (0.25f + 0.5f * alarmGain()).coerceIn(0f, 1f)
        runCatching { pool.play(id, v, v, 1, 0, 1f) }
    }

    // ------------------------------------------------------------ 미리듣기(설정 화면)
    fun previewMusic() {
        if (_preview.value?.kind == "music") { stopPreview(); return }
        stopPreview(); muteLive(true)
        previewLoop = Loop(trackRes(settings().track), alarm = false, gain = ::musicGain).also { it.rampTo(1f, 1_500) }
        _preview.value = Preview("music")
        previewJob = scope.launch { delay(30_000); stopPreview() }
    }

    /** 1단계부터 4단계까지 4초씩, 실제 깨우기를 16초로 줄여 들려준다. */
    fun previewAlarm() {
        if (_preview.value?.kind == "alarm") { stopPreview(); return }
        stopPreview(); muteLive(true)
        val first = if (settings().ramp) 1 else 2
        previewJob = scope.launch {
            for (s in first..4) {
                previewLoop?.release(600)
                val target = if (settings().ramp) STAGE_LEVEL[s] else 1f
                previewLoop = Loop(alarmRes(settings().alarm, s), alarm = true, gain = ::alarmGain).also { it.rampTo(target, if (s == first) 1_500 else 800) }
                _preview.value = Preview("alarm", s)
                delay(4_000)
            }
            stopPreview()
        }
    }

    fun stopPreview() {
        val was = _preview.value != null
        previewJob?.cancel(); previewJob = null
        previewLoop?.release(500); previewLoop = null
        _preview.value = null
        if (was) muteLive(false)
    }

    private fun muteLive(mute: Boolean) {
        music?.rampTo(if (mute) 0f else if (settings().fade) 0.6f else 1f, 400)
        alarm?.rampTo(if (mute) 0f else if (settings().ramp) STAGE_LEVEL[alarmStage.coerceAtLeast(1)] else 1f, 400)
    }

    private fun trackRes(t: SleepTrack) = when (t) {
        SleepTrack.PIANO -> R.raw.sleep_piano
        SleepTrack.AMBIENT -> R.raw.sleep_ambient
        SleepTrack.RAIN -> R.raw.sleep_rain
        SleepTrack.WAVES -> R.raw.sleep_waves
        SleepTrack.CABIN -> R.raw.sleep_cabin
    }

    private fun alarmRes(a: AlarmSound, s: Int): Int = ALARM_RES.getValue(a)[s.coerceIn(1, 4) - 1]

    private companion object {
        const val STEP_MS = 40L
        /** 단계별 층 음량(점점 크게 깨우기를 켰을 때). */
        val STAGE_LEVEL = floatArrayOf(0f, 0.22f, 0.5f, 0.75f, 1f)
        val ALARM_RES = mapOf(
            AlarmSound.SUNRISE to intArrayOf(R.raw.alarm_sunrise_1, R.raw.alarm_sunrise_2, R.raw.alarm_sunrise_3, R.raw.alarm_sunrise_4),
            AlarmSound.FOREST to intArrayOf(R.raw.alarm_forest_1, R.raw.alarm_forest_2, R.raw.alarm_forest_3, R.raw.alarm_forest_4),
            AlarmSound.MUSICBOX to intArrayOf(R.raw.alarm_musicbox_1, R.raw.alarm_musicbox_2, R.raw.alarm_musicbox_3, R.raw.alarm_musicbox_4),
            AlarmSound.DRIVE to intArrayOf(R.raw.alarm_drive_1, R.raw.alarm_drive_2, R.raw.alarm_drive_3, R.raw.alarm_drive_4),
        )
    }
}
