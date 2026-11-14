package kr.contest.wakecar.core

import kotlin.random.Random

/**
 * 깬 직후의 짧은 말놀이. 답을 고르는 동안 머리가 깨어나도록 돕는다.
 * 맞고 틀림은 따지지 않고, 운전할 수 있는지도 판정하지 않는다.
 *
 * 지금은 화면에서 고른다. 음성 인식(STT SDK)이 승인되면 같은 질문을 말로 주고받는다.
 */
object WarmUpQuestions {

    private val days = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")

    /**
     * @param dayOfWeek 1 = 월요일 … 7 = 일요일
     * @param hour 0~23
     */
    fun build(dayOfWeek: Int, hour: Int, random: Random): List<Question> {
        val today = (dayOfWeek - 1).mod(7)
        val dayChoices = listOf(today, (today + 2) % 7, (today + 4) % 7).shuffled(random)
        val h = hour.mod(24)
        val hourChoices = listOf(h, (h + 3) % 24, (h + 21) % 24).shuffled(random)
        return listOf(
            Question("오늘이 무슨 요일이에요?", dayChoices.map { days[it] }),
            Question("지금 몇 시쯤일까요?", hourChoices.map(::hourLabel)),
        )
    }

    private fun hourLabel(hour: Int): String {
        val half = if (hour < 12) "오전" else "오후"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$half ${h12}시쯤"
    }
}
