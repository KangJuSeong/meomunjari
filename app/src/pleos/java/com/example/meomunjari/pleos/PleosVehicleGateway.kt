package com.example.meomunjari.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.Wiper
import android.content.Context
import android.util.Log
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.VehicleGateway
import com.example.meomunjari.core.vehicle.VehicleSnapshot
import com.example.meomunjari.domain.SettingCompatibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.reflect.KClass

/**
 * Pleos Vehicle SDK 2.3.2 + AAOS CarPropertyManager(기어·속도) 기반 VehicleGateway.
 * 메서드 이름은 API 레퍼런스(document.pleos.ai)의 도메인별 목록에서 확인했다.
 */
class PleosVehicleGateway(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleGateway {

    private val vehicle = Vehicle(context.applicationContext)
    private val car = AaosCarProperties(context.applicationContext)
    private val invoker = PleosInvoker()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _capabilities = MutableStateFlow<Set<ControlItem>>(emptySet())
    override val capabilities: StateFlow<Set<ControlItem>> = _capabilities.asStateFlow()

    private var initialized = false
    private var pollJob: Job? = null

    // 도메인 객체 getter 이름은 공식 샘플의 VehicleApiExplorer와 같다.
    private val hvac: Any by lazy { vehicle.getHVAC() }
    private val ev: Any by lazy { vehicle.getEvBattery() }
    private val fuel: Any by lazy { vehicle.getFuel() }
    private val seat: Any by lazy { vehicle.getSeat() }
    private val window: Any by lazy { vehicle.getWindow() }
    private val door: Any by lazy { vehicle.getDoor() }
    private val light: Any by lazy { vehicle.getLight() }
    private val wiper: Any by lazy { vehicle.getWiper() }

    override fun start() {
        if (initialized) return
        try {
            vehicle.initialize()
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle SDK initialize failed", e)
            return
        }
        _snapshot.update { it.copy(connected = true) }
        checkCapabilities()
        subscribe()
        car.connect()
        pollJob = scope.launch {
            while (isActive) {
                _snapshot.update { it.copy(gear = car.gear(), speedKmh = car.speedKmh()) }
                delay(1_000)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        car.release()
        if (initialized) {
            runCatching { vehicle.release() }
            invoker.clear()
            initialized = false
        }
    }

    // ---------- 상태 구독 ----------

    private fun subscribe() {
        // 배터리(EV) / 연료(내연기관): 지원하는 쪽만 값이 들어온다.
        read(EvBattery::class, ev, "getEvBatteryStateOfCharge", "registerEvBatteryStateOfCharge") { v ->
            PleosInvoker.number(v)?.let { pct -> _snapshot.update { it.copy(batteryPct = pct) } }
        }
        read(Fuel::class, fuel, "getFuelPercent", "registerFuelPercent") { v ->
            PleosInvoker.number(v)?.let { pct -> _snapshot.update { it.copy(fuelPct = pct) } }
        }
        invoker.call(EvBattery::class, ev, "getCurrentEvEfficiency", onResult = { v ->
            PleosInvoker.number(v)?.let { eff -> _snapshot.update { it.copy(efficiencyKmPerKWh = eff) } }
        })
        // 실내 온도·공조
        read(HVAC::class, hvac, "getHvacTemperatureCurrent", "registerHvacTemperatureCurrent", DRIVER_SEAT) { v ->
            PleosInvoker.number(v)?.let { t -> _snapshot.update { it.copy(cabinTempC = t) } }
        }
        read(HVAC::class, hvac, "getHvacTemperature", "registerHvacTemperature", DRIVER_SEAT) { v ->
            PleosInvoker.number(v)?.let { t -> _snapshot.update { it.copy(hvacTargetC = t) } }
        }
        read(HVAC::class, hvac, "getHvacPowerState", "registerHvacPowerState") { v ->
            PleosInvoker.flag(v)?.let { on -> _snapshot.update { it.copy(hvacOn = on) } }
        }
        // 와이퍼(비 여부)
        read(Wiper::class, wiper, "getCurrentWiperState", "registerWiperState") { v ->
            PleosInvoker.flag(v)?.let { on -> _snapshot.update { it.copy(wiperActive = on) } }
        }
        // 창문
        read(Window::class, window, "getCurrentWindowPosition", "registerWindowPosition", DRIVER_SEAT) { v ->
            PleosInvoker.number(v)?.let { p -> _snapshot.update { it.copy(windowOpenPct = p.toInt()) } }
        }
    }

    private fun read(
        cls: KClass<*>,
        target: Any,
        getter: String,
        register: String,
        area: String? = null,
        onValue: (List<Any?>) -> Unit,
    ) {
        invoker.call(cls, target, getter, areaHint = area, onResult = onValue, onError = { log(getter, it) })
        invoker.call(cls, target, register, areaHint = area, onResult = onValue, onError = { log(register, it) })
    }

    private fun checkCapabilities() {
        val checks = mapOf(
            ControlItem.HVAC to Triple<KClass<*>, Any, String>(HVAC::class, hvac, "checkHvacTemperatureCapabilities"),
            ControlItem.SEAT to Triple(Seat::class, seat, "checkSeatMemoryCapabilities"),
            ControlItem.WINDOW to Triple(Window::class, window, "checkWindowPositionCapabilities"),
            ControlItem.DOOR to Triple(Door::class, door, "checkDoorPositionCapabilities"),
            ControlItem.LIGHT to Triple(Light::class, light, "checkAmbientLightSwitchCapabilities"),
        )
        checks.forEach { (item, check) ->
            val (cls, target, method) = check
            invoker.call(
                cls, target, method,
                onResult = { v ->
                    if (PleosInvoker.supported(v)) _capabilities.update { it + item }
                },
                onError = { log(method, it) },
            )
        }
    }

    // ---------- 제어 ----------

    override suspend fun apply(preset: SettingPreset, includeDoor: Boolean): List<ApplyItemResult> {
        val caps = _capabilities.value
        return SettingCompatibility.items(preset).map { (item, label) ->
            when {
                item == ControlItem.DOOR && !includeDoor -> ApplyItemResult(item, ApplyStatus.SKIPPED, "확인하지 않아 열지 않음")
                item !in caps -> ApplyItemResult(item, ApplyStatus.UNSUPPORTED)
                else -> {
                    val ok = applyItem(item, preset)
                    ApplyItemResult(item, if (ok) ApplyStatus.APPLIED else ApplyStatus.FAILED, label)
                }
            }
        }
    }

    private suspend fun applyItem(item: ControlItem, p: SettingPreset): Boolean = when (item) {
        ControlItem.HVAC -> {
            val power = set(HVAC::class, hvac, "setHvacPower", p.hvacOn)
            if (p.hvacOn) set(HVAC::class, hvac, "setHvacTemperature", p.targetTempC.toFloat(), DRIVER_SEAT) && power else power
        }
        // Seat 공개 API에는 등받이 '위치 지정'이 없어 시트 메모리 슬롯을 불러온다.
        ControlItem.SEAT -> p.seatMemorySlot?.let { set(Seat::class, seat, "selectSeatMemory", it, DRIVER_SEAT) } ?: true
        // 값 범위는 차종마다 다르다. capability 범위를 쓰도록 개선 필요(TODO).
        ControlItem.WINDOW -> set(Window::class, window, "setWindowPosition", p.windowOpenPct, DRIVER_SEAT)
        ControlItem.DOOR -> set(Door::class, door, "setDoorPosition", DOOR_OPEN_VALUE, p.openDoorArea ?: "ROW_2_RIGHT")
        ControlItem.LIGHT -> {
            val sw = set(Light::class, light, "setAmbientLightSwitch", p.ambientOn)
            if (p.ambientOn) set(Light::class, light, "setAmbientLightBrightness", p.ambientBrightness) && sw else sw
        }
    }

    override suspend fun setHvacPower(on: Boolean): Boolean = set(HVAC::class, hvac, "setHvacPower", on)

    /** set 계열 호출을 콜백이 올 때까지(최대 3초) 기다린다. */
    private suspend fun set(cls: KClass<*>, target: Any, method: String, value: Any?, area: String? = null): Boolean =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val sent = invoker.call(
                    cls, target, method, value, area,
                    onResult = { if (cont.isActive) cont.resume(true) },
                    onError = {
                        log(method, it)
                        if (cont.isActive) cont.resume(false)
                    },
                )
                if (!sent && cont.isActive) cont.resume(false)
            }
        } ?: false

    private fun log(method: String, t: Throwable) = Log.w(TAG, "$method: ${t.message}")

    companion object {
        private const val TAG = "PleosVehicle"
        private const val DRIVER_SEAT = "ROW_1_LEFT"
        private const val DOOR_OPEN_VALUE = 100
    }
}
