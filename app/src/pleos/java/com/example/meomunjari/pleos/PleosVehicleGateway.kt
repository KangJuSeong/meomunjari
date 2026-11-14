package com.example.meomunjari.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.constant.control.MoveControl
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
import com.example.meomunjari.core.vehicle.VehicleRange
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.SeatPosition
import com.example.meomunjari.domain.WindowPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
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

    private val _seatMemorySlots = MutableStateFlow<IntRange?>(null)
    override val seatMemorySlots: StateFlow<IntRange?> = _seatMemorySlots.asStateFlow()

    /** capability 확인에서 받은 영역별 값 범위. 키는 check 메서드 이름 → 영역 이름 → 범위. */
    private val bounds = java.util.concurrent.ConcurrentHashMap<String, Map<String, IntRange>>()

    private fun boundsOf(check: String, area: String): IntRange? = bounds[check]?.get(area)

    /** capability 확인에서 받은, 그 차가 실제로 받는 영역 객체. 키는 check 메서드 이름. */
    private val areaOf = java.util.concurrent.ConcurrentHashMap<String, Any>()

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
            PleosLog.info("→ Vehicle.initialize()")
            vehicle.initialize()
            initialized = true
            PleosLog.info("← Vehicle.initialize() 완료")
        } catch (e: Exception) {
            PleosLog.warn("✗ Vehicle.initialize(): ${e.javaClass.simpleName}: ${e.message}", e)
            Log.e(TAG, "Vehicle SDK initialize failed", e)
            return
        }
        _snapshot.update { it.copy(connected = true) }
        checkCapabilities()
        subscribe()
        car.connect()
        pollJob = scope.launch {
            while (isActive) {
                _snapshot.update { it.copy(gear = car.gear(), speedKmh = car.speedKmh(), outsideTempC = car.outsideTempC()) }
                delay(1_000)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        _snapshot.value = VehicleSnapshot()
        car.release()
        if (initialized) {
            PleosLog.info("→ Vehicle.release()")
            runCatching { vehicle.release() }.onFailure { PleosLog.warn("✗ Vehicle.release(): ${it.message}", it) }
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
        // 목표 온도(HVAC_TEMPERATURE_SET)는 공조가 꺼져 있으면 읽을 수 없다(PropertyNotAvailable).
        // 그래서 변경 구독만 걸어 두고, 한 번 읽기는 공조가 켜진 걸 확인했을 때 한다(readHvacTargetIfOn).
        invoker.call(HVAC::class, hvac, "registerHvacTemperature", areaHint = DRIVER_SEAT,
            onResult = ::onHvacTarget, onError = { log("registerHvacTemperature", it) })
        // 공조 전원 상태는 차가 받는 전원 영역을 capability로 확인한 뒤 구독한다(checkCapabilities).
        // 와이퍼(비 여부)
        read(Wiper::class, wiper, "getCurrentWiperState", "registerWiperState") { v ->
            PleosInvoker.flag(v)?.let { on -> _snapshot.update { it.copy(wiperActive = on) } }
        }
        // 창문 4개: 차 위치 → 여는 단계(0~10)
        WindowPosition.entries.forEach { w ->
            read(Window::class, window, "getCurrentWindowPosition", "registerWindowPosition", w.area) { v ->
                PleosInvoker.number(v)?.let { p ->
                    val range = boundsOf(CHECK_WINDOW, w.area) ?: 0..WindowPosition.MAX_LEVEL
                    val level = VehicleRange.toPercent(p.toInt(), range) * WindowPosition.MAX_LEVEL / 100
                    _snapshot.update { it.copy(windowLevelByArea = it.windowLevelByArea + (w.area to level)) }
                }
            }
        }
    }

    private fun read(
        cls: KClass<*>,
        target: Any,
        getter: String,
        register: String,
        area: String? = null,
        areaObject: Any? = null,
        onValue: (List<Any?>) -> Unit,
    ) {
        invoker.call(cls, target, getter, areaHint = area, areaObject = areaObject, onResult = onValue, onError = { log(getter, it) })
        invoker.call(cls, target, register, areaHint = area, areaObject = areaObject, onResult = onValue, onError = { log(register, it) })
    }

    /** 공조 목표 온도 값. 꺼져 있을 때 들어오는 0.0 같은, 설정 가능한 범위 밖 값은 버린다. */
    private fun onHvacTarget(values: List<Any?>) {
        PleosInvoker.number(values)?.takeIf { it in 10f..35f }?.let { t -> _snapshot.update { it.copy(hvacTargetC = t) } }
    }

    /** 공조가 켜져 있을 때만 목표 온도를 한 번 읽는다. */
    private fun readHvacTargetIfOn(on: Boolean) {
        if (!on) return
        invoker.call(HVAC::class, hvac, "getHvacTemperature", areaHint = DRIVER_SEAT,
            onResult = ::onHvacTarget, onError = { log("getHvacTemperature", it) })
    }

    private fun checkCapabilities() {
        val checks = mapOf(
            ControlItem.HVAC to Triple<KClass<*>, Any, String>(HVAC::class, hvac, "checkHvacTemperatureCapabilities"),
            ControlItem.SEAT to Triple(Seat::class, seat, CHECK_SEAT_MEMORY),
            ControlItem.WINDOW to Triple(Window::class, window, CHECK_WINDOW),
            ControlItem.DOOR to Triple(Door::class, door, "checkDoorPositionCapabilities"),
            ControlItem.LIGHT to Triple(Light::class, light, CHECK_LIGHT_SWITCH),
        )
        checks.forEach { (item, check) ->
            val (cls, target, method) = check
            invoker.call(
                cls, target, method,
                onResult = { v ->
                    if (PleosInvoker.supported(v)) _capabilities.update { it + item }
                    val b = PleosInvoker.intBounds(v)
                    bounds[method] = b
                    PleosInvoker.firstArea(v)?.let { areaOf[method] = it }
                    if (method == CHECK_SEAT_MEMORY) _seatMemorySlots.value = b[DRIVER_SEAT]
                },
                onError = { log(method, it) },
            )
        }
        // 공조 전원은 좌석 하나가 아니라 여러 좌석을 묶은 영역으로만 받는 차가 있다(에뮬레이터: ROW_1_LEFT 거부).
        invoker.call(
            HVAC::class, hvac, CHECK_HVAC_POWER,
            onResult = { v ->
                val area = PleosInvoker.firstArea(v)
                area?.let { areaOf[CHECK_HVAC_POWER] = it }
                PleosLog.info("공조 전원 영역 = ${PleosLog.fmt(area)}")
                read(HVAC::class, hvac, "getHvacPowerState", "registerHvacPowerState", areaObject = area) { s ->
                    PleosInvoker.flag(s)?.let { on ->
                        _snapshot.update { it.copy(hvacOn = on, hvacPowerKnown = true) }
                        readHvacTargetIfOn(on)
                    }
                }
            },
            onError = { log(CHECK_HVAC_POWER, it) },
        )
        invoker.call(
            Light::class, light, CHECK_LIGHT_BRIGHTNESS,
            onResult = { v -> PleosInvoker.firstArea(v)?.let { areaOf[CHECK_LIGHT_BRIGHTNESS] = it } },
            onError = { log(CHECK_LIGHT_BRIGHTNESS, it) },
        )
        // 등받이 각도 조절만 지원하는 차도 시트 항목을 쓸 수 있게 한다.
        invoker.call(
            Seat::class, seat, CHECK_BACKREST,
            onResult = { v ->
                if (PleosInvoker.supported(v)) _capabilities.update { it + ControlItem.SEAT }
                bounds[CHECK_BACKREST] = PleosInvoker.intBounds(v)
            },
            onError = { log("checkSeatBackrestAnglePositionCapabilities", it) },
        )
    }

    // ---------- 제어 ----------

    override suspend fun apply(preset: SettingPreset, includeDoor: Boolean): List<ApplyItemResult> {
        val caps = _capabilities.value
        return SettingCompatibility.items(preset).map { (item, label) ->
            when {
                item == ControlItem.DOOR && !includeDoor -> ApplyItemResult(item, ApplyStatus.SKIPPED, "확인하지 않아 열지 않음")
                item !in caps -> ApplyItemResult(item, ApplyStatus.UNSUPPORTED)
                item == ControlItem.SEAT -> applySeatItem(preset)
                else -> {
                    val ok = applyItem(item, preset)
                    ApplyItemResult(item, if (ok) ApplyStatus.APPLIED else ApplyStatus.FAILED, label)
                }
            }
        }
    }

    private suspend fun applyItem(item: ControlItem, p: SettingPreset): Boolean = when (item) {
        ControlItem.HVAC -> {
            val power = setHvacPowerOnCar(p.hvacOn)
            if (p.hvacOn) {
                // 에뮬레이터는 목표 온도가 바뀌어도 구독 이벤트를 늦게 주거나 안 줘서, 완료 콜백 값을 바로 반영한다.
                val temp = setForValues(HVAC::class, hvac, "setHvacTemperature", p.targetTempC.toFloat(), DRIVER_SEAT)
                temp?.let { v -> _snapshot.update { it.copy(hvacTargetC = PleosInvoker.number(v) ?: p.targetTempC.toFloat()) } }
                temp != null && power
            } else power
        }
        // 시트: 메모리 슬롯을 불러온 뒤, 좌석별 등받이 각도를 맞춘다.
        ControlItem.SEAT -> applySeatItem(p).status == ApplyStatus.APPLIED
        // 창문 4개를 각각 맞춘다. 앱은 0~10단계로 저장하고, 차마다 다른 위치 범위로 바꿔 보낸다.
        ControlItem.WINDOW -> {
            val known = bounds[CHECK_WINDOW]
            WindowPosition.entries.map { w ->
                val level = (p.windowLevelByArea[w.area] ?: 0).coerceIn(0, WindowPosition.MAX_LEVEL)
                if (known != null && w.area !in known) {
                    PleosLog.info("${w.label} 창문: 이 차는 이 창문 조절을 지원하지 않아 건너뜀")
                    return@map true
                }
                val range = known?.get(w.area) ?: 0..WindowPosition.MAX_LEVEL
                val position = VehicleRange.fromPercent(level * 100 / WindowPosition.MAX_LEVEL, range, roundUp = true)
                PleosLog.info("${w.label} 창문 ${level}단 → 차 위치 $position (범위 ${range.first}~${range.last})")
                set(Window::class, window, "setWindowPosition", position, w.area)
            }.all { it }
        }
        ControlItem.DOOR -> set(Door::class, door, "setDoorPosition", DOOR_OPEN_VALUE, p.openDoorArea ?: "ROW_2_RIGHT")
        ControlItem.LIGHT -> {
            val switchArea = areaOf[CHECK_LIGHT_SWITCH]
            val sw = set(Light::class, light, "setAmbientLightSwitch", p.ambientOn, areaObject = switchArea)
            if (p.ambientOn) {
                set(Light::class, light, "setAmbientLightBrightness", p.ambientBrightness,
                    areaObject = areaOf[CHECK_LIGHT_BRIGHTNESS] ?: switchArea) && sw
            } else sw
        }
    }

    override suspend fun applySeat(preset: SettingPreset): ApplyItemResult {
        val label = SettingCompatibility.items(preset).firstOrNull { it.first == ControlItem.SEAT }?.second.orEmpty()
        if (ControlItem.SEAT !in _capabilities.value) return ApplyItemResult(ControlItem.SEAT, ApplyStatus.UNSUPPORTED)
        return applySeatItem(preset)
    }

    /**
     * 시트 = 메모리 불러오기 + 좌석별 등받이. 부분마다 결과를 모아, 일부만 되면 PARTIAL로 무엇이 안 됐는지 알려준다.
     */
    private suspend fun applySeatItem(p: SettingPreset): ApplyItemResult {
        val done = mutableListOf<String>()
        val notDone = mutableListOf<String>()
        p.seatMemorySlot?.let { slot ->
            val range = _seatMemorySlots.value
            when {
                range != null && slot !in range -> {
                    PleosLog.warn("시트 메모리 ${slot}번은 이 차에서 지원하지 않아 건너뜀 (지원 ${range.first}~${range.last}번)")
                    notDone += "메모리 ${slot}번: 이 차에 없는 번호"
                }
                set(Seat::class, seat, "selectSeatMemory", slot, DRIVER_SEAT) -> done += "메모리 ${slot}번"
                else -> notDone += "메모리 ${slot}번: 전송 실패"
            }
        }
        SeatPosition.entries.forEach { pos ->
            val pct = p.backrestPctBySeat[pos.area] ?: return@forEach
            when (val outcome = moveBackrest(pos.area, pct)) {
                BackrestOutcome.REACHED -> done += "${pos.label} 등받이 $pct%"
                else -> notDone += "${pos.label} 등받이: ${outcome.reason}"
            }
        }
        val status = when {
            notDone.isEmpty() -> ApplyStatus.APPLIED
            done.isEmpty() -> ApplyStatus.FAILED
            else -> ApplyStatus.PARTIAL
        }
        val detail = (done + notDone).joinToString(" · ")
        PleosLog.info("시트 적용 결과: ${status.label} · $detail")
        return ApplyItemResult(ControlItem.SEAT, status, detail)
    }

    private enum class BackrestOutcome(val reason: String) {
        REACHED("도달"),
        STALLED("차가 움직이지 않음"),
        TIMEOUT("시간 안에 목표에 닿지 못함"),
        UNSUPPORTED("이 좌석은 조절 미지원"),
        FAILED("위치를 읽거나 움직이지 못함"),
    }

    override suspend fun setHvacPower(on: Boolean): Boolean = setHvacPowerOnCar(on)

    /**
     * 공조 전원을 켜고 끈다. 에뮬레이터는 전원이 바뀌어도 registerHvacPowerState 이벤트를 보내지 않아,
     * 완료 콜백이 돌려준 값을 확정된 전원 상태로 반영한다.
     */
    private suspend fun setHvacPowerOnCar(on: Boolean): Boolean {
        val values = setForValues(HVAC::class, hvac, "setHvacPower", on, areaObject = areaOf[CHECK_HVAC_POWER])
            ?: return false
        val confirmed = PleosInvoker.flag(values) ?: on
        _snapshot.update { it.copy(hvacOn = confirmed, hvacPowerKnown = true) }
        readHvacTargetIfOn(confirmed)
        return confirmed == on
    }

    /**
     * 등받이를 목표 눕힘 정도(0~100%)까지 움직인다. 차가 알려준 그 좌석의 위치 범위로 바꿔서 맞춘다.
     * Seat 공개 API에는 위치를 '지정'하는 함수가 없고 현재 위치 조회([getCurrentSeatBackrestAnglePosition])와
     * 앞/뒤/정지 이동([moveSeatBackrestAngle])만 있어, 현재 위치를 읽으며 목표에 닿을 때까지 움직이고 멈춘다.
     * FORWARD가 값을 키우는 쪽인지는 문서에 없어, 첫 이동 뒤 오차가 커지면 방향을 뒤집는다.
     */
    private suspend fun moveBackrest(area: String, targetPct: Int): BackrestOutcome {
        val seatName = seatLabel(area)
        val range = boundsOf(CHECK_BACKREST, area)
        if (bounds[CHECK_BACKREST] != null && range == null) {
            Log.w(SEAT_TAG, "$seatName 등받이: 이 차는 이 좌석의 등받이 조절을 지원하지 않아 건너뜀")
            return BackrestOutcome.UNSUPPORTED
        }
        val r = range ?: 0..100
        val target = VehicleRange.fromPercent(targetPct, r)
        val startedAt = System.currentTimeMillis()
        val startPos = readBackrest(area)
        if (startPos == null) {
            Log.w(SEAT_TAG, "$seatName 등받이: 현재 위치를 읽지 못해 움직이지 않음 (목표 $targetPct% = $target)")
            return BackrestOutcome.FAILED
        }
        var current: Int = startPos
        Log.i(SEAT_TAG, "$seatName 등받이: 현재 $current → 목표 $target ($targetPct%, 차 범위 ${r.first}~${r.last}, 허용 ±$BACKREST_TOLERANCE)")
        if (abs(target - current) <= BACKREST_TOLERANCE) {
            Log.i(SEAT_TAG, "$seatName 등받이: 이미 목표 범위 안이라 움직이지 않음")
            return BackrestOutcome.REACHED
        }
        var sign = 1
        var firstMove = true
        var step = 0
        var stalled = false
        var triedReverse = false
        var lastChangeAt = System.currentTimeMillis()
        val reached = withTimeoutOrNull(BACKREST_TIMEOUT_MS) {
            while (abs(target - current) > BACKREST_TOLERANCE) {
                val before = abs(target - current)
                val forward = (target > current) == (sign > 0)
                val command = if (forward) MoveControl.FORWARD else MoveControl.BACKWARD
                if (!set(Seat::class, seat, "moveSeatBackrestAngle", command, area)) {
                    Log.w(SEAT_TAG, "$seatName 등받이: moveSeatBackrestAngle($command) 실패")
                    return@withTimeoutOrNull false
                }
                delay(BACKREST_POLL_MS)
                val read = readBackrest(area)
                if (read == null) {
                    Log.w(SEAT_TAG, "$seatName 등받이: 이동 중 현재 위치를 읽지 못함")
                    return@withTimeoutOrNull false
                }
                step++
                Log.d(SEAT_TAG, "$seatName 등받이: #$step $command $current → $read (남은 ${target - read})")
                if (read != current) lastChangeAt = System.currentTimeMillis()
                current = read
                if (System.currentTimeMillis() - lastChangeAt >= BACKREST_STALL_MS) {
                    if (!triedReverse) {
                        triedReverse = true
                        sign = -sign
                        lastChangeAt = System.currentTimeMillis()
                        Log.i(SEAT_TAG, "$seatName 등받이: ${BACKREST_STALL_MS / 1000}초 동안 위치가 그대로라 반대 방향으로 시도")
                        continue
                    }
                    stalled = true
                    Log.w(SEAT_TAG, "$seatName 등받이: 양쪽 방향 모두 위치가 바뀌지 않음 → 차가 움직이지 않는 것으로 보고 멈춤")
                    return@withTimeoutOrNull false
                }
                if (firstMove) {
                    firstMove = false
                    if (abs(target - current) > before) {
                        sign = -sign
                        Log.i(SEAT_TAG, "$seatName 등받이: $command 로 목표에서 멀어져 방향을 반대로 바꿈")
                    }
                }
            }
            true
        } ?: false
        // 닿았든 시간이 다 됐든 모터는 반드시 멈춘다.
        set(Seat::class, seat, "moveSeatBackrestAngle", MoveControl.STOP, area)
        val endPos = readBackrest(area) ?: current
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        val outcome = when {
            reached -> "도달"
            stalled -> "움직임 없음"
            seconds * 1000 >= BACKREST_TIMEOUT_MS -> "시간 초과"
            else -> "중단"
        }
        Log.i(SEAT_TAG, "$seatName 등받이: $outcome · $startPos → $endPos (목표 $target = $targetPct%, ${step}회 이동, ${seconds}초)")
        return when {
            reached -> BackrestOutcome.REACHED
            stalled -> BackrestOutcome.STALLED
            seconds * 1000 >= BACKREST_TIMEOUT_MS -> BackrestOutcome.TIMEOUT
            else -> BackrestOutcome.FAILED
        }
    }

    private fun seatLabel(area: String): String =
        SeatPosition.entries.firstOrNull { it.area == area }?.label ?: area

    private suspend fun readBackrest(area: String): Int? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val sent = invoker.call(
                    Seat::class, seat, "getCurrentSeatBackrestAnglePosition", areaHint = area,
                    onResult = { if (cont.isActive) cont.resume(PleosInvoker.number(it)?.toInt()) },
                    onError = {
                        log("getCurrentSeatBackrestAnglePosition", it)
                        if (cont.isActive) cont.resume(null)
                    },
                )
                if (!sent && cont.isActive) cont.resume(null)
            }
        }

    /** set 계열 호출을 콜백이 올 때까지(최대 3초) 기다린다. */
    private suspend fun set(
        cls: KClass<*>,
        target: Any,
        method: String,
        value: Any?,
        area: String? = null,
        areaObject: Any? = null,
    ): Boolean = setForValues(cls, target, method, value, area, areaObject) != null

    /** [set]과 같되 완료 콜백 값을 돌려준다. 실패·시간 초과면 null. */
    private suspend fun setForValues(
        cls: KClass<*>,
        target: Any,
        method: String,
        value: Any?,
        area: String? = null,
        areaObject: Any? = null,
    ): List<Any?>? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<List<Any?>?> { cont ->
                val sent = invoker.call(
                    cls, target, method, value, area,
                    areaObject = areaObject,
                    onResult = { if (cont.isActive) cont.resume(it) },
                    onError = {
                        log(method, it)
                        if (cont.isActive) cont.resume(null)
                    },
                )
                if (!sent && cont.isActive) cont.resume(null)
            }
        }

    private fun log(method: String, t: Throwable) {
        // 미지원·일시 불가는 정상 상황이라 경고로 남기지 않는다(앱은 대체 값을 쓴다).
        if (PleosLog.isUnsupported(t)) Log.i(TAG, "$method: 미지원/읽을 수 없음 → 대체 값 사용") else Log.w(TAG, "$method: ${t.message}")
    }

    companion object {
        private const val TAG = "PleosVehicle"
        /** 좌석 등받이 이동 로그. `adb logcat -s MeomunjariSeat` 로 볼 수 있다. */
        private const val SEAT_TAG = "MeomunjariSeat"
        private const val DRIVER_SEAT = "ROW_1_LEFT"
        private const val DOOR_OPEN_VALUE = 100
        private const val CHECK_SEAT_MEMORY = "checkSeatMemoryCapabilities"
        private const val CHECK_HVAC_POWER = "checkHvacPowerCapabilities"
        private const val CHECK_LIGHT_SWITCH = "checkAmbientLightSwitchCapabilities"
        private const val CHECK_LIGHT_BRIGHTNESS = "checkAmbientLightBrightnessCapabilities"
        /** 이동 명령을 보내도 위치가 이만큼 그대로면 '안 움직임'으로 본다. */
        private const val BACKREST_STALL_MS = 2_000L
        private const val CHECK_WINDOW = "checkWindowPositionCapabilities"
        private const val CHECK_BACKREST = "checkSeatBackrestAnglePositionCapabilities"
        private const val BACKREST_TOLERANCE = 2
        private const val BACKREST_POLL_MS = 300L
        private const val BACKREST_TIMEOUT_MS = 30_000L
    }
}
