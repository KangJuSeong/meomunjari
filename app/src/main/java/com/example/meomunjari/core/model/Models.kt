package com.example.meomunjari.core.model

import kotlinx.serialization.Serializable

/** 차급. rank가 클수록 큰 차. 스팟의 maxVehicleClass 이하 차만 진입 가능. */
@Serializable
enum class VehicleClass(val label: String, val rank: Int) {
    COMPACT("경차", 0),
    SMALL("소형 SUV", 1),
    MID("중형 SUV", 2),
    LARGE("대형 SUV", 3),
    VAN("밴·캠핑카", 4),
}

@Serializable
enum class PowerType(val label: String, val energyLabel: String) {
    EV("전기차", "배터리"),
    ICE("내연기관", "연료"),
}

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Amenities(
    val toilet: Boolean = false,
    val convenienceStoreM: Int? = null,
    val ground: String = "",
    val noise: String = "",
    val water: Boolean = false,
)

@Serializable
data class TagCount(val tag: String, val count: Int)

data class RestrictedPlaceReport(
    val spotId: String,
    val reporter: String,
    val note: String,
    val createdAt: String,
)

@Serializable
data class Spot(
    val id: String,
    val name: String,
    val region: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 1000,
    val description: String = "",
    val amenities: Amenities = Amenities(),
    val tags: List<TagCount> = emptyList(),
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val visitCount: Int = 0,
    val lastVisitedAt: String? = null,
    val createdAt: String = "",
    val registeredBy: String = "",
    val maxVehicleClass: VehicleClass = VehicleClass.VAN,
    val visitedVehicleClasses: Map<VehicleClass, Int> = emptyMap(),
    val liveCampers: Int = 0,
    val typicalWeekendCampers: String = "",
    val photoColors: List<String> = emptyList(),
    val overnightTemperaturesC: List<Double> = emptyList(), // 이전 목업의 도착 기준 배열
    val hourlyTemperaturesC: Map<String, Double> = emptyMap(), // 현지 정각 HH:00 → °C
) {
    val location: LatLng get() = LatLng(lat, lng)

    /** 많이 붙은 태그 순. */
    val sortedTags: List<TagCount> get() = tags.sortedByDescending { it.count }

    fun hasTag(tag: String): Boolean = tags.any { it.tag == tag }

    fun fits(vehicleClass: VehicleClass): Boolean = vehicleClass.rank <= maxVehicleClass.rank
}

@Serializable
data class Review(
    val id: String,
    val spotId: String,
    val author: String,
    val authorGrade: String = "",
    val rating: Int,
    val text: String,
    val createdAt: String,
    val tags: List<String> = emptyList(),
    val presetId: String? = null,
)

/**
 * 차가 밤새 기록한 실측 리포트 1회분.
 * 한 스팟에 사용자당 1개만 두고, 다시 방문하면 최신 기록으로 바꾼다.
 */
@Serializable
data class BatteryComparison(
    val predictedMorningPct: Double?,
    val endPct: Double?,
    val stayMinutes: Long,
    val plannedMinutes: Int = 480,
    val plannedWakeTime: String? = null,
)

@Serializable
data class MeasurementReport(
    val id: String,
    val spotId: String,
    val month: Int,
    val powerType: PowerType,
    val vehicleClass: VehicleClass,
    val minCabinTempC: Double,
    /** 실내 온도(밤새 평균). */
    val avgCabinTempC: Double? = null,
    val hvacHours: Double,
    /** 배터리(연료) 소모량 %. */
    val energyUsedPct: Double,
    /** 차박 시간. */
    val durationHours: Double,
    val rained: Boolean,
    /** 방문 일자 (yyyy-MM-dd). */
    val createdAt: String = "",
    /** 외부 최저·최고 온도. 차가 외기 온도를 주지 않으면 null. */
    val outsideMinC: Double? = null,
    val outsideMaxC: Double? = null,
    /** 사용 중이던 외부 기기 (전기장판, 캠핑 냉장고 등). */
    val devices: List<String> = emptyList(),
    val hasEnergyReading: Boolean = true,
    val batteryComparison: BatteryComparison? = null,
) {
    val validEnergy: Boolean get() = hasEnergyReading && durationHours.isFinite() && durationHours > 0 &&
        hvacHours in 0.0..durationHours && energyUsedPct in 0.0..100.0
    val measuredEnergyUsedPct: Double? get() = energyUsedPct.takeIf { validEnergy }
    val drainPerHour: Double get() = if (durationHours > 0) energyUsedPct / durationHours else 0.0

    /** 방문 일자. 형식이 맞지 않으면 null. */
    val visitDate: java.time.LocalDate? get() = runCatching { java.time.LocalDate.parse(createdAt) }.getOrNull()

    /**
     * "11월 8일 · 외부 −2~6°C · 실내 21°C · 배터리 −14% · 9시간 · 전기장판"
     * [today]와 연도가 다르면 "2025년 11월 8일"처럼 연도를 붙인다.
     */
    fun summaryLine(today: java.time.LocalDate? = null): String = listOfNotNull(
        visitDate?.let { d ->
            val year = if (today != null && d.year != today.year) "${d.year}년 " else ""
            "$year${d.monthValue}월 ${d.dayOfMonth}일"
        },
        if (outsideMinC != null && outsideMaxC != null) "외부 ${deg(outsideMinC)}~${deg(outsideMaxC)}°C" else null,
        avgCabinTempC?.let { "실내 ${deg(it)}°C" },
        energySummary(),
        "${hours(durationHours)}시간",
        devices.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(" · ")

    /** 측정값만: "외부 −2~6°C · 실내 21°C · 배터리 −14% · 9시간" */
    fun valuesLine(): String = listOfNotNull(
        if (outsideMinC != null && outsideMaxC != null) "외부 ${deg(outsideMinC)}~${deg(outsideMaxC)}°C" else null,
        avgCabinTempC?.let { "실내 ${deg(it)}°C" },
        energySummary(),
        "${hours(durationHours)}시간",
    ).joinToString(" · ")

    private fun energySummary(): String = measuredEnergyUsedPct?.let { "${powerType.energyLabel} −${Math.round(it)}%" }
        ?: "${powerType.energyLabel} 소모 측정 안 됨"

    private fun deg(c: Double): String = Math.round(c).toInt().let { if (it < 0) "−${-it}" else "$it" }

    private fun hours(h: Double): String = if (h % 1.0 == 0.0) "${h.toInt()}" else "${Math.round(h * 10) / 10.0}"
}

/** 차박 세팅. 내 세팅·고수 세팅이 모두 이 형태. */
@Serializable
data class SettingPreset(
    val id: String,
    val spotId: String? = null,
    val ownerNickname: String,
    val ownerGrade: String = "",
    val title: String = "",
    val targetTempC: Double = 21.0,
    val hvacOn: Boolean = true,
    val seatMemorySlot: Int? = null,
    /**
     * 좌석별 등받이 눕힘 정도(0 = 가장 세움, 100 = 가장 눕힘). 키는 SeatValue 이름(ROW_1_LEFT 등),
     * 없으면 그 좌석은 건드리지 않는다. 차마다 등받이 위치 범위가 달라 적용할 때 그 차의 범위로 바꾼다.
     */
    val backrestPctBySeat: Map<String, Int> = emptyMap(),
    /**
     * 창문별 여는 단계(0 = 닫힘 ~ 10 = 활짝). 키는 WindowValue 이름(ROW_1_LEFT 등), 없는 창문은 닫는다.
     * 차마다 창문 위치 범위가 달라(Pleos 에뮬레이터는 0~10) 적용할 때 그 차의 범위로 바꾼다.
     */
    val windowLevelByArea: Map<String, Int> = emptyMap(),
    /** 열 도어 위치(Vehicle SDK DoorValue 이름). null이면 도어는 건드리지 않는다. */
    val openDoorArea: String? = null,
    val ambientOn: Boolean = false,
    val ambientBrightness: Int = 30,
    val loadCount: Int = 0,
    val ownerRating: Double = 0.0,
)

@Serializable
data class CarProfile(
    val model: String,
    val vehicleClass: VehicleClass,
    val powerType: PowerType,
    val batteryKWh: Double = 77.4,
    val efficiencyKmPerKWh: Double = 5.0,
    val fuelTankL: Double = 60.0,
    val fuelKmPerL: Double = 11.0,
    /** 시험 차종·구동계·배터리·히트펌프 구성이 확인된 경우에만 지정한다. */
    val hvacPowerProfile: String? = null,
)

@Serializable
data class Visit(
    val id: String,
    val spotId: String,
    val date: String,
    val month: Int,
)

@Serializable
data class UserData(
    val nickname: String,
    val car: CarProfile,
    val mySetting: SettingPreset,
    /**
     * 시트 메모리 슬롯별로 저장한 좌석 등받이 각도. 키는 슬롯 번호(0 = 메모리 안 씀),
     * 값은 SeatValue 이름 → 각도. 슬롯을 바꾸면 그 슬롯의 각도가 mySetting으로 불려온다.
     */
    val seatSlotBackrests: Map<Int, Map<String, Int>> = emptyMap(),
    val visits: List<Visit> = emptyList(),
    val registeredSpotIds: List<String> = emptyList(),
    val reportCount: Int = 0,
    /** 기록왕 뱃지용. 아무도 기록하지 않은 스팟·계절의 첫 기록은 2로 센다. */
    val reportCredits: Int = 0,
    val reviewCount: Int = 0,
    val presetLoadsReceived: Int = 0,
    val consentToRecord: Boolean = false,
)

@Serializable
data class TagDictionary(
    val categories: Map<String, List<String>> = emptyMap(),
    /** 동의어 → 대표 태그. */
    val synonyms: Map<String, String> = emptyMap(),
    /** "1".."12" → 그 달의 추천 태그. */
    val seasonal: Map<String, List<String>> = emptyMap(),
)
