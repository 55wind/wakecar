package kr.contest.wakecar.core

/**
 * 효과를 재기 위한 익명 기록. 이 차(앱)에만 저장하고 밖으로 보내지 않는다.
 *
 * - 다시 잠들어 한 번 더 깨운 비율 = realarmed / woken
 * - 권장 휴식 시간을 채우고 출발한 비율 = restFilled / departed
 */
data class NapStats(
    val woken: Int = 0,
    val realarmed: Int = 0,
    val departed: Int = 0,
    val restFilled: Int = 0,
) {
    /** 끝난 낮잠 하나를 더한다. 알람이 울리기 전에 끝난 낮잠은 세지 않는다. */
    fun add(nap: NapState): NapStats {
        if (!nap.woken) return this
        val rest = nap.restCompleted
        return copy(
            woken = woken + 1,
            realarmed = realarmed + if (nap.realarmCount > 0) 1 else 0,
            departed = departed + if (rest != null) 1 else 0,
            restFilled = restFilled + if (rest == true) 1 else 0,
        )
    }

    val realarmPercent: Int? get() = if (woken == 0) null else realarmed * 100 / woken
    val restFilledPercent: Int? get() = if (departed == 0) null else restFilled * 100 / departed
}
