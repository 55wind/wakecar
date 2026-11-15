package kr.contest.wakecar.core

/**
 * 키우기(육성) 규칙. 낮잠을 잘 자고 잘 일어나면 캐릭터가 자란다. Android를 모르는 순수 계산이라 단위 테스트를 한다.
 *
 * - 경험치: 낮잠 성공 40(다시 깨운 적이 없으면 +10, 알람 15초 안에 일어나면 +10), 스스로 끝낸 낮잠 10, 쓰다듬기 2(하루 10번까지)
 * - 별사탕: 낮잠 성공 30, 오늘의 할 일 보상, 쓰다듬기 1(하루 10번까지). 옷장 소품을 산다.
 * - 레벨마다 필요한 경험치 = 50 + 30 x (레벨 - 1). Lv1~2 아기, Lv3~5 꼬마, Lv6 이상 어른(단계마다 크기가 커진다).
 * - 기분: 쓰다듬기 +5, 낮잠 성공 +15, 시간당 4씩 준다. 졸음: 마지막 낮잠 뒤 시간당 12씩 오른다(낮잠을 자면 0).
 * - 캐릭터마다 레벨, 경험치, 기분, 옷장은 따로 두고 별사탕, 졸음, 오늘의 할 일은 함께 쓴다.
 */
enum class GrowStage(val label: String, val minLevel: Int, val scale: Float) {
    BABY("아기", 1, 0.84f), KID("꼬마", 3, 0.93f), ADULT("어른", 6, 1.02f);

    companion object {
        fun of(level: Int): GrowStage = entries.last { level >= it.minLevel }
    }
}

enum class Outfit(val label: String, val price: Int, val minLevel: Int) {
    RIBBON("분홍 리본", 40, 1), CAP("별 수면 모자", 80, 2), CROWN("반짝 왕관", 150, 5),
}

enum class Quest(val text: String, val need: Int, val reward: Int) {
    NAP("낮잠 한 번 잘 자고 일어나기", 1, 30),
    QUICK("알람 15초 안에 일어나기", 1, 20),
    PET("캐릭터 5번 쓰다듬기", 5, 10),
}

data class CharGrowth(
    val level: Int = 1,
    val exp: Int = 0,
    val mood: Int = 60,
    val moodAt: Long = 0,
    val owned: Set<Outfit> = emptySet(),
    val wearing: Outfit? = null,
)

data class Daily(
    val date: String = "",
    val naps: Int = 0,
    val quick: Int = 0,
    val pets: Int = 0,
    val claimed: Set<Quest> = emptySet(),
) {
    fun progress(q: Quest): Int = when (q) {
        Quest.NAP -> naps
        Quest.QUICK -> quick
        Quest.PET -> pets
    }
}

data class GrowthState(
    val coins: Int = 30,
    /** 마지막 낮잠을 마친 시각(벽시계 ms). 졸음 계산에 쓴다. */
    val napAt: Long = 0,
    val chars: Map<String, CharGrowth> = emptyMap(),
    val daily: Daily = Daily(),
)

data class LevelUp(val level: Int, val stage: GrowStage, val grew: Boolean)

data class Reward(val exp: Int, val coins: Int, val levelUps: List<LevelUp>, val notes: List<String> = emptyList())

object Growth {
    private const val HOUR = 3_600_000L
    const val PET_REWARD_LIMIT = 10
    const val QUICK_WAKE_MS = 15_000L

    fun expToNext(level: Int): Int = 50 + 30 * (level - 1)

    fun char(s: GrowthState, key: String): CharGrowth = s.chars[key] ?: CharGrowth()

    fun mood(c: CharGrowth, now: Long): Int =
        (c.mood - (now - c.moodAt).coerceAtLeast(0) * 4 / HOUR).toInt().coerceIn(0, 100)

    fun sleepy(s: GrowthState, now: Long): Int =
        if (s.napAt == 0L) 50 else ((now - s.napAt).coerceAtLeast(0) * 12 / HOUR).toInt().coerceIn(0, 100)

    /** 날짜가 바뀌었으면 오늘의 할 일을 새로 시작한다. */
    fun rollDay(s: GrowthState, today: String): GrowthState =
        if (s.daily.date == today) s else s.copy(daily = Daily(date = today))

    private fun withMood(c: CharGrowth, delta: Int, now: Long) = c.copy(mood = (mood(c, now) + delta).coerceIn(0, 100), moodAt = now)

    /** 경험치와 별사탕을 더하고 레벨 업을 처리한다. */
    private fun gain(s: GrowthState, key: String, exp: Int, coins: Int, now: Long, moodDelta: Int): Pair<GrowthState, List<LevelUp>> {
        var c = withMood(char(s, key), moodDelta, now).let { it.copy(exp = it.exp + exp) }
        val ups = mutableListOf<LevelUp>()
        while (c.exp >= expToNext(c.level)) {
            val before = GrowStage.of(c.level)
            c = c.copy(exp = c.exp - expToNext(c.level), level = c.level + 1)
            val after = GrowStage.of(c.level)
            ups += LevelUp(c.level, after, after != before)
        }
        return s.copy(coins = s.coins + coins, chars = s.chars + (key to c)) to ups
    }

    fun pet(s0: GrowthState, key: String, now: Long, today: String): Pair<GrowthState, Reward> {
        val s = rollDay(s0, today).let { it.copy(daily = it.daily.copy(pets = it.daily.pets + 1)) }
        val rewarded = s.daily.pets <= PET_REWARD_LIMIT
        val (next, ups) = gain(s, key, if (rewarded) 2 else 0, if (rewarded) 1 else 0, now, moodDelta = 5)
        return next to Reward(if (rewarded) 2 else 0, if (rewarded) 1 else 0, ups)
    }

    /** 끝난 낮잠 하나에 대한 보상. */
    fun nap(s0: GrowthState, key: String, nap: NapState, now: Long, today: String): Pair<GrowthState, Reward> {
        var s = rollDay(s0, today).copy(napAt = now)
        return when (nap.endReason) {
            EndReason.COMPLETED, EndReason.LEFT_CAR -> {
                val quick = nap.wakeResponseMs?.let { it <= QUICK_WAKE_MS } == true
                val once = nap.realarmCount == 0
                s = s.copy(daily = s.daily.copy(naps = s.daily.naps + 1, quick = s.daily.quick + if (quick) 1 else 0))
                val (next, ups) = gain(s, key, 40 + (if (once) 10 else 0) + (if (quick) 10 else 0), 30, now, moodDelta = 15)
                next to Reward(
                    40 + (if (once) 10 else 0) + (if (quick) 10 else 0), 30, ups,
                    listOfNotNull(if (once) "한 번에 일어남 +10" else null, if (quick) "15초 안에 일어남 +10" else null),
                )
            }
            EndReason.USER_ENDED -> gain(s, key, 10, 0, now, moodDelta = 0).let { (n, u) -> n to Reward(10, 0, u, listOf("스스로 낮잠을 끝냄")) }
            else -> s to Reward(0, 0, emptyList(), listOf("안전 때문에 멈춘 낮잠은 보상이 없어요"))
        }
    }

    fun claim(s0: GrowthState, q: Quest, today: String): GrowthState {
        val s = rollDay(s0, today)
        if (q in s.daily.claimed || s.daily.progress(q) < q.need) return s
        return s.copy(coins = s.coins + q.reward, daily = s.daily.copy(claimed = s.daily.claimed + q))
    }

    /** 사기(가진 게 아니면) 또는 입기와 벗기. */
    fun outfit(s: GrowthState, key: String, o: Outfit): GrowthState {
        val c = char(s, key)
        return when {
            o in c.owned -> s.copy(chars = s.chars + (key to c.copy(wearing = if (c.wearing == o) null else o)))
            c.level < o.minLevel || s.coins < o.price -> s
            else -> s.copy(coins = s.coins - o.price, chars = s.chars + (key to c.copy(owned = c.owned + o, wearing = o)))
        }
    }
}
