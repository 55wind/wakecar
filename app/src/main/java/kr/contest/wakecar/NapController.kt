package kr.contest.wakecar

import android.app.Application
import android.os.SystemClock
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.contest.wakecar.audio.AlarmPlayer
import kr.contest.wakecar.audio.Speaker
import kr.contest.wakecar.core.Effect
import kr.contest.wakecar.core.NapSession
import kr.contest.wakecar.core.NapState
import kr.contest.wakecar.core.NapStats
import kr.contest.wakecar.core.NapTimings
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.core.StartBlock
import kr.contest.wakecar.core.VehicleSnapshot
import kr.contest.wakecar.core.WarmUpQuestions
import kr.contest.wakecar.vehicle.CabinSupport
import kr.contest.wakecar.vehicle.PleosVehicle

/** 기상 시각을 정하는 두 가지 방법: 잘 시간(분) 또는 돌아가야 할 시각. */
data class NapPlan(
    val byReturnTime: Boolean = false,
    val minutes: Int = 15,
    val returnByMinuteOfDay: Int = 0,
) {
    /** 잘 수 있는 시간(분). 돌아갈 시각까지 여유가 없으면 null. */
    fun napMinutes(nowMinuteOfDay: Int): Int? {
        if (!byReturnTime) return minutes
        val untilReturn = (returnByMinuteOfDay - nowMinuteOfDay).mod(MINUTES_PER_DAY)
        val available = untilReturn - WALK_MINUTES - WAKE_MINUTES
        return if (available < MIN_NAP_MINUTES) null else minOf(available, MAX_NAP_MINUTES)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        /** 차에서 자리까지 걸어가는 시간. */
        const val WALK_MINUTES = 5
        /** 알람이 울리고 미션·확인 질문을 마치는 데 잡아 둔 시간. */
        const val WAKE_MINUTES = 3
        const val MIN_NAP_MINUTES = 5
        const val MAX_NAP_MINUTES = 30
    }
}

data class UiState(
    val nap: NapState = NapState(),
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val support: CabinSupport = CabinSupport(),
    val plan: NapPlan = NapPlan(),
    val demo: Boolean = false,
    val msPerNapMinute: Long = NapTimings.REAL.msPerNapMinute,
    val startBlock: StartBlock? = StartBlock.NOT_CONNECTED,
    val clockMinuteOfDay: Int = 0,
    /** 자는 중: 깨울 때까지 남은 낮잠 시간(분, 올림). */
    val napMinutesLeft: Int = 0,
    /** 확인 질문: 다시 깨우기까지 남은 시간(초, 올림). */
    val guardSecondsLeft: Int = 0,
    /** 깬 뒤 쉬는 시간: '출발할게'를 누를 수 있을 때까지 남은 시간(초, 올림). */
    val restSecondsLeft: Int = 0,
    /** 등받이 일으키기를 켰는지. 기본은 꺼짐. */
    val seatRaise: Boolean = false,
    /** 이 차에만 저장한 익명 휴식 기록. */
    val stats: NapStats = NapStats(),
)

/**
 * 세션·차량·소리를 잇는다. 화면이 다시 만들어져도 낮잠이 이어지도록 Application이 들고 있는다.
 * 모든 호출은 메인 스레드에서 한다.
 */
class NapController(private val app: Application) {

    private val vehicle = PleosVehicle(app)
    private val speaker = Speaker(app)
    private val alarm = AlarmPlayer()
    private val scope = MainScope()

    private var demo = false
    private var seatRaise = false
    private var serviceActive = false
    private val prefs = app.getSharedPreferences("nap_stats", android.content.Context.MODE_PRIVATE)
    private var stats = NapStats(
        woken = prefs.getInt("woken", 0),
        realarmed = prefs.getInt("realarmed", 0),
        departed = prefs.getInt("departed", 0),
        restFilled = prefs.getInt("restFilled", 0),
    )
    /** 이미 기록한 낮잠(시작 시각). 같은 낮잠을 두 번 세지 않는다. */
    private var recordedStart = -1L
    private var plan = NapPlan()
    private var timings = NapTimings.REAL
    private var session = newSession()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        vehicle.connect()
        scope.launch { vehicle.snapshot.collect { dispatch(session.onVehicle(now(), it)) } }
        scope.launch { vehicle.support.collect { publish() } }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                dispatch(session.tick(now()))
            }
        }
    }

    fun onAppOpened() = vehicle.connect()

    /** 속도 읽기 허용 여부가 바뀌면 표준 속성 읽기를 다시 건다. */
    fun onPermissionChanged() = vehicle.refreshStandard()

    /** 앱을 완전히 닫을 때. 낮잠 중이면 연결을 유지한다. */
    fun onAppClosed() {
        val phase = session.state.phase
        if (phase == Phase.SETUP || phase == Phase.DONE) vehicle.close()
    }

    fun selectMinutes(minutes: Int) {
        plan = plan.copy(byReturnTime = false, minutes = minutes)
        publish()
    }

    fun selectReturnTime() {
        // 기본값: 25분 뒤를 5분 단위로 올림한 시각.
        val base = minuteOfDay() + 25
        plan = plan.copy(byReturnTime = true, returnByMinuteOfDay = ((base + 4) / 5 * 5).mod(NapPlan.MINUTES_PER_DAY))
        publish()
    }

    fun shiftReturnTime(deltaMinutes: Int) {
        plan = plan.copy(returnByMinuteOfDay = (plan.returnByMinuteOfDay + deltaMinutes).mod(NapPlan.MINUTES_PER_DAY))
        publish()
    }

    fun setDemo(on: Boolean) {
        if (session.state.phase != Phase.SETUP || on == demo) return
        demo = on
        timings = if (on) NapTimings.DEMO else NapTimings.REAL
        session = newSession().also { it.onVehicle(now(), vehicle.snapshot.value) }
        publish()
    }

    /** 등받이 일으키기. 처음 켤 때 깨어 있는 상태에서 한 번 미리 보여 준다. */
    fun setSeatRaise(on: Boolean) {
        if (session.state.phase != Phase.SETUP || on == seatRaise) return
        seatRaise = on
        if (on) vehicle.previewSeatRaise()
        publish()
    }

    fun start() {
        val minutes = plan.napMinutes(minuteOfDay()) ?: return
        dispatch(session.start(now(), minutes))
    }

    fun endNap() = dispatch(session.endNap(now()))

    fun emergencyStop() = dispatch(session.emergencyStop(now()))

    fun answer() = dispatch(session.answer(now()))

    fun snooze() = dispatch(session.snooze(now()))

    fun depart() = dispatch(session.depart(now()))

    fun endRest() = dispatch(session.endRest(now()))

    /** 깨우는 중 화면을 누르면 등받이만 멈춘다. 알람은 계속 울린다. */
    fun stopSeatRaise() = dispatch(session.stopSeatRaise())

    fun reset() {
        session.reset()
        publish()
    }

    private fun newSession() = NapSession(timings, Random.Default, questionSource = {
        val calendar = Calendar.getInstance()
        // Calendar: 일요일 = 1 … 토요일 = 7 → 월요일 = 1 … 일요일 = 7
        val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        WarmUpQuestions.build(dayOfWeek, calendar.get(Calendar.HOUR_OF_DAY), Random.Default)
    }, seatRaiseEnabled = { seatRaise })

    private fun dispatch(effects: List<Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is Effect.Speak -> speaker.speak(effect.text)
                is Effect.Alarm -> alarm.setLevel(effect.level)
                else -> vehicle.apply(effect)
            }
        }
        publish()
    }

    private fun publish() {
        val now = now()
        val nap = session.state
        // 낮잠이 진행되는 동안에는 다른 화면으로 넘어가도 알람이 울리도록 서비스를 켜 둔다.
        if (nap.phase == Phase.DONE && nap.startedAt != recordedStart) {
            recordedStart = nap.startedAt
            stats = stats.add(nap)
            prefs.edit()
                .putInt("woken", stats.woken)
                .putInt("realarmed", stats.realarmed)
                .putInt("departed", stats.departed)
                .putInt("restFilled", stats.restFilled)
                .apply()
        }
        val active = nap.phase != Phase.SETUP && nap.phase != Phase.DONE
        if (active != serviceActive) {
            serviceActive = active
            NapService.setActive(app, active)
        }
        _ui.value = UiState(
            nap = nap,
            vehicle = vehicle.snapshot.value,
            support = vehicle.support.value,
            plan = plan,
            demo = demo,
            msPerNapMinute = timings.msPerNapMinute,
            startBlock = session.startBlock(),
            clockMinuteOfDay = minuteOfDay(),
            napMinutesLeft = if (nap.phase == Phase.SLEEPING) {
                ceilDiv(nap.wakeAt - now, timings.msPerNapMinute)
            } else {
                0
            },
            guardSecondsLeft = if (nap.phase == Phase.GUARD) ceilDiv(nap.guardDeadline - now, 1000) else 0,
            restSecondsLeft = if (nap.phase == Phase.REST) ceilDiv(nap.restUntil - now, 1000) else 0,
            seatRaise = seatRaise,
            stats = stats,
        )
    }

    private fun ceilDiv(value: Long, unit: Long): Int = ((value.coerceAtLeast(0) + unit - 1) / unit).toInt()

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun minuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
