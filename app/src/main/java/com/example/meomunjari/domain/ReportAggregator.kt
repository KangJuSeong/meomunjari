package com.example.meomunjari.domain

import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.PowerType
import kotlin.math.roundToInt

data class ReportSummary(
    val spotId: String,
    val month: Int,
    val powerType: PowerType?,
    val sampleCount: Int,
    val energySampleCount: Int,
    val minCabinTempC: Double?,
    val avgHvacHours: Double?,
    val avgEnergyUsedPct: Double?,
    val avgDrainPerHour: Double?,
    val rainyCount: Int,
) {
    /** 3건 미만이면 '참고치'. */
    val reliable: Boolean get() = sampleCount >= ReportAggregator.MIN_RELIABLE

    val isEmpty: Boolean get() = sampleCount == 0

    /** "11월 기준 새벽 실내 최저 9°C · 공조 8시간 사용 시 배터리 14% 소모 · 기록 12회 중 비 온 날 3회" */
    fun headline(): String {
        if (isEmpty) return "${month}월 실측 기록이 아직 없어요"
        val parts = mutableListOf<String>()
        minCabinTempC?.let { parts += "${month}월 기준 새벽 실내 최저 ${it.roundToInt()}°C" }
        if (avgHvacHours != null && avgEnergyUsedPct != null && powerType != null) {
            parts += "공조 ${avgHvacHours.roundToInt()}시간 사용 시 ${powerType.energyLabel} ${avgEnergyUsedPct.roundToInt()}% 소모"
        }
        parts += "기록 ${sampleCount}회 중 비 온 날 ${rainyCount}회"
        return parts.joinToString(" · ")
    }
}

object ReportAggregator {
    const val MIN_RELIABLE = 3

    /**
     * 스팟 × 월로 집계한다. 온도·비는 동력원과 무관하게 전체를, 소모량은 같은 동력원만 쓴다.
     */
    fun summarize(
        reports: List<MeasurementReport>,
        spotId: String,
        month: Int,
        powerType: PowerType?,
    ): ReportSummary {
        val inMonth = reports.filter { it.spotId == spotId && it.month == month }
        val energy = if (powerType == null) inMonth else inMonth.filter { it.powerType == powerType }
        return ReportSummary(
            spotId = spotId,
            month = month,
            powerType = powerType,
            sampleCount = inMonth.size,
            energySampleCount = energy.size,
            minCabinTempC = inMonth.minOfOrNull { it.minCabinTempC },
            avgHvacHours = energy.takeIf { it.isNotEmpty() }?.map { it.hvacHours }?.average(),
            avgEnergyUsedPct = energy.takeIf { it.isNotEmpty() }?.map { it.energyUsedPct }?.average(),
            avgDrainPerHour = energy.takeIf { it.isNotEmpty() }?.map { it.drainPerHour }?.average(),
            rainyCount = inMonth.count { it.rained },
        )
    }

    fun monthsWithData(reports: List<MeasurementReport>, spotId: String): List<Int> =
        reports.filter { it.spotId == spotId }.map { it.month }.distinct().sorted()

    fun season(month: Int): Season = when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.AUTUMN
        else -> Season.WINTER
    }
}

enum class Season(val label: String) { SPRING("봄"), SUMMER("여름"), AUTUMN("가을"), WINTER("겨울") }
