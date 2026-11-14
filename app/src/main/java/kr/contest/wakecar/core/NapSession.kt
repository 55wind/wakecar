package kr.contest.wakecar.core

import kotlin.math.abs
import kotlin.random.Random

/**
 * 낮잠 한 번의 흐름(자는 동안 → 깨울 때 → 깨어남 미션 → 다시 잠들기 방지 → 끝)을 맡는 상태 기계.
 *
 * 차량 API와 Android를 모른다. 시간(now)과 차량 값(VehicleSnapshot)을 받아 상태를 바꾸고,
 * 바깥이 해야 할 일(Effect)을 돌려준다. 모든 호출은 한 스레드(메인)에서 한다.
 *
 * 안전 원칙
 * - 기어가 P이고 문이 닫혀 있을 때만 시작한다.
 * - 자는 중 문이 열리거나 브레이크를 밟거나 기어가 P에서 벗어나면 바로 끝낸다.
 * - 알람은 화면을 한 번 눌러서는 꺼지지 않는다. 차량 조작(미션)이나 3초 길게 누르기만 받는다.
 * - 앱은 등받이를 뒤로 젖히지 않는다.
 */
class NapSession(
    private val timings: NapTimings,
    private val random: Random = Random.Default,
    /** 말놀이 질문을 만드는 곳. 미션을 처음 끝낸 순간에 한 번 부른다. */
    private val questionSource: () -> List<Question> = { emptyList() },
    /** 운전자가 '등받이 일으키기'를 켜 두었는지. 기본은 꺼짐. */
    private val seatRaiseEnabled: () -> Boolean = { false },
) {
    var state = NapState()
        private set

    private var snapshot = VehicleSnapshot()

    private var preWakeAt = 0L
    private var stage1StartedAt = 0L
    private var lastAmbientStep = 0
    private var firstWakeAt: Long? = null
    private var stageEnteredAt = 0L
    private var lastMission: Mission? = null
    private var windowBaseline: Int? = null
    private var pendingQuestions: MutableList<Question>? = null
    private var seatRaising = false
    /** 브레이크, 문, 화면 조작으로 멈추면 그 낮잠에서는 등받이를 다시 움직이지 않는다. */
    private var seatRaiseStopped = false

    /** 지금 낮잠을 시작할 수 없는 이유. 시작할 수 있으면 null. */
    fun startBlock(): StartBlock? = when {
        !snapshot.connected || snapshot.inPark == null -> StartBlock.NOT_CONNECTED
        snapshot.inPark == false -> StartBlock.NOT_IN_PARK
        snapshot.moving == true -> StartBlock.MOVING
        snapshot.anyDoorOpen == true -> StartBlock.DOOR_OPEN
        (snapshot.energyPercent ?: 100) < MIN_ENERGY_TO_START -> StartBlock.LOW_ENERGY
        else -> null
    }

    fun start(now: Long, napMinutes: Int): List<Effect> {
        if (state.phase != Phase.SETUP || startBlock() != null) return emptyList()
        resetInternals()
        state = NapState(phase = Phase.SLEEPING, startedAt = now)
        sleepUntil(now, now + napMinutes * timings.msPerNapMinute)
        // 문이 잠겨 있지 않으면 잠그고 쉬도록 안내한다. 앱이 문을 직접 잠그지는 않는다.
        val lockHint = if (snapshot.driverDoorLocked == false) " 문이 잠겨 있지 않아요. 문을 잠그고 쉬세요." else ""
        return listOf(
            Effect.AmbientLight(0),
            Effect.Speak("${napMinutes}분 뒤에 깨워 드릴게요.$lockHint 편히 주무세요."),
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
        val active = state.phase != Phase.SETUP && state.phase != Phase.DONE
        if (!active) return emptyList()

        if (new.inPark == false) return finish(now, EndReason.GEAR_LEFT_PARK)
        if (new.moving == true) return finish(now, EndReason.MOVED)

        val brakePressedNow = old.brakePressed != true && new.brakePressed == true
        val doorOpenedNow = old.anyDoorOpen != true && new.anyDoorOpen == true
        val powerDownNow = old.powerDownWarning != true && new.powerDownWarning == true

        return when (state.phase) {
            Phase.SLEEPING -> when {
                doorOpenedNow -> finish(now, EndReason.DOOR_OPENED)
                brakePressedNow -> finish(now, EndReason.BRAKE_PRESSED)
                // 전원이 꺼지면 깨울 수 없다. 꺼지기 전에 먼저 깨운다.
                powerDownNow -> {
                    state = state.copy(wokenEarlyForPower = true)
                    enterWaking(now, stage = 2, lead = "차량 전원이 곧 꺼져서 일찍 깨웠어요.")
                }
                (new.energyPercent ?: 100) < MIN_ENERGY_WHILE_SLEEPING -> {
                    state = state.copy(wokenEarlyForEnergy = true)
                    enterWaking(now, stage = 2, lead = "배터리가 부족해 일찍 깨웠어요.")
                }
                else -> emptyList()
            }
            Phase.WAKING -> {
                // 브레이크를 밟거나 문을 열면 등받이는 즉시 멈춘다. 알람은 계속 울린다.
                val stop = if (brakePressedNow || doorOpenedNow) stopSeatRaise() else emptyList()
                stop + advanceMission(now, old, new, brakePressedNow)
            }
            Phase.REST -> if (doorOpenedNow) finish(now, EndReason.LEFT_CAR) else emptyList()
            Phase.GUARD -> when {
                doorOpenedNow -> finish(now, EndReason.LEFT_CAR)
                // 차량 조작이 있으면 깨어 있는 것으로 보고 다시 깨우기까지의 시간을 새로 잰다.
                operated(old, new) -> {
                    state = state.copy(guardDeadline = now + timings.guardMs)
                    emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /** 자는 중에만 화면으로 끝낼 수 있다. 깨우는 중에는 미션으로만 끝난다. */
    fun endNap(now: Long): List<Effect> =
        if (state.phase == Phase.SLEEPING) finish(now, EndReason.USER_ENDED) else emptyList()

    /** 몸이 불편하거나 미션을 할 수 없는 상황을 위한 길. 화면을 3초 길게 눌러야 한다. */
    fun emergencyStop(now: Long): List<Effect> =
        if (state.phase == Phase.WAKING || state.phase == Phase.GUARD) {
            finish(now, EndReason.EMERGENCY_STOP)
        } else {
            emptyList()
        }

    /** 말놀이 답. 맞고 틀림은 따지지 않는다. */
    fun answer(now: Long): List<Effect> {
        if (state.phase != Phase.GUARD || state.question == null) return emptyList()
        pendingQuestions?.removeFirstOrNull()
        return if (pendingQuestions.isNullOrEmpty()) enterRest(now) else ask(now)
    }

    /** 화면을 누르면 등받이만 멈춘다. 알람은 계속 울린다. */
    fun stopSeatRaise(): List<Effect> {
        if (!seatRaising) return emptyList()
        seatRaising = false
        seatRaiseStopped = true
        return listOf(Effect.SeatRaise(false))
    }

    /** '출발할게'. 쉬는 시간이 지난 뒤에만 받는다. 출발 여부는 언제나 운전자가 정한다. */
    fun depart(now: Long): List<Effect> {
        if (state.phase != Phase.REST || now < state.restUntil) return emptyList()
        state = state.copy(restCompleted = true)
        return finish(now, EndReason.COMPLETED)
    }

    /** 다시 운전하지 않을 때. 쉬는 시간을 기다리지 않고 끝낸다. */
    fun endRest(now: Long): List<Effect> =
        if (state.phase == Phase.REST) finish(now, EndReason.COMPLETED) else emptyList()

    /** '5분만 더'. 알람이 멈춘 뒤 한 번만 미룬다. 다시 깨울 때는 미션부터 다시 한다. */
    fun snooze(now: Long): List<Effect> {
        if (state.phase != Phase.GUARD || state.snoozeUsed) return emptyList()
        state = state.copy(phase = Phase.SLEEPING, snoozeUsed = true, stage = 0, question = null, mission = null)
        sleepUntil(now, now + timings.snoozeMs)
        return listOf(Effect.AmbientLight(0), Effect.Speak("5분 뒤에 다시 깨워 드릴게요."))
    }

    /** 끝 화면에서 처음 화면으로. */
    fun reset() {
        if (state.phase == Phase.DONE) state = NapState()
    }

    private fun sleepUntil(now: Long, wakeAt: Long) {
        val length = wakeAt - now
        preWakeAt = wakeAt - minOf(timings.preWakeMs, length / 2)
        lastAmbientStep = 0
        state = state.copy(wakeAt = wakeAt)
    }

    private fun tickSleeping(now: Long): List<Effect> {
        val fx = mutableListOf<Effect>()
        // 1단계: 정한 시각 전에 조명을 서서히 밝힌다.
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
                raiseSeatIfEnabled() + listOf(Effect.Fan(FanLevel.MEDIUM), Effect.Alarm(2))
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

    private fun enterWaking(now: Long, stage: Int, lead: String): List<Effect> {
        if (firstWakeAt == null) {
            firstWakeAt = now
            state = state.copy(sleptMs = now - state.startedAt)
        }
        stageEnteredAt = now
        val mission = pickMission()
        lastMission = mission
        windowBaseline = snapshot.driverWindowOpenPercent
        state = state.copy(phase = Phase.WAKING, stage = stage, mission = mission, missionStep = 0, question = null, woken = true)
        val fx = mutableListOf<Effect>(Effect.AmbientLight(100))
        if (stage >= 3) {
            fx += raiseSeatIfEnabled()
            fx += Effect.Fan(FanLevel.MEDIUM)
        }
        fx += Effect.Alarm(if (stage >= 3) 2 else 1)
        fx += Effect.Speak("$lead ${mission.prompt}")
        return fx
    }

    /** 그 차에서 읽을 수 있는 신호로만 미션을 고르고, 직전 미션은 피한다. */
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

    /** 브레이크, 와이퍼, 창문 가운데 무엇이든 움직였는지. */
    private fun operated(old: VehicleSnapshot, new: VehicleSnapshot): Boolean =
        old.brakePressed != new.brakePressed ||
            old.wiperOn != new.wiperOn ||
            old.driverWindowOpenPercent != new.driverWindowOpenPercent

    private fun enterGuard(now: Long): List<Effect> {
        if (state.wakeResponseMs == null) {
            state = state.copy(wakeResponseMs = now - (firstWakeAt ?: now))
        }
        if (pendingQuestions == null) pendingQuestions = questionSource().toMutableList()
        val fx = mutableListOf<Effect>(Effect.Alarm(0))
        if (seatRaising) {
            seatRaising = false
            fx += Effect.SeatRaise(false)
        }
        fx += if (pendingQuestions.isNullOrEmpty()) enterRest(now) else ask(now)
        return fx
    }

    /** 3단계: 켜 두었을 때만, 그리고 이 낮잠에서 멈춘 적이 없을 때만 등받이를 일으킨다. */
    private fun raiseSeatIfEnabled(): List<Effect> {
        if (seatRaising || seatRaiseStopped || !seatRaiseEnabled()) return emptyList()
        seatRaising = true
        return listOf(Effect.SeatRaise(true))
    }

    /** 깬 직후에는 반응이 느릴 수 있다. 다시 운전하기 전에 쉬는 시간을 둔다. */
    private fun enterRest(now: Long): List<Effect> {
        state = state.copy(phase = Phase.REST, mission = null, question = null, restUntil = now + timings.restMs)
        return listOf(Effect.Speak("깬 직후에는 반응이 느릴 수 있어요. 10분만 바람 쐬고 출발해요."))
    }

    private fun ask(now: Long): List<Effect> {
        val pending = pendingQuestions.orEmpty()
        val question = pending.first()
        val total = maxOf(state.questionCount, pending.size)
        state = state.copy(
            phase = Phase.GUARD,
            mission = null,
            question = question,
            questionCount = total,
            questionNumber = total - pending.size + 1,
            guardDeadline = now + timings.guardMs,
        )
        return listOf(Effect.Speak(question.text))
    }

    /** 미션을 하고도 아무 조작이 없으면 다시 잠든 것으로 보고 더 세게 깨운다. */
    private fun realarm(now: Long): List<Effect> {
        state = state.copy(realarmCount = state.realarmCount + 1)
        return enterWaking(now, stage = 3, lead = "다시 잠드신 것 같아요.")
    }

    private fun finish(now: Long, reason: EndReason): List<Effect> {
        val slept = if (firstWakeAt == null) now - state.startedAt else state.sleptMs
        // 쉬는 시간 중에 기어를 바꾸거나 차가 움직이면, 권장 휴식을 채웠는지로 기록한다.
        if (state.phase == Phase.REST && (reason == EndReason.GEAR_LEFT_PARK || reason == EndReason.MOVED)) {
            state = state.copy(restCompleted = now >= state.restUntil)
        }
        val chargeReminder = snapshot.chargePortOpen == true
        state = state.copy(
            phase = Phase.DONE,
            stage = 0,
            mission = null,
            question = null,
            endReason = reason,
            sleptMs = slept,
            chargeReminder = chargeReminder,
        )
        val closing = buildString {
            append(
                when (reason) {
                    EndReason.COMPLETED -> "조심히 다녀오세요."
                    EndReason.LEFT_CAR -> ""
                    EndReason.USER_ENDED -> "낮잠을 끝냈어요."
                    EndReason.GEAR_LEFT_PARK -> "기어가 P에서 벗어나 낮잠을 끝냈어요."
                    EndReason.MOVED -> "차가 움직여 낮잠을 끝냈어요."
                    EndReason.DOOR_OPENED -> "문이 열려 낮잠을 끝냈어요."
                    EndReason.BRAKE_PRESSED -> "브레이크를 밟아 낮잠을 끝냈어요."
                    EndReason.EMERGENCY_STOP -> "알람을 멈췄어요."
                }
            )
            if (chargeReminder && reason != EndReason.LEFT_CAR) {
                append(" 충전이 끝났는지 확인하고 충전 케이블을 분리해 주세요.")
            }
        }.trim()
        val fx = mutableListOf<Effect>(Effect.Alarm(0))
        if (seatRaising) {
            seatRaising = false
            fx += Effect.SeatRaise(false)
        }
        fx += Effect.RestoreAll
        if (closing.isNotEmpty()) fx += Effect.Speak(closing)
        return fx
    }

    private fun resetInternals() {
        stage1StartedAt = 0
        lastAmbientStep = 0
        firstWakeAt = null
        stageEnteredAt = 0
        lastMission = null
        windowBaseline = null
        pendingQuestions = null
        seatRaising = false
        seatRaiseStopped = false
    }

    companion object {
        /** 잔량이 이보다 낮으면 시작하지 않는다(%). */
        const val MIN_ENERGY_TO_START = 15
        /** 자는 중 잔량이 이 아래로 떨어지면 바로 깨운다(%). */
        const val MIN_ENERGY_WHILE_SLEEPING = 10
        /** 창문이 이만큼 움직여야 "내렸다"로 본다(%p). */
        const val WINDOW_MOVED_PERCENT = 20
        /** 처음 위치에서 이 안으로 돌아와야 "다시 올렸다"로 본다(%p). */
        const val WINDOW_BACK_PERCENT = 5
    }
}
