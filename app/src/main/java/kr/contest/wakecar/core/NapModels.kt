package kr.contest.wakecar.core

/** 낮잠 한 번의 진행 단계. */
enum class Phase { SETUP, SLEEPING, WAKING, GUARD, DONE }

/** 기상 미션. 화면을 눌러서는 알람이 꺼지지 않고, 차량 조작이 감지되어야 멈춘다. */
enum class Mission(val prompt: String, val steps: Int) {
    BRAKE_TWICE("브레이크를 두 번 밟아 주세요.", 2),
    WIPER_ON("와이퍼를 한 번 켜 주세요.", 1),
    WINDOW_DOWN_UP("운전석 창문을 내렸다가 다시 올려 주세요.", 2),
}

enum class EndReason { COMPLETED, LEFT_CAR, USER_ENDED, GEAR_LEFT_PARK, EMERGENCY_STOP }

enum class Interruption { DOOR_OPENED, BRAKE_PRESSED }

enum class StartBlock { NOT_CONNECTED, NOT_IN_PARK, DOOR_OPEN, LOW_ENERGY }

enum class FanLevel { MEDIUM, MAX }

/** 차량에서 읽은 현재 값. null 은 이 차(또는 에뮬레이터)에서 읽을 수 없다는 뜻이다. */
data class VehicleSnapshot(
    val connected: Boolean = false,
    val inPark: Boolean? = null,
    val brakePressed: Boolean? = null,
    val anyDoorOpen: Boolean? = null,
    val driverDoorLocked: Boolean? = null,
    val wiperOn: Boolean? = null,
    /** 운전석 창문이 열린 정도. 0 = 닫힘, 100 = 완전히 열림. */
    val driverWindowOpenPercent: Int? = null,
    /** 전기차는 배터리 잔량, 엔진차는 연료량(%). */
    val energyPercent: Int? = null,
    /** 연료로 달리는 차(엔진차)인지. 배터리 잔량 대신 연료량만 읽히면 true. */
    val runsOnFuel: Boolean? = null,
    val chargePortOpen: Boolean? = null,
)

/** 세션이 바깥(차량 제어, 소리)에 요청하는 동작. 세션 자체는 차량 API를 모른다. */
sealed interface Effect {
    data object SeatRecline : Effect
    data object SeatRestore : Effect
    data class AmbientLight(val percent: Int) : Effect
    /** on = 외기 유입, off = 낮잠 전 내·외기 설정으로 되돌림. */
    data class FreshAir(val on: Boolean) : Effect
    data class Fan(val level: FanLevel) : Effect
    /** 0 = 끔, 1~3 = 점점 크고 빠르게. */
    data class Alarm(val level: Int) : Effect
    data class Speak(val text: String) : Effect
    /** 등받이·조명·공조를 낮잠 전 상태로 되돌린다. */
    data object RestoreAll : Effect
}

data class Question(val text: String, val choices: List<String>, val answerIndex: Int)

/**
 * 시간 설정. 시연 모드는 같은 흐름을 짧게 줄여 보여 준다.
 * 모든 값은 밀리초.
 */
data class NapTimings(
    /** 낮잠 1분이 실제로 흐르는 시간. */
    val msPerNapMinute: Long,
    /** 기상 시각보다 이만큼 먼저 조명을 밝히기 시작한다(1단계). */
    val preWakeMs: Long,
    /** 기상 알림(2단계) 뒤 반응이 없으면 외기·송풍을 켜는 시점(3단계). */
    val stage3AfterMs: Long,
    /** 기상 알림 뒤 반응이 없으면 송풍·알람을 최대로 올리는 시점(4단계). */
    val stage4AfterMs: Long,
    /** 미션 뒤 확인 질문에 답이 없으면 다시 깨우기까지 기다리는 시간. */
    val guardMs: Long,
    val freshAirEveryMs: Long,
    val freshAirForMs: Long,
    /** 자는 중 조작이 감지되어 띄운 "끝낼까요?" 질문을 거두는 시간. */
    val interruptionPromptMs: Long,
    /** 확인 질문에 이보다 늦게 답하면 "몸을 풀고 움직이세요"를 안내한다. */
    val slowAnswerMs: Long,
) {
    companion object {
        val REAL = NapTimings(
            msPerNapMinute = 60_000,
            preWakeMs = 5 * 60_000,
            stage3AfterMs = 60_000,
            stage4AfterMs = 5 * 60_000,
            guardMs = 3 * 60_000,
            freshAirEveryMs = 15 * 60_000,
            freshAirForMs = 60_000,
            interruptionPromptMs = 30_000,
            slowAnswerMs = 8_000,
        )

        /** 시연 모드: 낮잠 1분을 2초로 줄이고, 깨우기 단계 간격도 짧게 잡는다. */
        val DEMO = NapTimings(
            msPerNapMinute = 2_000,
            preWakeMs = 10_000,
            stage3AfterMs = 8_000,
            stage4AfterMs = 20_000,
            guardMs = 20_000,
            freshAirEveryMs = 12_000,
            freshAirForMs = 4_000,
            interruptionPromptMs = 15_000,
            slowAnswerMs = 8_000,
        )
    }
}

data class NapState(
    val phase: Phase = Phase.SETUP,
    val startedAt: Long = 0,
    val wakeAt: Long = 0,
    /** 0 = 자는 중, 1~4 = 깨우기 단계. */
    val stage: Int = 0,
    val mission: Mission? = null,
    val missionStep: Int = 0,
    val interruption: Interruption? = null,
    val guardDeadline: Long = 0,
    val question: Question? = null,
    val questionNumber: Int = 0,
    val questionCount: Int = 0,
    val wrongAnswer: Boolean = false,
    /** 미션을 하고도 다시 잠들어 한 번 더 깨운 횟수. */
    val realarmCount: Int = 0,
    val wokenEarlyForEnergy: Boolean = false,
    val endReason: EndReason? = null,
    val sleptMs: Long = 0,
    /** 기상 알림부터 첫 미션 완료까지 걸린 시간. */
    val wakeResponseMs: Long? = null,
    val needsStretch: Boolean = false,
    val chargeReminder: Boolean = false,
)
