package kr.contest.wakecar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthTest {
    private val hour = 3_600_000L
    private val key = "kongkong"
    private val day = "2026-11-15"

    private fun done(reason: EndReason, respMs: Long? = 9_000, realarm: Int = 0) =
        NapState(phase = Phase.DONE, endReason = reason, wakeResponseMs = respMs, realarmCount = realarm, woken = true)

    @Test fun `잘 자고 15초 안에 한 번에 일어나면 60 경험치와 30 별사탕`() {
        val (s, r) = Growth.nap(GrowthState(coins = 0), key, done(EndReason.COMPLETED), now = 0, today = day)
        assertEquals(60, r.exp); assertEquals(30, r.coins)
        assertEquals(1, s.daily.naps); assertEquals(1, s.daily.quick)
        assertEquals(1, r.levelUps.size)          // 60 >= 50: Lv2
        assertEquals(2, Growth.char(s, key).level); assertEquals(10, Growth.char(s, key).exp)
    }

    @Test fun `다시 깨웠거나 늦게 일어나면 가산점이 없다`() {
        val (_, r) = Growth.nap(GrowthState(), key, done(EndReason.COMPLETED, respMs = 40_000, realarm = 1), 0, day)
        assertEquals(40, r.exp)
    }

    @Test fun `안전 때문에 멈춘 낮잠은 보상이 없다`() {
        val (s, r) = Growth.nap(GrowthState(coins = 5), key, done(EndReason.DOOR_OPENED), 0, day)
        assertEquals(0, r.exp); assertEquals(5, s.coins)
    }

    @Test fun `레벨이 오르면 단계와 크기가 바뀐다`() {
        var s = GrowthState()
        val ups = mutableListOf<LevelUp>()
        repeat(4) { s = Growth.nap(s, key, done(EndReason.COMPLETED), 0, day).also { ups += it.second.levelUps }.first }
        assertEquals(GrowStage.KID, GrowStage.of(Growth.char(s, key).level))
        assertTrue(ups.any { it.grew && it.stage == GrowStage.KID })
    }

    @Test fun `쓰다듬기 보상은 하루 10번까지`() {
        var s = GrowthState(coins = 0)
        repeat(12) { s = Growth.pet(s, key, 0, day).first }
        assertEquals(10, s.coins); assertEquals(20, Growth.char(s, key).exp)
        s = Growth.pet(s, key, 0, "2026-11-16").first      // 다음 날은 다시 받는다
        assertEquals(11, s.coins)
    }

    @Test fun `오늘의 할 일은 다 했을 때 한 번만 받는다`() {
        var s = GrowthState(coins = 0)
        s = Growth.claim(s, Quest.NAP, day); assertEquals(0, s.coins)
        s = Growth.nap(s, key, done(EndReason.COMPLETED), 0, day).first
        val before = s.coins
        s = Growth.claim(s, Quest.NAP, day); s = Growth.claim(s, Quest.NAP, day)
        assertEquals(before + Quest.NAP.reward, s.coins)
    }

    @Test fun `옷장은 레벨과 별사탕이 되어야 사고 다시 누르면 벗는다`() {
        var s = GrowthState(coins = 100)
        s = Growth.outfit(s, key, Outfit.CROWN); assertEquals(100, s.coins)      // Lv5부터
        s = Growth.outfit(s, key, Outfit.RIBBON); assertEquals(60, s.coins); assertEquals(Outfit.RIBBON, Growth.char(s, key).wearing)
        s = Growth.outfit(s, key, Outfit.RIBBON); assertNull(Growth.char(s, key).wearing); assertEquals(60, s.coins)
    }

    @Test fun `졸음은 시간당 12 오르고 기분은 시간당 4 준다`() {
        val s = GrowthState(napAt = 0)
        assertEquals(0, Growth.sleepy(s.copy(napAt = 1), 1))
        assertEquals(60, Growth.sleepy(s.copy(napAt = 1), 1 + 5 * hour))
        assertEquals(40, Growth.mood(CharGrowth(mood = 60, moodAt = 0), 5 * hour))
    }
}
