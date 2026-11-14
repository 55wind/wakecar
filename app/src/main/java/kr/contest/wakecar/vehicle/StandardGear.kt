package kr.contest.wakecar.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * Android Automotive 표준 차량 속성 GEAR_SELECTION 으로 기어를 읽는다.
 *
 * Pleos Vehicle SDK 2.3.2 는 기어 API를 폐기 예정으로 표시하고 이 속성을 쓰라고 안내한다.
 * 그래서 표준 속성을 먼저 쓰고, 읽지 못하는 환경에서만 Pleos 기어 API 값을 쓴다([PleosVehicle]).
 *
 * @param onPark true = P, false = P 아님, null = 알 수 없음
 */
class StandardGear(
    private val context: Context,
    private val onPark: (Boolean?) -> Unit,
    /** true = 차가 움직이는 중(속도가 0이 아님), null = 속도를 읽을 수 없음 */
    private val onMoving: (Boolean?) -> Unit = {},
) {

    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> publish(value.value as? Int)
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> publishSpeed(value.value as? Float)
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) = Unit
    }

    /** 자동차용 Android가 아니면 관련 클래스가 없으므로 Throwable 까지 잡는다. */
    fun start(): Boolean = try {
        val created = Car.createCar(context)
        val properties = created.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        car = created
        manager = properties
        @Suppress("DEPRECATION")
        properties.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        publish(properties.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)?.value)
        // 속도는 읽을 수 없는 차도 있다. 실패해도 기어 읽기는 그대로 둔다.
        try {
            @Suppress("DEPRECATION")
            properties.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            publishSpeed(properties.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)?.value)
        } catch (t: Throwable) {
            Log.w("WakeCar", "standard speed: ${t.message ?: t}")
        }
        true
    } catch (t: Throwable) {
        Log.w("WakeCar", "standard gear: ${t.message ?: t}")
        false
    }

    fun stop() {
        try {
            @Suppress("DEPRECATION")
            manager?.unregisterCallback(callback)
            car?.disconnect()
        } catch (t: Throwable) {
            Log.w("WakeCar", "standard gear stop: ${t.message ?: t}")
        }
        manager = null
        car = null
    }

    private fun publishSpeed(metersPerSecond: Float?) {
        onMoving(metersPerSecond?.let { kotlin.math.abs(it) > 0.3f })
    }

    private fun publish(gear: Int?) {
        onPark(if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) null else gear == VehicleGear.GEAR_PARK)
    }
}
