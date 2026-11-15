package kr.contest.wakecar.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.contest.wakecar.core.CharGrowth
import kr.contest.wakecar.core.Daily
import kr.contest.wakecar.core.GrowthState
import kr.contest.wakecar.core.Outfit
import kr.contest.wakecar.core.Quest

/** 캐릭터 네 종. 모두 원작이며 자동차는 몸이 아니라 소품으로만 남긴다. */
enum class Character(val title: String, val desc: String, val favorite: String, val motion: Motion) {
    KONGKONG("콩콩", "말랑한 민트 찹쌀떡이에요. 발이 작은 바퀴라 데굴데굴 굴러다니고, 머리 위 새싹 전구가 깨울 때마다 밝아져요.", "데굴데굴 한 바퀴", Motion.ROLL),
    POKSIN("폭신", "운전석 머리받침이 살아났어요. 낮잠 내내 머리를 받쳐 주다가 시간이 되면 살살 깨워요.", "폭신폭신 눌리기", Motion.SQUISH),
    PPANGPPI("빵삐", "아침을 알리는 동그란 병아리예요. 잠이 깰수록 머리 깃털이 하나씩 서고, 가슴 단추를 누르면 빵 하고 울어요.", "깡충깡충 두 번", Motion.HOP),
    MONGSIL("몽실", "좌석 위에 내려앉은 낮잠 구름이에요. 안전띠를 단단히 매고 둥실 떠서 잠을 지켜 줘요.", "둥실둥실 흔들기", Motion.FLOAT);

    /** assets/chars/ 아래 폴더 이름. */
    val asset: String get() = name.lowercase()
}

/** 캐릭터가 하는 몸짓. 좋아하는 모션, 쓰다듬기 반응, 스스로 움직이기에 쓴다. */
enum class Motion { ROLL, SQUISH, HOP, FLOAT, LEAN, PEEK, TOSS, SHIVER, BOUNCE }

enum class SleepTrack(val title: String, val hint: String) {
    PIANO("고요한 피아노", "분당 60박의 느린 피아노예요."),
    AMBIENT("느린 앰비언트", "박자 없이 넓게 퍼지는 화음이에요."),
    RAIN("창밖의 빗소리", "고르게 내리는 비로 바깥 소음을 덮어요."),
    WAVES("파도 호흡", "파도가 분당 6번 오가며 숨을 천천히 고르게 해요."),
    CABIN("달리는 차 안", "낮게 웅웅대는 주행음이에요."),
}

enum class AlarmSound(val title: String, val hint: String) {
    SUNRISE("해 뜨는 피아노", "낮은 화음에서 시작해 밝은 음으로 올라가요."),
    FOREST("새벽 숲", "멀리서 들리던 새소리가 가까워지고 많아져요."),
    MUSICBOX("오르골", "맑은 종소리 선율이 점점 빨라져요."),
    DRIVE("출발 신호", "경쾌한 마림바에 빵빵 소리가 더해져요."),
}

data class AppSettings(
    val character: Character = Character.KONGKONG,
    val effects: Boolean = true,
    val music: Boolean = true,
    val track: SleepTrack = SleepTrack.PIANO,
    /** 0~100 */
    val musicVolume: Int = 35,
    /** 잠들면 낮잠 길이의 절반(최대 10분) 동안 수면 음악을 30%까지 줄인다. */
    val fade: Boolean = true,
    val alarm: AlarmSound = AlarmSound.SUNRISE,
    /** 점점 크게 깨우기: 깨우기 3분 전부터 아주 작게 시작해 단계마다 키운다. */
    val ramp: Boolean = true,
    /** 0~100 */
    val alarmVolume: Int = 70,
)

/** 설정과 키우기 기록을 이 기기에만 저장한다(밖으로 보내지 않는다). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wakecar_settings", Context.MODE_PRIVATE)
    private val growPrefs = context.getSharedPreferences("wakecar_growth", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _growth = MutableStateFlow(loadGrowth())
    val growth: StateFlow<GrowthState> = _growth

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("character", s.character.name)
            .putBoolean("effects", s.effects).putBoolean("music", s.music)
            .putString("track", s.track.name).putInt("musicVolume", s.musicVolume).putBoolean("fade", s.fade)
            .putString("alarm", s.alarm.name).putBoolean("ramp", s.ramp).putInt("alarmVolume", s.alarmVolume)
            .apply()
    }

    fun updateGrowth(change: (GrowthState) -> GrowthState) {
        val g = change(_growth.value)
        _growth.value = g
        saveGrowth(g)
    }

    private fun loadSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            character = enumOr(prefs.getString("character", null), d.character),
            effects = prefs.getBoolean("effects", d.effects),
            music = prefs.getBoolean("music", d.music),
            track = enumOr(prefs.getString("track", null), d.track),
            musicVolume = prefs.getInt("musicVolume", d.musicVolume).coerceIn(0, 100),
            fade = prefs.getBoolean("fade", d.fade),
            alarm = enumOr(prefs.getString("alarm", null), d.alarm),
            ramp = prefs.getBoolean("ramp", d.ramp),
            alarmVolume = prefs.getInt("alarmVolume", d.alarmVolume).coerceIn(10, 100),
        )
    }

    // 키우기 기록: 캐릭터마다 "레벨,경험치,기분,기분시각,가진것|...,입은것" 한 줄
    private fun saveGrowth(g: GrowthState) {
        val e = growPrefs.edit().clear()
            .putInt("coins", g.coins).putLong("napAt", g.napAt)
            .putString("daily", listOf(g.daily.date, g.daily.naps, g.daily.quick, g.daily.pets, g.daily.claimed.joinToString("|") { it.name }).joinToString(","))
        g.chars.forEach { (k, c) ->
            e.putString("char_$k", listOf(c.level, c.exp, c.mood, c.moodAt, c.owned.joinToString("|") { it.name }, c.wearing?.name ?: "").joinToString(","))
        }
        e.apply()
    }

    private fun loadGrowth(): GrowthState = runCatching {
        val chars = growPrefs.all.keys.filter { it.startsWith("char_") }.associate { key ->
            val p = growPrefs.getString(key, "")!!.split(",")
            key.removePrefix("char_") to CharGrowth(
                level = p[0].toInt(), exp = p[1].toInt(), mood = p[2].toInt(), moodAt = p[3].toLong(),
                owned = p[4].split("|").filter { it.isNotEmpty() }.map { Outfit.valueOf(it) }.toSet(),
                wearing = p[5].takeIf { it.isNotEmpty() }?.let { Outfit.valueOf(it) },
            )
        }
        val d = growPrefs.getString("daily", null)?.split(",")
        GrowthState(
            coins = growPrefs.getInt("coins", 30),
            napAt = growPrefs.getLong("napAt", 0L),
            chars = chars,
            daily = if (d == null || d.size < 5) Daily() else Daily(
                date = d[0], naps = d[1].toInt(), quick = d[2].toInt(), pets = d[3].toInt(),
                claimed = d[4].split("|").filter { it.isNotEmpty() }.map { Quest.valueOf(it) }.toSet(),
            ),
        )
    }.getOrDefault(GrowthState())

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(fallback)
}
