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

@Serializable
data class Spot(
    val id: String,
    val name: String,
    val region: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 300,
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
    val nearestChargerKm: Double = 10.0,
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
    val reportId: String? = null,
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
    /** 작성자 닉네임. 스팟당 1개 규칙에 쓴다. */
    val author: String = "",
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
        "${powerType.energyLabel} −${Math.round(energyUsedPct)}%",
        "${hours(durationHours)}시간",
        devices.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(" · ")

    /** 측정값만: "외부 −2~6°C · 실내 21°C · 배터리 −14% · 9시간" */
    fun valuesLine(): String = listOfNotNull(
        if (outsideMinC != null && outsideMaxC != null) "외부 ${deg(outsideMinC)}~${deg(outsideMaxC)}°C" else null,
        avgCabinTempC?.let { "실내 ${deg(it)}°C" },
        "${powerType.energyLabel} −${Math.round(energyUsedPct)}%",
        "${hours(durationHours)}시간",
    ).joinToString(" · ")

    private fun deg(c: Double): String = Math.round(c).toInt().let { if (it < 0) "−${-it}" else "$it" }

    private fun hours(h: Double): String = if (h % 1.0 == 0.0) "${h.toInt()}" else "${Math.round(h * 10) / 10.0}"
}

/** 차박 세팅. 내 세팅·계절 기본값·고수 세팅이 모두 이 형태. */
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
    val backrestAngleDeg: Int? = null,
    val windowOpenPct: Int = 0,
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
    /** 음성 검색 키워드 → 태그. */
    val speechKeywords: Map<String, String> = emptyMap(),
)

@Serializable
data class RestrictedZone(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val reason: String,
)
