package kr.contest.wakecar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NapSessionTest {

    private val t = NapTimings.REAL
    private val min = 60_000L

    private val parked = VehicleSnapshot(
        connected = true,
        inPark = true,
        brakePressed = false,
        anyDoorOpen = false,
        driverDoorLocked = true,
        wiperOn = false,
        driverWindowOpenPercent = 0,
        energyPercent = 80,
        chargePortOpen = false,
    )

    private val questions = listOf(
        Question("오늘이 무슨 요일이에요?", listOf("월요일", "수요일", "금요일"), 1),
        Question("지금 몇 시쯤일까요?", listOf("오전 9시쯤", "오후 1시쯤", "오후 4시쯤"), 1),
    )

    private fun session(
        snapshot: VehicleSnapshot = parked,
        seed: Int = 1,
        qs: List<Question> = questions,
    ) = NapSession(t, Random(seed)) { qs }.also { it.onVehicle(0, snapshot) }

    /** 15분 낮잠을 시작해 기상 알림(2단계)까지 보낸다. */
    private fun NapSession.sleepUntilWake(): Long {
        start(0, 15)
        tick(15 * min)
        assertEquals(Phase.WAKING, state.phase)
        return 15 * min
    }

    private fun NapSession.pressBrake(now: Long, base: VehicleSnapshot = parked): List<Effect> {
        val fx = onVehicle(now, base.copy(brakePressed = true))
        return fx + onVehicle(now + 1, base.copy(brakePressed = false))
    }

    /** 뽑힌 미션을 차량 조작으로 끝낸다. */
    private fun NapSession.completeMission(now: Long): List<Effect> = when (state.mission!!) {
        Mission.BRAKE_TWICE -> pressBrake(now) + pressBrake(now + 10)
        Mission.WIPER_ON -> onVehicle(now, parked.copy(wiperOn = true)) +
            onVehicle(now + 1, parked)
        Mission.WINDOW_DOWN_UP -> onVehicle(now, parked.copy(driverWindowOpenPercent = 60)) +
            onVehicle(now + 1, parked.copy(driverWindowOpenPercent = 0))
    }

    @Test
    fun `기어가 P가 아니면 시작하지 않는다`() {
        val s = session(parked.copy(inPark = false))
        assertEquals(StartBlock.NOT_IN_PARK, s.startBlock())
        assertTrue(s.start(0, 15).isEmpty())
        assertEquals(Phase.SETUP, s.state.phase)
    }

    @Test
    fun `차량에 연결되지 않았거나 문이 열렸거나 잔량이 낮으면 시작하지 않는다`() {
        assertEquals(StartBlock.NOT_CONNECTED, session(VehicleSnapshot()).startBlock())
        assertEquals(StartBlock.DOOR_OPEN, session(parked.copy(anyDoorOpen = true)).startBlock())
        assertEquals(StartBlock.LOW_ENERGY, session(parked.copy(energyPercent = 10)).startBlock())
        assertNull(session(parked.copy(energyPercent = null)).startBlock())
    }

    @Test
    fun `시작하면 등받이를 젖히고 조명을 끈다`() {
        val s = session()
        val fx = s.start(0, 15)
        assertTrue(Effect.SeatRecline in fx)
        assertTrue(Effect.AmbientLight(0) in fx)
        assertEquals(Phase.SLEEPING, s.state.phase)
        assertEquals(15 * min, s.state.wakeAt)
    }

    @Test
    fun `기상 5분 전부터 조명을 서서히 밝힌다`() {
        val s = session()
        s.start(0, 15)
        assertTrue(s.tick(9 * min).isEmpty())
        assertEquals(listOf<Effect>(Effect.AmbientLight(20)), s.tick(10 * min))
        assertEquals(1, s.state.stage)
        assertTrue(s.tick(10 * min + 1000).isEmpty())
        assertEquals(listOf<Effect>(Effect.AmbientLight(60)), s.tick(12 * min + 30_000))
        assertEquals(Phase.SLEEPING, s.state.phase)
    }

    @Test
    fun `기상 시각에 등받이를 세우고 알람과 미션을 낸다`() {
        val s = session()
        s.start(0, 15)
        val fx = s.tick(15 * min)
        assertEquals(Phase.WAKING, s.state.phase)
        assertEquals(2, s.state.stage)
        assertTrue(Effect.SeatRestore in fx)
        assertTrue(Effect.AmbientLight(100) in fx)
        assertTrue(Effect.Alarm(1) in fx)
        assertTrue(fx.any { it is Effect.Speak && s.state.mission!!.prompt in it.text })
        assertEquals(15 * min, s.state.sleptMs)
    }

    @Test
    fun `반응이 없으면 3단계 외기와 송풍, 4단계 최대 송풍으로 올린다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        assertTrue(s.tick(wake + t.stage3AfterMs - 1).isEmpty())
        val stage3 = s.tick(wake + t.stage3AfterMs)
        assertEquals(3, s.state.stage)
        assertEquals(listOf(Effect.FreshAir(true), Effect.Fan(FanLevel.MEDIUM), Effect.Alarm(2)), stage3)
        val stage4 = s.tick(wake + t.stage4AfterMs)
        assertEquals(4, s.state.stage)
        assertTrue(Effect.Fan(FanLevel.MAX) in stage4)
        assertTrue(Effect.Alarm(3) in stage4)
        assertEquals(Phase.WAKING, s.state.phase)
    }

    @Test
    fun `알람은 화면으로 끌 수 없다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        assertTrue(s.endNap(wake + 1).isEmpty())
        assertTrue(s.answer(wake + 1, 0).isEmpty())
        assertEquals(Phase.WAKING, s.state.phase)
    }

    @Test
    fun `브레이크를 두 번 밟아야 알람이 멈춘다`() {
        // 와이퍼가 켜져 있고 창문을 읽을 수 없으면 브레이크 미션만 남는다.
        val base = parked.copy(wiperOn = true, driverWindowOpenPercent = null)
        val s = session(base)
        val wake = s.sleepUntilWake()
        assertEquals(Mission.BRAKE_TWICE, s.state.mission)

        assertTrue(s.pressBrake(wake + 100, base).isEmpty())
        assertEquals(1, s.state.missionStep)
        assertEquals(Phase.WAKING, s.state.phase)

        val fx = s.pressBrake(wake + 200, base)
        assertTrue(Effect.Alarm(0) in fx)
        assertEquals(Phase.GUARD, s.state.phase)
        assertEquals(200L, s.state.wakeResponseMs)
    }

    @Test
    fun `와이퍼를 켜면 미션이 끝난다`() {
        val base = parked.copy(brakePressed = null, driverWindowOpenPercent = null)
        val s = session(base)
        val wake = s.sleepUntilWake()
        assertEquals(Mission.WIPER_ON, s.state.mission)
        val fx = s.onVehicle(wake + 50, base.copy(wiperOn = true))
        assertTrue(Effect.Alarm(0) in fx)
        assertEquals(Phase.GUARD, s.state.phase)
    }

    @Test
    fun `창문은 내렸다가 다시 올려야 미션이 끝난다`() {
        val base = parked.copy(brakePressed = null, wiperOn = null, driverWindowOpenPercent = 10)
        val s = session(base)
        val wake = s.sleepUntilWake()
        assertEquals(Mission.WINDOW_DOWN_UP, s.state.mission)

        s.onVehicle(wake + 10, base.copy(driverWindowOpenPercent = 20))
        assertEquals(0, s.state.missionStep)
        s.onVehicle(wake + 20, base.copy(driverWindowOpenPercent = 70))
        assertEquals(1, s.state.missionStep)
        assertEquals(Phase.WAKING, s.state.phase)
        s.onVehicle(wake + 30, base.copy(driverWindowOpenPercent = 12))
        assertEquals(Phase.GUARD, s.state.phase)
    }

    @Test
    fun `확인 질문 두 개에 답하면 끝나고 차를 원래대로 돌린다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        assertEquals(Phase.GUARD, s.state.phase)
        assertEquals(1, s.state.questionNumber)
        assertEquals(2, s.state.questionCount)

        s.answer(wake + 2000, 1)
        assertEquals(2, s.state.questionNumber)
        val fx = s.answer(wake + 4000, 1)
        assertEquals(Phase.DONE, s.state.phase)
        assertEquals(EndReason.COMPLETED, s.state.endReason)
        assertTrue(Effect.RestoreAll in fx)
        assertFalse(s.state.needsStretch)
    }

    @Test
    fun `틀리거나 늦게 답하면 몸을 풀라고 안내한다`() {
        val wrong = session()
        var wake = wrong.sleepUntilWake()
        wrong.completeMission(wake + 100)
        wrong.answer(wake + 1000, 0)
        assertTrue(wrong.state.wrongAnswer)
        assertEquals(1, wrong.state.questionNumber)
        assertTrue(wrong.state.needsStretch)

        val slow = session()
        wake = slow.sleepUntilWake()
        slow.completeMission(wake + 100)
        slow.answer(wake + 200 + t.slowAnswerMs + 1, 1)
        assertTrue(slow.state.needsStretch)
    }

    @Test
    fun `미션 뒤 답이 없으면 다시 잠든 것으로 보고 다른 미션으로 더 세게 깨운다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        val first = s.state.mission
        s.completeMission(wake + 100)
        val deadline = s.state.guardDeadline

        assertTrue(s.tick(deadline - 1).isEmpty())
        val fx = s.tick(deadline)
        assertEquals(Phase.WAKING, s.state.phase)
        assertEquals(3, s.state.stage)
        assertEquals(1, s.state.realarmCount)
        assertNotEquals(first, s.state.mission)
        assertTrue(Effect.Alarm(2) in fx)
        assertTrue(Effect.Fan(FanLevel.MEDIUM) in fx)

        // 두 번째 미션을 끝내면 남은 질문부터 이어 간다.
        s.completeMission(deadline + 100)
        assertEquals(Phase.GUARD, s.state.phase)
        assertEquals(1, s.state.questionNumber)
    }

    @Test
    fun `어느 단계에서든 기어가 P에서 벗어나면 즉시 끝낸다`() {
        val sleeping = session()
        sleeping.start(0, 15)
        val fx = sleeping.onVehicle(1000, parked.copy(inPark = false))
        assertEquals(EndReason.GEAR_LEFT_PARK, sleeping.state.endReason)
        assertTrue(Effect.RestoreAll in fx)
        assertTrue(Effect.Alarm(0) in fx)

        val waking = session()
        val wake = waking.sleepUntilWake()
        waking.onVehicle(wake + 10, parked.copy(inPark = false))
        assertEquals(Phase.DONE, waking.state.phase)
    }

    @Test
    fun `자는 중 잔량이 기준 아래로 떨어지면 바로 깨운다`() {
        val s = session()
        s.start(0, 15)
        val fx = s.onVehicle(3 * min, parked.copy(energyPercent = 9))
        assertEquals(Phase.WAKING, s.state.phase)
        assertTrue(s.state.wokenEarlyForEnergy)
        assertTrue(Effect.Alarm(1) in fx)
        assertEquals(3 * min, s.state.sleptMs)
    }

    @Test
    fun `자는 중 문이 열리면 끝낼지 묻고, 답이 없으면 계속 잔다`() {
        val s = session()
        s.start(0, 15)
        s.onVehicle(min, parked.copy(anyDoorOpen = true))
        assertEquals(Interruption.DOOR_OPENED, s.state.interruption)
        assertEquals(Phase.SLEEPING, s.state.phase)

        s.tick(min + t.interruptionPromptMs)
        assertNull(s.state.interruption)

        s.onVehicle(2 * min, parked)
        s.onVehicle(2 * min + 1, parked.copy(brakePressed = true))
        assertEquals(Interruption.BRAKE_PRESSED, s.state.interruption)
        s.continueSleep()
        assertNull(s.state.interruption)

        s.endNap(3 * min)
        assertEquals(EndReason.USER_ENDED, s.state.endReason)
        assertEquals(3 * min, s.state.sleptMs)
    }

    @Test
    fun `자는 동안 15분마다 1분씩 외기를 들인다`() {
        val s = session()
        s.start(0, 30)
        assertTrue(s.tick(14 * min).isEmpty())
        assertEquals(listOf<Effect>(Effect.FreshAir(true)), s.tick(15 * min))
        assertTrue(s.tick(15 * min + 30_000).isEmpty())
        assertEquals(listOf<Effect>(Effect.FreshAir(false)), s.tick(16 * min))
    }

    @Test
    fun `확인 구간에 문을 열고 내리면 깬 것으로 보고 끝낸다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        s.onVehicle(wake + 500, parked.copy(anyDoorOpen = true))
        assertEquals(EndReason.LEFT_CAR, s.state.endReason)
    }

    @Test
    fun `충전구가 열려 있으면 끝날 때 충전 케이블을 안내한다`() {
        val charging = parked.copy(chargePortOpen = true)
        val s = session(charging, qs = emptyList())
        s.start(0, 15)
        s.tick(15 * min)
        val base = charging
        val fx = when (s.state.mission!!) {
            Mission.BRAKE_TWICE -> s.pressBrake(15 * min + 1, base) + s.pressBrake(15 * min + 10, base)
            Mission.WIPER_ON -> s.onVehicle(15 * min + 1, base.copy(wiperOn = true))
            Mission.WINDOW_DOWN_UP -> s.onVehicle(15 * min + 1, base.copy(driverWindowOpenPercent = 50)) +
                s.onVehicle(15 * min + 2, base)
        }
        assertEquals(Phase.DONE, s.state.phase)
        assertTrue(s.state.chargeReminder)
        assertTrue(fx.any { it is Effect.Speak && "충전 케이블" in it.text })
    }

    @Test
    fun `비상 종료는 깨우는 중에만 듣는다`() {
        val s = session()
        s.start(0, 15)
        assertTrue(s.emergencyStop(1000).isEmpty())
        val wake = s.sleepUntilWake()
        val fx = s.emergencyStop(wake + 10)
        assertEquals(EndReason.EMERGENCY_STOP, s.state.endReason)
        assertTrue(Effect.RestoreAll in fx)
        s.reset()
        assertEquals(Phase.SETUP, s.state.phase)
    }

    @Test
    fun `시연 모드는 15분 낮잠을 30초로 줄인다`() {
        val s = NapSession(NapTimings.DEMO, Random(1)) { questions }
        s.onVehicle(0, parked)
        s.start(0, 15)
        assertEquals(30_000, s.state.wakeAt)
        s.tick(30_000)
        assertEquals(Phase.WAKING, s.state.phase)
    }

    @Test
    fun `확인 질문은 오늘 요일과 지금 시각을 정답으로 낸다`() {
        val qs = WarmUpQuestions.build(dayOfWeek = 5, hour = 13, random = Random(3))
        assertEquals(2, qs.size)
        assertEquals("금요일", qs[0].choices[qs[0].answerIndex])
        assertEquals("오후 1시쯤", qs[1].choices[qs[1].answerIndex])
        assertEquals(3, qs[0].choices.toSet().size)
        assertEquals(3, qs[1].choices.toSet().size)
    }
}
