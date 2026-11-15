package com.example.meomunjari.sim

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.LocationPreset
import com.example.meomunjari.core.RegistrationPlace
import com.example.meomunjari.core.VirtualConsole
import com.example.meomunjari.core.location.LocationGateway
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.navi.NaviDestination
import com.example.meomunjari.core.navi.NaviGateway
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.core.vehicle.VehicleGateway
import com.example.meomunjari.core.vehicle.VehicleSnapshot
import com.example.meomunjari.domain.DriveGuard
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.WindowPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max

/**
 * mock 플레이버용 가상 차량·위치·내비. 1초마다 물리 상태를 갱신하고,
 * 가상 차량 콘솔(VirtualConsole)로 값을 바꿀 수 있다.
 */
class SimulatedWorld(
    private val scope: CoroutineScope,
    private val timeScale: StateFlow<Int>,
    private val spotsProvider: () -> List<Spot>,
    private val batteryKWh: Double,
) : VehicleGateway, LocationGateway, NaviGateway, VirtualConsole {

    private val _snapshot = MutableStateFlow(
        VehicleSnapshot(
            connected = true,
            gear = Gear.PARK,
            speedKmh = 0f,
            batteryPct = 64f,
            efficiencyKmPerKWh = 5.1f,
            cabinTempC = 15f,
            outsideTempC = 7f,
            hvacOn = false,
            hvacPowerKnown = true,
            hvacTargetC = 21f,
        ),
    )
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _capabilities = MutableStateFlow(ControlItem.entries.toSet())
    override val capabilities: StateFlow<Set<ControlItem>> = _capabilities.asStateFlow()
    override val seatMemorySlots: StateFlow<IntRange?> = MutableStateFlow<IntRange?>(1..3).asStateFlow()

    private val _location = MutableStateFlow<LatLng?>(HOME)
    override val location: StateFlow<LatLng?> = _location.asStateFlow()

    private val _destination = MutableStateFlow<NaviDestination?>(null)
    override val destination: StateFlow<NaviDestination?> = _destination.asStateFlow()

    private val _outside = MutableStateFlow(7f)
    override val outsideTempC: StateFlow<Float> = _outside.asStateFlow()

    private val _autoDrive = MutableStateFlow(false)
    override val autoDriving: StateFlow<Boolean> = _autoDrive.asStateFlow()

    private var job: Job? = null
    private var driveStepM = 0.0

    /** 시뮬레이션이 흐른 시간(분). 밤사이 외기 온도 변화를 만든다. */
    private var simElapsedMin = 0.0

    /**
     * 콘솔에서 정한 외기 온도를 저녁 기온으로 보고, 밤이 깊을수록 최대 4°C 내려간다(12시간 주기 최저).
     */
    private fun outsideNow(): Float =
        (_outside.value - 4.0 * (1 - cos(2 * PI * simElapsedMin / 1_440.0)) / 2).toFloat()

    override fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(TICK_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** realMs 동안의 변화. timeScale만큼 시간이 빨리 흐른다. */
    internal fun tick(realMs: Long) {
        val simMinutes = realMs / 60_000.0 * timeScale.value.coerceAtLeast(1)
        simElapsedMin += simMinutes
        val outsideC = outsideNow()
        if (_autoDrive.value) driveTowardDestination()

        _snapshot.update { s ->
            var battery = s.batteryPct ?: 0f
            if (s.hvacOn) {
                val outside = outsideC
                val target = s.hvacTargetC ?: 21f
                // 외기와 목표 온도 차이가 클수록 많이 쓴다. 7°C → 21°C 에서 약 1.9%/h.
                val drainPerHour = 0.8 + 0.08 * abs(target - outside)
                battery -= (drainPerHour * simMinutes / 60.0).toFloat()
            }
            val cabin = s.cabinTempC ?: outsideC
            val goal = if (s.hvacOn) s.hvacTargetC ?: cabin else outsideC + if (s.windowLevelByArea.values.any { it > 0 }) 0f else 3f
            val tau = if (s.hvacOn) 15.0 else 90.0
            val k = 1 - exp(-simMinutes / tau)
            s.copy(
                batteryPct = battery.coerceIn(0f, 100f),
                cabinTempC = (cabin + (goal - cabin) * k).toFloat(),
                outsideTempC = outsideC,
            )
        }
    }

    private fun driveTowardDestination() {
        val dest = _destination.value ?: run { _autoDrive.value = false; return }
        val here = _location.value ?: return
        val arrivalPoint = Geo.offsetNorth(dest.location, 40.0)
        val remaining = Geo.distanceM(here, arrivalPoint)
        if (driveStepM <= 0) driveStepM = max(remaining / 12.0, 200.0)
        val step = minOf(driveStepM, remaining)
        val arrived = step >= remaining
        val usedPct = step * Geo.ROAD_FACTOR / 1000.0 / (_snapshot.value.efficiencyKmPerKWh ?: 5f) / batteryKWh * 100
        _location.value = if (arrived) arrivalPoint else Geo.interpolate(here, arrivalPoint, step / remaining)
        _snapshot.update {
            it.copy(
                gear = Gear.DRIVE,
                speedKmh = if (arrived) 0f else 62f,
                batteryPct = ((it.batteryPct ?: 0f) - usedPct).toFloat().coerceIn(0f, 100f),
            )
        }
        if (arrived) {
            driveStepM = 0.0
            _autoDrive.value = false
        }
    }

    // ---------- VehicleGateway ----------

    override suspend fun apply(preset: SettingPreset, includeDoor: Boolean): List<ApplyItemResult> {
        val caps = _capabilities.value
        val results = mutableListOf<ApplyItemResult>()
        for ((item, label) in SettingCompatibility.items(preset)) {
            if (item == ControlItem.DOOR && !includeDoor) {
                results += ApplyItemResult(item, ApplyStatus.SKIPPED, "확인하지 않아 열지 않음")
                continue
            }
            if (item !in caps) {
                results += ApplyItemResult(item, ApplyStatus.UNSUPPORTED)
                continue
            }
            delay(250)
            val applied = updateParkedVehicle { s ->
                when (item) {
                    ControlItem.HVAC -> s.copy(hvacOn = preset.hvacOn, hvacTargetC = preset.targetTempC.toFloat())
                    ControlItem.SEAT -> s.copy(
                        seatMemorySlot = preset.seatMemorySlot,
                        seatBackrestPct = s.seatBackrestPct + preset.backrestPctBySeat,
                    )
                    ControlItem.WINDOW -> s.copy(windowLevelByArea = WindowPosition.entries.associate { it.area to (preset.windowLevelByArea[it.area] ?: 0) })
                    ControlItem.DOOR -> s.copy(doorOpen = true)
                    ControlItem.LIGHT -> s.copy(ambientOn = preset.ambientOn, ambientBrightness = preset.ambientBrightness)
                }
            }
            results += if (applied) ApplyItemResult(item, ApplyStatus.APPLIED, label)
            else ApplyItemResult(item, ApplyStatus.SKIPPED, "주차 상태를 확인할 수 없음")
        }
        return results
    }

    override suspend fun applySeat(preset: SettingPreset): ApplyItemResult {
        val label = SettingCompatibility.items(preset).firstOrNull { it.first == ControlItem.SEAT }?.second.orEmpty()
        if (ControlItem.SEAT !in _capabilities.value) return ApplyItemResult(ControlItem.SEAT, ApplyStatus.UNSUPPORTED)
        delay(250)
        val applied = updateParkedVehicle {
            it.copy(seatMemorySlot = preset.seatMemorySlot, seatBackrestPct = it.seatBackrestPct + preset.backrestPctBySeat)
        }
        return if (applied) ApplyItemResult(ControlItem.SEAT, ApplyStatus.APPLIED, label)
        else ApplyItemResult(ControlItem.SEAT, ApplyStatus.SKIPPED, "주차 상태를 확인할 수 없음")
    }

    override suspend fun setHvacPower(on: Boolean): Boolean {
        if (ControlItem.HVAC !in _capabilities.value) return false
        return updateParkedVehicle { it.copy(hvacOn = on) }
    }

    private fun updateParkedVehicle(transform: (VehicleSnapshot) -> VehicleSnapshot): Boolean {
        while (true) {
            val current = _snapshot.value
            if (!DriveGuard.canControl(current)) return false
            if (_snapshot.compareAndSet(current, transform(current))) return true
        }
    }

    // ---------- NaviGateway ----------

    override suspend fun requestRoute(destination: NaviDestination): Boolean {
        _destination.value = destination
        driveStepM = 0.0
        return true
    }

    override fun cancelRoute() {
        _destination.value = null
        _autoDrive.value = false
    }

    // ---------- VirtualConsole ----------

    override fun locationPresets(): List<LocationPreset> = buildList {
        add(LocationPreset("속초 시내 (출발지)", HOME, RegistrationPlace("속초 출발지", "강원도")))
        spotsProvider().forEach { add(LocationPreset("${it.name} 입구", Geo.offsetNorth(it.location, 60.0))) }
        add(LocationPreset("고성 봉포 해변 공터 (미등록)", LatLng(38.2482, 128.5668), RegistrationPlace("고성 봉포 공터", "강원도")))
        add(LocationPreset("경기 광주 상수원보호구역", LatLng(37.48634563, 127.31812732), RegistrationPlace("광주 보호구역", "경기도")))
    }

    override fun setLocation(location: LatLng) {
        _location.value = location
    }

    override fun setGear(gear: Gear) {
        _snapshot.update { it.copy(gear = gear, speedKmh = if (gear == Gear.PARK) 0f else it.speedKmh) }
    }

    override fun setSpeed(kmh: Float) = _snapshot.update { it.copy(speedKmh = kmh) }
    override fun setBattery(pct: Float) = _snapshot.update { it.copy(batteryPct = pct) }
    override fun setCabinTemp(celsius: Float) = _snapshot.update { it.copy(cabinTempC = celsius) }
    override fun setOutsideTemp(celsius: Float) {
        _outside.value = celsius
        simElapsedMin = 0.0
        _snapshot.update { it.copy(outsideTempC = celsius) }
    }

    override fun setWiper(active: Boolean) = _snapshot.update { it.copy(wiperActive = active) }

    override fun setAutoDrive(enabled: Boolean) {
        if (enabled && _destination.value == null) return
        if (enabled) driveStepM = 0.0
        _autoDrive.value = enabled
        if (!enabled) _snapshot.update { it.copy(speedKmh = 0f) }
    }

    override fun setCapability(item: ControlItem, supported: Boolean) = _capabilities.update {
        if (supported) it + item else it - item
    }

    companion object {
        const val TICK_MS = 1_000L
        val HOME = LatLng(38.2070, 128.5918)
    }
}
