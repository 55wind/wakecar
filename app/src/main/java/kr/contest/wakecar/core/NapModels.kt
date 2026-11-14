package kr.contest.wakecar.core

/** 낮잠 한 번의 진행 단계. */
enum class Phase { SETUP, SLEEPING, WAKING, GUARD, DONE }

/** 깨어남 미션. 화면을 눌러서는 알람이 꺼지지 않고, 차량 조작이 감지되어야 멈춘다. */
enum class Mission(val prompt: String, val steps: Int) {
    BRAKE_TWICE("브레이크를 두 번 밟아 주세요.", 2),
    WIPER_ON("와이퍼를 한 번 켜 주세요.", 1),
    WINDOW_DOWN_UP("운전석 창문을 내렸다가 다시 올려 주세요.", 2),
}

enum class EndReason {
    COMPLETED,
    /** 깬 뒤 문을 열고 내림. */
    LEFT_CAR,
    USER_ENDED,
    GEAR_LEFT_PARK,
    /** 자는 중 문이 열림. */
    DOOR_OPENED,
    /** 자는 중 브레이크를 밟음. */
    BRAKE_PRESSED,
    EMERGENCY_STOP,
}

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

/**
 * 세션이 바깥(차량 제어, 소리)에 요청하는 동작. 세션 자체는 차량 API를 모른다.
 * 앱은 등받이를 뒤로 젖히지 않는다. 등받이는 운전자가 직접 젖힌다.
 */
sealed interface Effect {
    data class AmbientLight(val percent: Int) : Effect
    data class Fan(val level: FanLevel) : Effect
    /** 0 = 끔, 1~3 = 점점 크고 빠르게. */
    data class Alarm(val level: Int) : Effect
    data class Speak(val text: String) : Effect
    /** 무드등과 공조를 낮잠 전 상태로 되돌린다. */
    data object RestoreAll : Effect
}

/** 깬 직후의 짧은 말놀이. 맞고 틀림은 따지지 않는다. */
data class Question(val text: String, val choices: List<String>)

/**
 * 시간 설정. 시연 모드는 같은 흐름을 짧게 줄여 보여 준다.
 * 모든 값은 밀리초.
 */
data class NapTimings(
    /** 낮잠 1분이 실제로 흐르는 시간. */
    val msPerNapMinute: Long,
    /** 정한 시각보다 이만큼 먼저 조명을 밝히기 시작한다(1단계). */
    val preWakeMs: Long,
    /** 알람(2단계) 뒤 반응이 없으면 바람을 세게 하고 알람 간격을 줄이는 시점(3단계). */
    val stage3AfterMs: Long,
    /** 알람 뒤 반응이 없으면 마지막 단계로 올라가는 시점(4단계). */
    val stage4AfterMs: Long,
    /** 미션 뒤 아무 차량 조작이 없으면 깨우기를 다시 시작하기까지 기다리는 시간. */
    val guardMs: Long,
    /** '5분만 더'로 미루는 시간. 한 번만 쓸 수 있다. */
    val snoozeMs: Long,
) {
    companion object {
        val REAL = NapTimings(
            msPerNapMinute = 60_000,
            preWakeMs = 3 * 60_000,
            stage3AfterMs = 60_000,
            stage4AfterMs = 5 * 60_000,
            guardMs = 3 * 60_000,
            snoozeMs = 5 * 60_000,
        )

        /** 시연 모드: 낮잠 1분을 2초로 줄이고, 깨우기 단계 간격도 짧게 잡는다. */
        val DEMO = NapTimings(
            msPerNapMinute = 2_000,
            preWakeMs = 6_000,
            stage3AfterMs = 8_000,
            stage4AfterMs = 20_000,
            guardMs = 20_000,
            snoozeMs = 10_000,
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
    val guardDeadline: Long = 0,
    val question: Question? = null,
    val questionNumber: Int = 0,
    val questionCount: Int = 0,
    /** '5분만 더'를 이미 썼는지. */
    val snoozeUsed: Boolean = false,
    /** 미션을 하고도 다시 잠들어 한 번 더 깨운 횟수. */
    val realarmCount: Int = 0,
    val wokenEarlyForEnergy: Boolean = false,
    val endReason: EndReason? = null,
    val sleptMs: Long = 0,
    /** 알람부터 첫 미션 완료까지 걸린 시간. */
    val wakeResponseMs: Long? = null,
    val chargeReminder: Boolean = false,
)
