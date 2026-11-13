package com.example.meomunjari.domain

import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.PowerType
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.abs
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
    val minOutsideC: Double? = null,
    val avgDurationHours: Double? = null,
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

    /** 같은 시기로 보는 범위: 오늘 기준 앞뒤 2주. */
    const val WINDOW_DAYS = 14

    /**
     * 스팟 × 월로 집계한다. 온도·비는 동력원과 무관하게 전체를, 소모량은 같은 동력원만 쓴다.
     */
    fun summarize(
        reports: List<MeasurementReport>,
        spotId: String,
        month: Int,
        powerType: PowerType?,
    ): ReportSummary = summarizeOf(reports.filter { it.spotId == spotId && it.month == month }, spotId, month, powerType)

    /** 오늘 기준 앞뒤 [windowDays]일 안의 리포트로 집계한다 (연도 무관). */
    fun summarizeNear(
        reports: List<MeasurementReport>,
        spotId: String,
        today: LocalDate,
        powerType: PowerType?,
        windowDays: Int = WINDOW_DAYS,
    ): ReportSummary = summarizeOf(near(reports, spotId, today, windowDays), spotId, today.monthValue, powerType)

    /**
     * 같은 시기의 밤: 방문 일자가 오늘 기준 앞뒤 [windowDays]일 안인 리포트.
     * 연도는 보지 않는다 (작년 11월 8일 리포트도 올해 11월 13일 기준 5일 차이).
     * 오늘과 날짜가 가까운 순, 같으면 최근 방문 순.
     */
    fun near(
        reports: List<MeasurementReport>,
        spotId: String,
        today: LocalDate,
        windowDays: Int = WINDOW_DAYS,
    ): List<MeasurementReport> = reports
        .filter { it.spotId == spotId }
        .mapNotNull { r -> r.visitDate?.let { r to dayGap(it, today) } }
        .filter { (_, gap) -> gap <= windowDays }
        .sortedWith(compareBy<Pair<MeasurementReport, Int>> { it.second }.thenByDescending { it.first.createdAt })
        .map { it.first }

    /** 연도를 빼고 본 두 날짜의 차이(일). 연말·연초를 넘어가도 가까운 쪽으로 잰다. */
    fun dayGap(date: LocalDate, today: LocalDate): Int {
        val md = MonthDay.from(date)
        return (-1..1).minOf { dy -> abs(ChronoUnit.DAYS.between(today, md.atYear(today.year + dy))).toInt() }
    }

    private fun summarizeOf(
        inMonth: List<MeasurementReport>,
        spotId: String,
        month: Int,
        powerType: PowerType?,
    ): ReportSummary {
        val energy = inMonth.filter { it.validEnergy && (powerType == null || it.powerType == powerType) }.distinctBy { it.id }
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
            minOutsideC = inMonth.mapNotNull { it.outsideMinC }.minOrNull(),
            avgDurationHours = inMonth.takeIf { it.isNotEmpty() }?.map { it.durationHours }?.average(),
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
