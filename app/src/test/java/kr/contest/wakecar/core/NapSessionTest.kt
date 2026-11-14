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
        Question("오늘이 무슨 요일이에요?", listOf("월요일", "수요일", "금요일")),
        Question("지금 몇 시쯤일까요?", listOf("오전 9시쯤", "오후 1시쯤", "오후 4시쯤")),
    )

    private fun session(
        snapshot: VehicleSnapshot = parked,
        seed: Int = 1,
        qs: List<Question> = questions,
    ) = NapSession(t, Random(seed), { qs }).also { it.onVehicle(0, snapshot) }

    /** 15분 낮잠을 시작해 알람(2단계)까지 보낸다. */
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
    fun `시작하면 조명만 낮추고 등받이는 건드리지 않는다`() {
        val s = session()
        val fx = s.start(0, 15)
        assertEquals(2, fx.size)
        assertTrue(Effect.AmbientLight(0) in fx)
        assertTrue(fx.any { it is Effect.Speak })
        assertEquals(Phase.SLEEPING, s.state.phase)
        assertEquals(15 * min, s.state.wakeAt)
    }

    @Test
    fun `정한 시각 3분 전부터 조명을 서서히 밝힌다`() {
        val s = session()
        s.start(0, 15)
        assertTrue(s.tick(11 * min).isEmpty())
        assertEquals(listOf<Effect>(Effect.AmbientLight(20)), s.tick(12 * min))
        assertEquals(1, s.state.stage)
        assertTrue(s.tick(12 * min + 1000).isEmpty())
        assertEquals(listOf<Effect>(Effect.AmbientLight(60)), s.tick(13 * min + 30_000))
        assertEquals(Phase.SLEEPING, s.state.phase)
    }

    @Test
    fun `정한 시각에 알람과 미션을 낸다`() {
        val s = session()
        s.start(0, 15)
        val fx = s.tick(15 * min)
        assertEquals(Phase.WAKING, s.state.phase)
        assertEquals(2, s.state.stage)
        assertTrue(Effect.AmbientLight(100) in fx)
        assertTrue(Effect.Alarm(1) in fx)
        assertTrue(fx.any { it is Effect.Speak && s.state.mission!!.prompt in it.text })
        assertEquals(15 * min, s.state.sleptMs)
    }

    @Test
    fun `반응이 없으면 3단계에서 바람과 알람을 세게, 4단계에서 최대로 올린다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        assertTrue(s.tick(wake + t.stage3AfterMs - 1).isEmpty())
        val stage3 = s.tick(wake + t.stage3AfterMs)
        assertEquals(3, s.state.stage)
        assertEquals(listOf(Effect.Fan(FanLevel.MEDIUM), Effect.Alarm(2)), stage3)
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
        assertTrue(s.answer(wake + 1).isEmpty())
        assertTrue(s.snooze(wake + 1).isEmpty())
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
    fun `말놀이는 무엇을 골라도 넘어가고 두 개를 마치면 끝난다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        assertEquals(Phase.GUARD, s.state.phase)
        assertEquals(1, s.state.questionNumber)
        assertEquals(2, s.state.questionCount)

        s.answer(wake + 2000)
        assertEquals(2, s.state.questionNumber)
        s.answer(wake + 60_000)
        // 말놀이가 끝나면 바로 끝내지 않고 쉬는 시간을 둔다.
        assertEquals(Phase.REST, s.state.phase)
        assertEquals(wake + 60_000 + t.restMs, s.state.restUntil)
        assertTrue(s.depart(s.state.restUntil - 1).isEmpty())
        val fx = s.depart(s.state.restUntil)
        assertEquals(Phase.DONE, s.state.phase)
        assertEquals(EndReason.COMPLETED, s.state.endReason)
        assertTrue(Effect.RestoreAll in fx)
    }

    @Test
    fun `문이 잠겨 있지 않으면 시작할 때 잠그고 쉬도록 안내한다`() {
        val unlocked = session(parked.copy(driverDoorLocked = false))
        assertTrue(unlocked.start(0, 15).any { it is Effect.Speak && "문을 잠그고" in it.text })
        val locked = session()
        assertFalse(locked.start(0, 15).any { it is Effect.Speak && "문을 잠그고" in it.text })
    }

    @Test
    fun `자는 중 전원 차단 경고가 뜨면 바로 깨운다`() {
        val s = session()
        s.start(0, 15)
        val fx = s.onVehicle(4 * min, parked.copy(powerDownWarning = true))
        assertEquals(Phase.WAKING, s.state.phase)
        assertTrue(s.state.wokenEarlyForPower)
        assertTrue(Effect.Alarm(1) in fx)
    }

    @Test
    fun `익명 기록은 깨운 낮잠만 세고 권장 휴식을 채웠는지 남긴다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        s.answer(wake + 1000); s.answer(wake + 2000)
        s.depart(s.state.restUntil)
        var stats = NapStats().add(s.state)
        assertEquals(1, stats.woken)
        assertEquals(100, stats.restFilledPercent)

        // 쉬는 시간이 끝나기 전에 기어를 D로 바꾸면 채우지 못한 것으로 센다.
        val early = session()
        val w2 = early.sleepUntilWake()
        early.completeMission(w2 + 100)
        early.answer(w2 + 1000); early.answer(w2 + 2000)
        early.onVehicle(w2 + 3000, parked.copy(inPark = false))
        assertEquals(false, early.state.restCompleted)
        stats = stats.add(early.state)
        assertEquals(50, stats.restFilledPercent)

        // 알람이 울리기 전에 끝난 낮잠은 세지 않는다.
        val door = session()
        door.start(0, 15)
        door.onVehicle(min, parked.copy(anyDoorOpen = true))
        assertEquals(stats, stats.add(door.state))
    }

    @Test
    fun `차가 움직이면 시작하지 않고, 낮잠 중이면 바로 끝낸다`() {
        assertEquals(StartBlock.MOVING, session(parked.copy(moving = true)).startBlock())
        val s = session()
        s.start(0, 15)
        s.onVehicle(1000, parked.copy(moving = true))
        assertEquals(EndReason.MOVED, s.state.endReason)
    }

    @Test
    fun `등받이 일으키기는 켰을 때만 3단계에서 움직이고 브레이크를 밟으면 멈춘다`() {
        val off = session()
        var wake = off.sleepUntilWake()
        assertFalse(off.tick(wake + t.stage3AfterMs).any { it is Effect.SeatRaise })

        val base = parked.copy(wiperOn = true, driverWindowOpenPercent = null)
        val on = NapSession(t, Random(1), { questions }, { true }).also { it.onVehicle(0, base) }
        wake = on.sleepUntilWake()
        assertTrue(Effect.SeatRaise(true) in on.tick(wake + t.stage3AfterMs))
        val fx = on.onVehicle(wake + t.stage3AfterMs + 10, base.copy(brakePressed = true))
        assertTrue(Effect.SeatRaise(false) in fx)
        assertEquals(Phase.WAKING, on.state.phase)
        // 한 번 멈추면 그 낮잠에서는 다시 움직이지 않는다.
        assertFalse(on.tick(wake + t.stage4AfterMs).any { it is Effect.SeatRaise })
    }

    @Test
    fun `미션 뒤 3분 동안 아무 조작이 없으면 다른 미션으로 더 세게 깨운다`() {
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

        // 두 번째 미션을 끝내면 남은 말놀이부터 이어 간다.
        s.completeMission(deadline + 100)
        assertEquals(Phase.GUARD, s.state.phase)
        assertEquals(1, s.state.questionNumber)
    }

    @Test
    fun `미션 뒤 차량 조작이 있으면 다시 깨우기까지의 시간을 새로 잰다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        val deadline = s.state.guardDeadline

        s.onVehicle(deadline - 1000, parked.copy(driverWindowOpenPercent = 30))
        assertEquals(deadline - 1000 + t.guardMs, s.state.guardDeadline)
        assertTrue(s.tick(deadline).isEmpty())
        assertEquals(Phase.GUARD, s.state.phase)
    }

    @Test
    fun `5분만 더는 한 번만 미뤄 주고 다시 미션부터 한다`() {
        val s = session()
        val wake = s.sleepUntilWake()
        s.completeMission(wake + 100)
        val snoozedAt = wake + 5000

        s.snooze(snoozedAt)
        assertEquals(Phase.SLEEPING, s.state.phase)
        assertTrue(s.state.snoozeUsed)
        assertEquals(snoozedAt + t.snoozeMs, s.state.wakeAt)

        s.tick(snoozedAt + t.snoozeMs)
        assertEquals(Phase.WAKING, s.state.phase)
        assertEquals(2, s.state.stage)

        s.completeMission(snoozedAt + t.snoozeMs + 100)
        assertEquals(Phase.GUARD, s.state.phase)
        assertTrue(s.snooze(snoozedAt + t.snoozeMs + 200).isEmpty())
        assertEquals(Phase.GUARD, s.state.phase)
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
    fun `자는 중 문이 열리거나 브레이크를 밟으면 바로 끝낸다`() {
        val door = session()
        door.start(0, 15)
        val fx = door.onVehicle(min, parked.copy(anyDoorOpen = true))
        assertEquals(Phase.DONE, door.state.phase)
        assertEquals(EndReason.DOOR_OPENED, door.state.endReason)
        assertTrue(Effect.RestoreAll in fx)
        assertEquals(min, door.state.sleptMs)

        val brake = session()
        brake.start(0, 15)
        brake.onVehicle(2 * min, parked.copy(brakePressed = true))
        assertEquals(EndReason.BRAKE_PRESSED, brake.state.endReason)
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
    fun `깬 뒤 문을 열고 내리면 끝낸다`() {
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
        val fx = when (s.state.mission!!) {
            Mission.BRAKE_TWICE -> s.pressBrake(15 * min + 1, charging) + s.pressBrake(15 * min + 10, charging)
            Mission.WIPER_ON -> s.onVehicle(15 * min + 1, charging.copy(wiperOn = true))
            Mission.WINDOW_DOWN_UP -> s.onVehicle(15 * min + 1, charging.copy(driverWindowOpenPercent = 50)) +
                s.onVehicle(15 * min + 2, charging)
        }
        assertEquals(Phase.REST, s.state.phase)
        assertTrue(fx.any { it is Effect.Alarm && it.level == 0 })
        val done = s.endRest(16 * min)
        assertEquals(Phase.DONE, s.state.phase)
        assertTrue(s.state.chargeReminder)
        assertTrue(done.any { it is Effect.Speak && "충전 케이블" in it.text })
    }

    @Test
    fun `3초 길게 눌러 끄기는 깨우는 중에만 듣는다`() {
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
        val s = NapSession(NapTimings.DEMO, Random(1), { questions })
        s.onVehicle(0, parked)
        s.start(0, 15)
        assertEquals(30_000, s.state.wakeAt)
        s.tick(30_000)
        assertEquals(Phase.WAKING, s.state.phase)
    }

    @Test
    fun `말놀이는 오늘 요일과 지금 시각을 선택지에 넣는다`() {
        val qs = WarmUpQuestions.build(dayOfWeek = 5, hour = 13, random = Random(3))
        assertEquals(2, qs.size)
        assertTrue("금요일" in qs[0].choices)
        assertTrue("오후 1시쯤" in qs[1].choices)
        assertEquals(3, qs[0].choices.toSet().size)
        assertEquals(3, qs[1].choices.toSet().size)
        assertFalse(qs[0].choices.isEmpty())
    }
}
