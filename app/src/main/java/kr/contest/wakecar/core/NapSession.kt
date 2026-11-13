package kr.contest.wakecar.core

import kotlin.math.abs
import kotlin.random.Random

/**
 * 낮잠 한 번의 흐름(자는 동안 → 깨울 때 → 미션 → 다시 잠들기 방지 → 끝)을 맡는 상태 기계.
 *
 * 차량 API와 Android를 모른다. 시간(now)과 차량 값(VehicleSnapshot)을 받아 상태를 바꾸고,
 * 바깥이 해야 할 일(Effect)을 돌려준다. 모든 호출은 한 스레드(메인)에서 한다.
 *
 * 안전 원칙
 * - 기어가 P일 때만 시작하고, P가 풀리면 어느 단계에서든 즉시 끝내고 차를 원래대로 돌린다.
 * - 알람은 화면 탭으로 꺼지지 않는다. 차량 조작(미션)이나 3초 길게 누르는 비상 종료만 받는다.
 */
class NapSession(
    private val timings: NapTimings,
    private val random: Random = Random.Default,
    /** 확인 질문을 만드는 곳. 미션을 처음 끝낸 순간에 한 번 부른다. */
    private val questionSource: () -> List<Question> = { emptyList() },
) {
    var state = NapState()
        private set

    private var snapshot = VehicleSnapshot()

    private var preWakeAt = 0L
    private var stage1StartedAt = 0L
    private var lastAmbientStep = 0
    private var nextFreshAirAt = 0L
    private var freshAirUntil: Long? = null
    private var interruptionUntil = 0L
    private var firstWakeAt: Long? = null
    private var stageEnteredAt = 0L
    private var lastMission: Mission? = null
    private var windowBaseline: Int? = null
    private var pendingQuestions: MutableList<Question>? = null
    private var questionAskedAt = 0L

    /** 지금 낮잠을 시작할 수 없는 이유. 시작할 수 있으면 null. */
    fun startBlock(): StartBlock? = when {
        !snapshot.connected || snapshot.inPark == null -> StartBlock.NOT_CONNECTED
        snapshot.inPark == false -> StartBlock.NOT_IN_PARK
        snapshot.anyDoorOpen == true -> StartBlock.DOOR_OPEN
        (snapshot.energyPercent ?: 100) < MIN_ENERGY_TO_START -> StartBlock.LOW_ENERGY
        else -> null
    }

    fun start(now: Long, napMinutes: Int): List<Effect> {
        if (state.phase != Phase.SETUP || startBlock() != null) return emptyList()
        val napMs = napMinutes * timings.msPerNapMinute
        resetInternals()
        preWakeAt = now + napMs - minOf(timings.preWakeMs, napMs / 2)
        nextFreshAirAt = now + timings.freshAirEveryMs
        state = NapState(phase = Phase.SLEEPING, startedAt = now, wakeAt = now + napMs)
        return listOf(
            Effect.SeatRecline,
            Effect.AmbientLight(0),
            Effect.Speak("${napMinutes}분 뒤에 깨워 드릴게요. 편히 주무세요."),
        )
    }

    fun tick(now: Long): List<Effect> = when (state.phase) {
        Phase.SLEEPING -> tickSleeping(now)
        Phase.WAKING -> tickWaking(now)
        Phase.GUARD -> if (now >= state.guardDeadline) realarm(now) else emptyList()
        else -> emptyList()
    }

    /** 차량 값이 바뀔 때마다 부른다. 이전 값과 비교해 조작(밟음, 열림 등)을 알아낸다. */
    fun onVehicle(now: Long, new: VehicleSnapshot): List<Effect> {
        val old = snapshot
        snapshot = new
        val active = state.phase == Phase.SLEEPING || state.phase == Phase.WAKING || state.phase == Phase.GUARD
        if (!active) return emptyList()

        if (new.inPark == false) return finish(now, EndReason.GEAR_LEFT_PARK)

        val brakePressedNow = old.brakePressed != true && new.brakePressed == true
        val doorOpenedNow = old.anyDoorOpen != true && new.anyDoorOpen == true

        return when (state.phase) {
            Phase.SLEEPING -> when {
                (new.energyPercent ?: 100) < MIN_ENERGY_WHILE_SLEEPING -> {
                    state = state.copy(wokenEarlyForEnergy = true)
                    enterWaking(now, stage = 2, lead = "배터리가 부족해 일찍 깨웠어요.")
                }
                doorOpenedNow -> interrupt(now, Interruption.DOOR_OPENED)
                brakePressedNow -> interrupt(now, Interruption.BRAKE_PRESSED)
                else -> emptyList()
            }
            Phase.WAKING -> advanceMission(now, old, new, brakePressedNow)
            Phase.GUARD -> if (doorOpenedNow) finish(now, EndReason.LEFT_CAR) else emptyList()
            else -> emptyList()
        }
    }

    /** 자는 중 "끝낼까요?" 질문에 "계속 자기"로 답했을 때. */
    fun continueSleep() {
        if (state.phase == Phase.SLEEPING) state = state.copy(interruption = null)
    }

    /** 자는 중에만 화면으로 끝낼 수 있다. 깨우는 중에는 미션으로만 끝난다. */
    fun endNap(now: Long): List<Effect> =
        if (state.phase == Phase.SLEEPING) finish(now, EndReason.USER_ENDED) else emptyList()

    /** 차량 신호를 읽지 못하는 상황을 위한 뒷문. 화면을 3초 길게 눌러야 한다. */
    fun emergencyStop(now: Long): List<Effect> =
        if (state.phase == Phase.WAKING || state.phase == Phase.GUARD) {
            finish(now, EndReason.EMERGENCY_STOP)
        } else {
            emptyList()
        }

    fun answer(now: Long, choiceIndex: Int): List<Effect> {
        val question = state.question
        if (state.phase != Phase.GUARD || question == null) return emptyList()
        if (choiceIndex != question.answerIndex) {
            state = state.copy(wrongAnswer = true, needsStretch = true, guardDeadline = now + timings.guardMs)
            return emptyList()
        }
        if (now - questionAskedAt > timings.slowAnswerMs) state = state.copy(needsStretch = true)
        pendingQuestions?.removeFirstOrNull()
        return if (pendingQuestions.isNullOrEmpty()) finish(now, EndReason.COMPLETED) else ask(now)
    }

    /** 끝 화면에서 처음 화면으로. */
    fun reset() {
        if (state.phase == Phase.DONE) state = NapState()
    }

    private fun tickSleeping(now: Long): List<Effect> {
        val fx = mutableListOf<Effect>()
        if (state.interruption != null && now >= interruptionUntil) state = state.copy(interruption = null)

        // 이산화탄소가 쌓이지 않도록 일정 간격으로 잠깐 외기를 들인다.
        val until = freshAirUntil
        if (until != null) {
            if (now >= until) {
                fx += Effect.FreshAir(false)
                freshAirUntil = null
                nextFreshAirAt = now + timings.freshAirEveryMs
            }
        } else if (now >= nextFreshAirAt && now < preWakeAt) {
            fx += Effect.FreshAir(true)
            freshAirUntil = now + timings.freshAirForMs
        }

        // 1단계: 기상 시각 전에 조명을 다섯 걸음에 나눠 밝힌다.
        if (state.stage == 0 && now >= preWakeAt) {
            state = state.copy(stage = 1)
            stage1StartedAt = now
        }
        if (state.stage == 1 && now < state.wakeAt) {
            val span = (state.wakeAt - stage1StartedAt).coerceAtLeast(1)
            val step = 1 + ((now - stage1StartedAt) * 4 / span).toInt().coerceIn(0, 3)
            if (step != lastAmbientStep) {
                lastAmbientStep = step
                fx += Effect.AmbientLight(step * 20)
            }
        }

        if (now >= state.wakeAt) fx += enterWaking(now, stage = 2, lead = "일어날 시간이에요.")
        return fx
    }

    private fun tickWaking(now: Long): List<Effect> {
        val held = now - stageEnteredAt
        return when {
            state.stage == 2 && held >= timings.stage3AfterMs -> {
                state = state.copy(stage = 3)
                stageEnteredAt = now
                listOf(Effect.FreshAir(true), Effect.Fan(FanLevel.MEDIUM), Effect.Alarm(2))
            }
            state.stage == 3 && held >= timings.stage4AfterMs - timings.stage3AfterMs -> {
                state = state.copy(stage = 4)
                stageEnteredAt = now
                listOf(
                    Effect.Fan(FanLevel.MAX),
                    Effect.Alarm(3),
                    Effect.Speak("일어나세요. ${state.mission?.prompt.orEmpty()}"),
                )
            }
            else -> emptyList()
        }
    }

    private fun interrupt(now: Long, cause: Interruption): List<Effect> {
        state = state.copy(interruption = cause)
        interruptionUntil = now + timings.interruptionPromptMs
        return emptyList()
    }

    private fun enterWaking(now: Long, stage: Int, lead: String): List<Effect> {
        if (firstWakeAt == null) {
            firstWakeAt = now
            state = state.copy(sleptMs = now - state.startedAt)
        }
        freshAirUntil = null
        stageEnteredAt = now
        val mission = pickMission()
        lastMission = mission
        windowBaseline = snapshot.driverWindowOpenPercent
        state = state.copy(
            phase = Phase.WAKING,
            stage = stage,
            mission = mission,
            missionStep = 0,
            interruption = null,
            question = null,
            wrongAnswer = false,
        )
        val fx = mutableListOf<Effect>(Effect.SeatRestore, Effect.AmbientLight(100))
        if (stage >= 3) {
            fx += Effect.FreshAir(true)
            fx += Effect.Fan(FanLevel.MEDIUM)
        }
        fx += Effect.Alarm(if (stage >= 3) 2 else 1)
        fx += Effect.Speak("$lead ${mission.prompt}")
        return fx
    }

    /** 읽을 수 있는 신호로만 미션을 고르고, 직전 미션은 피한다. */
    private fun pickMission(): Mission {
        val candidates = buildList {
            if (snapshot.brakePressed != null) add(Mission.BRAKE_TWICE)
            if (snapshot.wiperOn == false) add(Mission.WIPER_ON)
            if (snapshot.driverWindowOpenPercent != null) add(Mission.WINDOW_DOWN_UP)
        }
        val fresh = candidates.filter { it != lastMission }.ifEmpty { candidates }
        return if (fresh.isEmpty()) Mission.BRAKE_TWICE else fresh[random.nextInt(fresh.size)]
    }

    private fun advanceMission(
        now: Long,
        old: VehicleSnapshot,
        new: VehicleSnapshot,
        brakePressedNow: Boolean,
    ): List<Effect> {
        val mission = state.mission ?: return emptyList()
        val step = state.missionStep
        val next = when (mission) {
            Mission.BRAKE_TWICE -> if (brakePressedNow) step + 1 else step
            Mission.WIPER_ON -> if (old.wiperOn != true && new.wiperOn == true) step + 1 else step
            Mission.WINDOW_DOWN_UP -> {
                val base = windowBaseline
                val position = new.driverWindowOpenPercent
                when {
                    base == null || position == null -> step
                    step == 0 && abs(position - base) >= WINDOW_MOVED_PERCENT -> 1
                    step == 1 && abs(position - base) <= WINDOW_BACK_PERCENT -> 2
                    else -> step
                }
            }
        }
        if (next == step) return emptyList()
        state = state.copy(missionStep = next)
        return if (next >= mission.steps) enterGuard(now) else emptyList()
    }

    private fun enterGuard(now: Long): List<Effect> {
        if (state.wakeResponseMs == null) {
            state = state.copy(wakeResponseMs = now - (firstWakeAt ?: now))
        }
        if (pendingQuestions == null) pendingQuestions = questionSource().toMutableList()
        val fx = mutableListOf<Effect>(Effect.Alarm(0))
        fx += if (pendingQuestions.isNullOrEmpty()) finish(now, EndReason.COMPLETED) else ask(now)
        return fx
    }

    private fun ask(now: Long): List<Effect> {
        val pending = pendingQuestions.orEmpty()
        val question = pending.first()
        val total = maxOf(state.questionCount, pending.size)
        questionAskedAt = now
        state = state.copy(
            phase = Phase.GUARD,
            mission = null,
            question = question,
            questionCount = total,
            questionNumber = total - pending.size + 1,
            wrongAnswer = false,
            guardDeadline = now + timings.guardMs,
        )
        return listOf(Effect.Speak(question.text))
    }

    /** 미션을 하고도 확인 질문에 답이 없으면 다시 잠든 것으로 보고 더 세게 깨운다. */
    private fun realarm(now: Long): List<Effect> {
        state = state.copy(realarmCount = state.realarmCount + 1)
        return enterWaking(now, stage = 3, lead = "다시 잠드신 것 같아요.")
    }

    private fun finish(now: Long, reason: EndReason): List<Effect> {
        val slept = if (firstWakeAt == null) now - state.startedAt else state.sleptMs
        val chargeReminder = snapshot.chargePortOpen == true
        state = state.copy(
            phase = Phase.DONE,
            stage = 0,
            mission = null,
            question = null,
            interruption = null,
            endReason = reason,
            sleptMs = slept,
            chargeReminder = chargeReminder,
        )
        val closing = buildString {
            append(
                when (reason) {
                    EndReason.COMPLETED ->
                        if (state.needsStretch) "대답이 조금 느렸어요. 2분만 몸을 풀고 움직이세요."
                        else "좋아요. 늦지 않게 다녀오세요."
                    EndReason.LEFT_CAR -> ""
                    EndReason.USER_ENDED -> "낮잠을 끝냈어요."
                    EndReason.GEAR_LEFT_PARK -> "기어가 P에서 벗어나 낮잠을 끝냈어요."
                    EndReason.EMERGENCY_STOP -> "알람을 멈췄어요."
                }
            )
            if (chargeReminder && reason != EndReason.LEFT_CAR) {
                append(" 충전이 끝났는지 확인하고 충전 케이블을 분리해 주세요.")
            }
        }.trim()
        val fx = mutableListOf(Effect.Alarm(0), Effect.RestoreAll)
        if (closing.isNotEmpty()) fx += Effect.Speak(closing)
        return fx
    }

    private fun resetInternals() {
        stage1StartedAt = 0
        lastAmbientStep = 0
        freshAirUntil = null
        interruptionUntil = 0
        firstWakeAt = null
        stageEnteredAt = 0
        lastMission = null
        windowBaseline = null
        pendingQuestions = null
        questionAskedAt = 0
    }

    companion object {
        /** 공조를 켠 채 쉬어야 하므로 잔량이 이보다 낮으면 시작하지 않는다(%). */
        const val MIN_ENERGY_TO_START = 15
        /** 자는 중 잔량이 이 아래로 떨어지면 바로 깨운다(%). */
        const val MIN_ENERGY_WHILE_SLEEPING = 10
        /** 창문이 이만큼 움직여야 "내렸다"로 본다(%p). */
        const val WINDOW_MOVED_PERCENT = 20
        /** 처음 위치에서 이 안으로 돌아와야 "다시 올렸다"로 본다(%p). */
        const val WINDOW_BACK_PERCENT = 5
    }
}
