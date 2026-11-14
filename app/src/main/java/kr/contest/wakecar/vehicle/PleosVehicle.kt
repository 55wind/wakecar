package kr.contest.wakecar.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.contest.wakecar.core.Effect
import kr.contest.wakecar.core.FanLevel
import kr.contest.wakecar.core.VehicleSnapshot

/** 차가 대신 해 줄 수 있는 일. null = 확인 중, false = 이 차에서는 화면 안내로 대신한다. */
data class CabinSupport(
    val seat: Boolean? = null,
    val ambientLight: Boolean? = null,
    val fan: Boolean? = null,
)

/**
 * Pleos Vehicle SDK 연결을 한 곳에서 맡는다.
 *
 * - 읽기: 기어, 브레이크, 도어, 와이퍼, 창문, 배터리·연료, 충전구 → [snapshot]
 *   (기어와 충전구 API는 SDK 2.3.2에서 폐기 예정이다. 기어는 [StandardGear]로 표준 속성을 먼저 읽는다.)
 * - 제어: 무드등, 풍량 → [apply]. 앱은 등받이를 뒤로 젖히지 않는다(등받이 코드는 깨울 때 세우는 기능을 위해 남겨 둠). 시작할 때 capability로 지원 여부를 확인하고,
 *   지원하지 않으면 아무것도 하지 않는다(화면 안내로 대신). 바꾼 값은 처음 값을 기억했다가 되돌린다.
 *
 * 차량 통신은 IPC라 결과가 비동기 콜백으로 온다. 콜백은 모두 메인 스레드로 넘겨 처리한다.
 * 주행 제어(시동·제동·조향·잠금)는 쓰지 않는다.
 */
@Suppress("DEPRECATION")
class PleosVehicle(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot

    private val _support = MutableStateFlow(CabinSupport())
    val support: StateFlow<CabinSupport> = _support

    private var connected = false

    /** 기어는 표준 차량 속성 값을 먼저 쓰고, 없을 때만 Pleos 기어 API 값을 쓴다. */
    private var standardPark: Boolean? = null
    private var pleosPark: Boolean? = null
    private val standardGear = StandardGear(
        context.applicationContext,
        onPark = { park ->
            main.post {
                standardPark = park
                publishGear()
            }
        },
        onMoving = { moving -> update { it.copy(moving = moving) } },
    )

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var lockArea: DoorArea? = null
    private val driverWindow = WindowArea(WindowValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val cabinDoors = listOf(
        DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
    )

    // 읽기 값의 원본
    private val doorOpen = mutableMapOf<DoorValue, Boolean>()
    private var wiperSwitchOn: Boolean? = null
    private var wiperStateOn: Boolean? = null
    private var windowBounds: AreaBounds<Int>? = null
    private var evPercent: Int? = null
    private var fuelPercent: Int? = null

    // 제어용 상태
    private var seatPosition: Int? = null
    private var seatUpright: Int? = null
    private var ambientBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private val ambientOriginal = mutableMapOf<SeatArea, Int>()
    private var fanArea: SeatArea? = null
    private var fanBounds: AreaBounds<Int>? = null
    private var fanOriginal: Int? = null
    private var hvacPowerOriginal: Boolean? = null
    private var hvacPowerArea: SeatArea? = null

    fun connect() {
        if (connected) return
        if (!call("initialize") { vehicle.initialize() }) return
        connected = true
        update { it.copy(connected = true) }
        standardGear.start()
        readAndListen()
        checkSupport()
        main.post(wiperPoll)
    }

    /** 기어와 속도(표준 차량 속성) 읽기를 다시 건다. */
    fun refreshStandard() {
        if (!connected) return
        standardGear.stop()
        standardGear.start()
    }

    fun close() {
        if (!connected) return
        main.removeCallbacksAndMessages(null)
        standardGear.stop()
        unregisterAll()
        call("release") { vehicle.release() }
        connected = false
        _snapshot.value = VehicleSnapshot()
    }

    /** 세션이 요청한 동작 가운데 차량 제어에 해당하는 것만 처리한다. */
    fun apply(effect: Effect) {
        if (!connected) return
        when (effect) {
            is Effect.AmbientLight -> setAmbient(effect.percent)
            is Effect.Fan -> setFan(effect.level)
            is Effect.SeatRaise -> if (effect.on) startSeatRaise() else stopSeatRaise()
            Effect.RestoreAll -> {
                restoreAmbient()
                restoreFan()
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 읽기

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onGear(gear)
        override fun onFailed(e: Exception) = warn("gear listener", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = update { it.copy(brakePressed = pressed) }
        override fun onFailed(e: Exception) = warn("brake listener", e)
    }
    private val doorListeners = cabinDoors.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) = onDoor(door, position)
            override fun onFailed(e: Exception) = warn("door listener", e)
        }
    }
    private val lockListener = object : DoorLockStateListener {
        override fun onDoorLockStateUpdated(locked: Boolean?) = update { it.copy(driverDoorLocked = locked) }
        override fun onFailed(e: Exception) = warn("lock listener", e)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            main.post {
                wiperStateOn = state?.let { it == WiperState.ON }
                publishWiper()
            }
        }
        override fun onFailed(e: Exception) = warn("wiper listener", e)
    }
    private val windowListener = object : WindowPositionListener {
        override fun onWindowPositionUpdated(position: Int?) = onWindow(position)
        override fun onFailed(e: Exception) = warn("window listener", e)
    }
    private val evListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(percent: Int?) = onEnergy(ev = percent, fuel = fuelPercent)
        override fun onFailed(e: Exception) = warn("ev listener", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(percent: Int?) = onEnergy(ev = evPercent, fuel = percent)
        override fun onFailed(e: Exception) = warn("fuel listener", e)
    }
    private val chargePortListener = object : EvChargePortOpenListener {
        override fun onEvChargePortOpenUpdated(open: Boolean?) = update { it.copy(chargePortOpen = open) }
        override fun onFailed(e: Exception) = warn("charge port listener", e)
    }
    private val powerDownListener = object : PowerDownWarningIndicatorListener {
        override fun onPowerDownWarningIndicatorUpdated(on: Boolean?) = update { it.copy(powerDownWarning = on) }
        override fun onFailed(e: Exception) = warn("power down listener", e)
    }

    private val seatListener = object : SeatBackrestAnglePositionListener {
        override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
            main.post { onSeatPosition(position) }
        }
        override fun onFailed(e: Exception) = warn("seat listener", e)
    }

    private fun readAndListen() {
        val gear = vehicle.getGear()
        call("gear") {
            gear.getCurrentGearSelectionState({ onGear(it) }, { warn("gear", it) })
            gear.registerGearSelection(gearListener)
        }
        val brake = vehicle.getBrake()
        call("brake") {
            brake.getCurrentBrakePedalStatus({ pressed -> update { it.copy(brakePressed = pressed) } }, { warn("brake", it) })
            brake.registerBrakePedalStatus(brakeListener)
        }
        val door = vehicle.getDoor()
        cabinDoors.forEach { value ->
            call("door $value") {
                val area = DoorArea(value)
                door.getCurrentDoorPosition(area, { onDoor(value, it) }, { warn("door $value", it) })
                door.registerDoorPosition(area, doorListeners.getValue(value))
            }
        }
        // 잠금은 차마다 묶음 단위(영역)가 달라, 지원하는 영역을 먼저 물어본 뒤 운전석이 든 영역을 쓴다.
        call("door lock") {
            door.checkDoorLockCapabilities(
                { _, areas ->
                    val area = areas.firstOrNull { DoorValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()
                    if (area != null) {
                        main.post {
                            lockArea = area
                            call("door lock read") {
                                door.getDoorLockState(area, { locked -> update { it.copy(driverDoorLocked = locked) } }, { warn("lock", it) })
                                door.registerDoorLockState(area, lockListener)
                            }
                        }
                    }
                },
                { warn("lock capability", it) },
            )
        }
        call("wiper") { vehicle.getWiper().registerWiperState(windshield, wiperListener) }
        val window = vehicle.getWindow()
        call("window") {
            window.checkWindowPositionCapabilities(
                { _, bounds ->
                    main.post {
                        windowBounds = bounds.entries.firstOrNull { WindowValue.ROW_1_LEFT in it.key.values }?.value
                        window.getCurrentWindowPosition(driverWindow, { onWindow(it) }, { warn("window", it) })
                    }
                },
                { warn("window capability", it) },
            )
            window.registerWindowPosition(driverWindow, windowListener)
        }
        val ev = vehicle.getEvBattery()
        call("ev battery") {
            ev.getEvBatteryStateOfCharge({ onEnergy(ev = it, fuel = fuelPercent) }, { warn("ev soc", it) })
            ev.registerEvBatteryStateOfCharge(evListener)
            ev.isEvChargePortOpen({ open -> update { it.copy(chargePortOpen = open) } }, { warn("charge port", it) })
            ev.registerEvChargePortOpen(chargePortListener)
        }
        // 전원 차단 경고: 뜨면 자는 중이라도 바로 깨운다.
        call("power down warning") {
            val safety = vehicle.getSafety()
            safety.hasPowerDownWarningIndicator({ on -> update { it.copy(powerDownWarning = on) } }, { warn("power down", it) })
            safety.registerPowerDownWarningIndicator(powerDownListener)
        }
        val fuel = vehicle.getFuel()
        call("fuel") {
            fuel.getFuelPercent({ onEnergy(ev = evPercent, fuel = it) }, { warn("fuel", it) })
            fuel.registerFuelPercent(fuelListener)
        }
    }

    private fun unregisterAll() {
        call("unregister") {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            val door = vehicle.getDoor()
            cabinDoors.forEach { door.unregisterDoorPosition(DoorArea(it), doorListeners.getValue(it)) }
            lockArea?.let { door.unregisterDoorLockState(it, lockListener) }
            vehicle.getWiper().unregisterWiperState(windshield, wiperListener)
            vehicle.getWindow().unregisterWindowPosition(driverWindow, windowListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(evListener)
            vehicle.getEvBattery().unregisterEvChargePortOpen(chargePortListener)
            vehicle.getFuel().unregisterFuelPercent(fuelListener)
            vehicle.getSafety().unregisterPowerDownWarningIndicator(powerDownListener)
            vehicle.getSeat().unregisterSeatBackrestAnglePosition(driverSeat, seatListener)
        }
    }

    private fun onGear(gear: VehicleGear?) {
        main.post {
            pleosPark = if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) null else gear == VehicleGear.GEAR_PARK
            publishGear()
        }
    }

    private fun publishGear() {
        val park = standardPark ?: pleosPark
        if (_snapshot.value.inPark != park) _snapshot.value = _snapshot.value.copy(inPark = park)
    }

    private fun onDoor(door: DoorValue, position: Int?) {
        main.post {
            if (position != null) doorOpen[door] = position > 0
            _snapshot.value = _snapshot.value.copy(
                anyDoorOpen = if (doorOpen.isEmpty()) null else doorOpen.values.any { it },
            )
        }
    }

    private fun onWindow(position: Int?) = update { snapshot ->
        val bounds = windowBounds
        val percent = when {
            position == null -> null
            bounds == null || bounds.maxValue <= bounds.minValue -> position.coerceIn(0, 100)
            else -> ((position - bounds.minValue) * 100 / (bounds.maxValue - bounds.minValue)).coerceIn(0, 100)
        }
        snapshot.copy(driverWindowOpenPercent = percent)
    }

    private fun onEnergy(ev: Int?, fuel: Int?) {
        main.post {
            evPercent = ev?.takeIf { it in 0..100 }
            fuelPercent = fuel?.takeIf { it in 0..100 }
            _snapshot.value = _snapshot.value.copy(
                energyPercent = evPercent ?: fuelPercent,
                runsOnFuel = when {
                    evPercent != null -> false
                    fuelPercent != null -> true
                    else -> null
                },
            )
        }
    }

    /**
     * 와이퍼 스위치(Off·Auto·Low·High)는 변경 알림이 없어 주기적으로 읽는다.
     * 작동 상태 알림(ON/OFF)도 함께 받아, 둘 중 하나라도 켜지면 켜진 것으로 본다.
     */
    private val wiperPoll = object : Runnable {
        override fun run() {
            if (!connected) return
            call("wiper switch") {
                vehicle.getWiper().getCurrentWiperSwitch(
                    windshield,
                    { switch ->
                        main.post {
                            wiperSwitchOn = switch?.let { it != WiperSwitch.OFF && it != WiperSwitch.OTHER }
                            publishWiper()
                        }
                    },
                    { },
                )
            }
            main.postDelayed(this, WIPER_POLL_MS)
        }
    }

    private fun publishWiper() {
        val on = when {
            wiperSwitchOn == null && wiperStateOn == null -> null
            else -> wiperSwitchOn == true || wiperStateOn == true
        }
        if (_snapshot.value.wiperOn != on) _snapshot.value = _snapshot.value.copy(wiperOn = on)
    }

    // ---------------------------------------------------------------- 제어

    private fun checkSupport() {
        val seat = vehicle.getSeat()
        call("seat capability") {
            seat.checkMoveSeatBackrestAngleCapabilities(
                { _, areas ->
                    val ok = areas.any { SeatValue.ROW_1_LEFT in it.values }
                    supportUpdate { it.copy(seat = ok) }
                    if (ok) {
                        call("seat position") {
                            seat.getCurrentSeatBackrestAnglePosition(
                                driverSeat,
                                { p -> main.post { onSeatPosition(p) } },
                                { warn("seat position", it) },
                            )
                            seat.registerSeatBackrestAnglePosition(driverSeat, seatListener)
                        }
                    }
                },
                { supportUpdate { it.copy(seat = false) } },
            )
        } || supportUpdate { it.copy(seat = false) }

        val light = vehicle.getLight()
        call("ambient capability") {
            light.checkAmbientLightBrightnessCapabilities(
                { _, bounds ->
                    main.post { ambientBounds = bounds }
                    supportUpdate { it.copy(ambientLight = bounds.isNotEmpty()) }
                },
                { supportUpdate { it.copy(ambientLight = false) } },
            )
        } || supportUpdate { it.copy(ambientLight = false) }

        val hvac = vehicle.getHVAC()
        call("hvac power capability") {
            hvac.checkHvacPowerCapabilities(
                { _, areas -> main.post { hvacPowerArea = areas.firstOrNull() } },
                { warn("hvac power capability", it) },
            )
        }
        call("fan capability") {
            hvac.checkHvacFanSpeedCapabilities(
                { _, bounds ->
                    val entry = bounds.entries.firstOrNull()
                    Log.i(TAG, "fan areas " + bounds.entries.joinToString { e ->
                        e.key.values.joinToString("+") + "=" + e.value.minValue + ".." + e.value.maxValue
                    })
                    main.post {
                        fanArea = entry?.key
                        fanBounds = entry?.value
                    }
                    supportUpdate { it.copy(fan = entry != null) }
                },
                { supportUpdate { it.copy(fan = false) } },
            )
        } || supportUpdate { it.copy(fan = false) }
    }

    // 등받이 일으키기: 세우는 방향(FORWARD)으로만 움직인다. 뒤로 젖히지 않는다.
    // 한 번에 2초 이내로 움직이고 5초 이상 쉬며, 움직인 시간이 모두 10초가 되면 멈춘다.
    // 위치가 읽히는 차에서는 기억해 둔 각도에 닿으면 더 일찍 멈춘다.

    private var raiseLeftMs = 0L

    private val raisePulse: Runnable = object : Runnable {
        override fun run() {
            val upright = seatUpright
            val now = seatPosition
            if (raiseLeftMs <= 0 || (upright != null && now != null && now >= upright)) {
                raiseLeftMs = 0
                return
            }
            val pulse = minOf(SEAT_RAISE_PULSE_MS, raiseLeftMs)
            raiseLeftMs -= pulse
            sendSeat(MoveControl.FORWARD)
            main.postDelayed(stopPulse, pulse)
            if (raiseLeftMs > 0) main.postDelayed(this, pulse + SEAT_RAISE_REST_MS)
        }
    }

    private val stopPulse = Runnable { sendSeat(MoveControl.STOP) }

    private fun startSeatRaise() {
        if (_support.value.seat != true) return
        raiseLeftMs = SEAT_RAISE_TOTAL_MS
        main.post(raisePulse)
    }

    private fun stopSeatRaise() {
        raiseLeftMs = 0
        main.removeCallbacks(raisePulse)
        main.removeCallbacks(stopPulse)
        sendSeat(MoveControl.STOP)
    }

    /** 처음 켤 때 깨어 있는 상태에서 한 번 보여 준다(1초). */
    fun previewSeatRaise() {
        if (!connected || _support.value.seat != true) return
        sendSeat(MoveControl.FORWARD)
        main.postDelayed(stopPulse, 1_000)
    }

    private fun sendSeat(control: MoveControl) {
        call("seat $control") {
            vehicle.getSeat().moveSeatBackrestAngle(driverSeat, control, { _, _ -> }, { warn("seat $control", it) })
        }
    }

    /** 자기 전의 등받이 각도를 기억한다. 값이 클수록 세워진 상태이므로 낮잠 전에 본 가장 큰 값을 쓴다. */
    private fun onSeatPosition(position: Int?) {
        seatPosition = position
        if (position != null && raiseLeftMs == 0L) seatUpright = maxOf(seatUpright ?: position, position)
    }

    // 무드등: 처음 밝기를 읽어 둔 뒤 바꾸고, 끝나면 되돌린다.

    private fun setAmbient(percent: Int) {
        if (_support.value.ambientLight != true) return
        val light = vehicle.getLight()
        ambientBounds.forEach { (area, bounds) ->
            val target = bounds.minValue + (bounds.maxValue - bounds.minValue) * percent.coerceIn(0, 100) / 100
            val set = {
                call("ambient set") {
                    light.setAmbientLightBrightness(area, target, { _, _ -> }, { warn("ambient set", it) })
                }
            }
            if (area in ambientOriginal) {
                set()
            } else {
                call("ambient get") {
                    light.getAmbientLightBrightness(
                        area,
                        { value -> main.post { if (value != null) ambientOriginal.putIfAbsent(area, value); set() } },
                        { main.post { set() } },
                    )
                }
            }
        }
    }

    private fun restoreAmbient() {
        val light = vehicle.getLight()
        ambientOriginal.forEach { (area, value) ->
            call("ambient restore") {
                light.setAmbientLightBrightness(area, value, { _, _ -> }, { warn("ambient restore", it) })
            }
        }
        ambientOriginal.clear()
    }

    // 풍량: 처음 값보다 낮추지는 않는다.

    private fun setFan(level: FanLevel) {
        val area = fanArea ?: return
        val bounds = fanBounds ?: return
        val wanted = when (level) {
            FanLevel.MEDIUM -> (bounds.minValue + bounds.maxValue + 1) / 2
            FanLevel.MAX -> bounds.maxValue
        }
        val set = { original: Int? ->
            val target = maxOf(wanted, original ?: wanted)
            call("fan set") {
                vehicle.getHVAC().setHvacFanSpeed(
                    area, target,
                    { _, value -> Log.i(TAG, "fan set $value") },
                    {
                        warn("fan set $target", it)
                        supportUpdate { support -> support.copy(fan = false) }
                    },
                )
            }
        }
        val setFromOriginal = {
            val original = fanOriginal
            if (original != null) {
                set(original)
            } else {
                call("fan get") {
                    vehicle.getHVAC().getHvacFanSpeed(
                        area,
                        { value -> main.post { fanOriginal = value; set(value) } },
                        { main.post { set(null) } },
                    )
                }
            }
        }
        // 공조가 꺼져 있으면 풍량을 바꿀 수 없다. 처음 전원 상태를 기억해 두고 켠 뒤에 풍량을 올린다.
        if (hvacPowerOriginal != null) {
            setFromOriginal()
        } else {
            call("hvac power get") {
                vehicle.getHVAC().getHvacPowerState(
                    hvacPowerArea ?: area,
                    { on ->
                        main.post {
                            hvacPowerOriginal = on ?: true
                            Log.i(TAG, "hvac power $on")
                            if (on == false) {
                                call("hvac power on") {
                                    vehicle.getHVAC().setHvacPower(
                                        hvacPowerArea ?: area, true,
                                        { _, _ -> main.post { setFromOriginal() } },
                                        { warn("hvac power on", it); main.post { setFromOriginal() } },
                                    )
                                }
                            } else {
                                setFromOriginal()
                            }
                        }
                    },
                    { warn("hvac power get", it); main.post { setFromOriginal() } },
                )
            }
        }
    }

    private fun restoreFan() {
        val area = fanArea ?: return
        val original = fanOriginal
        fanOriginal = null
        if (original != null) {
            call("fan restore") {
                vehicle.getHVAC().setHvacFanSpeed(area, original, { _, _ -> }, { warn("fan restore", it) })
            }
        }
        val power = hvacPowerOriginal
        hvacPowerOriginal = null
        if (power == false) {
            call("hvac power restore") {
                vehicle.getHVAC().setHvacPower(hvacPowerArea ?: area, false, { _, _ -> }, { warn("hvac power restore", it) })
            }
        }
    }

    // ---------------------------------------------------------------- 공통

    private fun update(block: (VehicleSnapshot) -> VehicleSnapshot) {
        main.post { _snapshot.value = block(_snapshot.value) }
    }

    private fun supportUpdate(block: (CabinSupport) -> CabinSupport): Boolean {
        main.post { _support.value = block(_support.value) }
        return true
    }

    /** SDK 호출이 예외를 던져도 앱이 죽지 않게 감싼다. 성공하면 true. */
    private fun call(what: String, block: () -> Unit): Boolean =
        runCatching(block).onFailure { warn(what, it) }.isSuccess

    private fun warn(what: String, e: Throwable) {
        Log.w(TAG, "$what: ${e.message ?: e}")
    }

    private companion object {
        const val TAG = "WakeCar"
        const val WIPER_POLL_MS = 700L
        const val SEAT_RAISE_PULSE_MS = 2_000L
        const val SEAT_RAISE_REST_MS = 5_000L
        const val SEAT_RAISE_TOTAL_MS = 10_000L
    }
}
