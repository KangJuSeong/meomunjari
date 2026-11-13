package com.example.meomunjari.core.vehicle

import com.example.meomunjari.core.model.SettingPreset
import kotlinx.coroutines.flow.StateFlow

enum class Gear(val label: String) {
    PARK("P"),
    REVERSE("R"),
    NEUTRAL("N"),
    DRIVE("D"),
    UNKNOWN("?"),
}

/** 앱이 쓰는 차량 상태 전부. null = 이 차에서 읽을 수 없는 값. */
data class VehicleSnapshot(
    val connected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Float? = null,
    val batteryPct: Float? = null,
    val fuelPct: Float? = null,
    val efficiencyKmPerKWh: Float? = null,
    val cabinTempC: Float? = null,
    /** 외기 온도. 차가 주지 않으면 null. */
    val outsideTempC: Float? = null,
    val hvacOn: Boolean = false,
    val hvacTargetC: Float? = null,
    val wiperActive: Boolean = false,
    val windowOpenPct: Int = 0,
    val ambientOn: Boolean = false,
    val ambientBrightness: Int = 0,
    val doorOpen: Boolean = false,
    val seatMemorySlot: Int? = null,
    /** 좌석별 현재 등받이 눕힘 정도(0~100). 키는 SeatValue 이름. */
    val seatBackrestPct: Map<String, Int> = emptyMap(),
    val hvacPowerKnown: Boolean = false,
) {
    /** EV면 배터리 %, 내연기관이면 연료 %. */
    val energyPct: Float? get() = batteryPct ?: fuelPct
}

/** 차박 세팅에서 제어하는 항목. */
enum class ControlItem(val label: String) {
    HVAC("공조 온도"),
    SEAT("시트"),
    WINDOW("창문"),
    DOOR("도어"),
    LIGHT("실내 조명"),
}

enum class ApplyStatus(val label: String) {
    APPLIED("적용됨"),
    /** 한 항목 안에서 일부만 됨 (예: 시트 메모리는 됐지만 등받이는 차가 움직이지 않음). */
    PARTIAL("일부 적용"),
    UNSUPPORTED("이 차는 미지원"),
    FAILED("실패"),
    SKIPPED("건너뜀"),
}

data class ApplyItemResult(val item: ControlItem, val status: ApplyStatus, val detail: String = "")

interface VehicleGateway {
    val snapshot: StateFlow<VehicleSnapshot>

    /** capability 체크 결과 제어 가능한 항목. */
    val capabilities: StateFlow<Set<ControlItem>>

    fun start()
    fun stop()

    /**
     * 세팅을 차에 적용한다. 지원하지 않는 항목은 건너뛰고 항목별 결과를 돌려준다.
     * 주차 여부 확인은 호출하는 쪽(TripStore)이 먼저 한다.
     */
    suspend fun apply(preset: SettingPreset, includeDoor: Boolean): List<ApplyItemResult>

    /** 이 차가 지원하는 운전석 시트 메모리 번호 범위. 아직 모르거나 지원하지 않으면 null. */
    val seatMemorySlots: StateFlow<IntRange?>

    /** 시트 항목(메모리·좌석별 등받이 눕힘)만 다시 적용한다. 차박 중 내 세팅의 시트를 바꿨을 때 쓴다. */
    suspend fun applySeat(preset: SettingPreset): ApplyItemResult

    suspend fun setHvacPower(on: Boolean): Boolean
}
