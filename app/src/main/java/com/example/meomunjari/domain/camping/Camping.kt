package com.example.meomunjari.domain.camping

import com.example.meomunjari.core.model.BatteryComparison
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.Visit
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.domain.WakeSchedule
import java.time.LocalTime
import com.example.meomunjari.domain.MemberStatus

/** 차박 중 기록하는 샘플 1개. simTimeMs = 차박 시작 후 경과(시연 가속 반영). */
data class CampingSample(
    val simTimeMs: Long,
    val cabinTempC: Float?,
    val energyPct: Float?,
    val hvacOn: Boolean,
    val wiperActive: Boolean,
)

data class CampingState(
    val spot: Spot,
    val startedAt: LocalTime,
    val visit: Visit?,
    val preset: SettingPreset,
    val applyResults: List<ApplyItemResult>,
    val recording: Boolean,
    val samples: List<CampingSample> = emptyList(),
    val elapsedSimMs: Long = 0,
    val protectionThresholdPct: Int = 20,
    val protectionTriggered: Boolean = false,
    val notice: String? = null,
    val initialMorningPct: Double? = null,
    val plannedMinutes: Int = WakeSchedule.DEFAULT_MINUTES,
    val wakeAt: LocalTime? = null,
) {
    val elapsedHours: Double get() = elapsedSimMs / 3_600_000.0
}

data class FinishedCamping(
    val spot: Spot,
    val visit: Visit?,
    val report: MeasurementReport?,
    val creditsEarned: Int,
    val before: MemberStatus,
    val after: MemberStatus,
    val reviewSubmitted: Boolean = false,
    val batteryComparison: BatteryComparison? = null,
)

object CampingRecorder {
    /** 10분마다 기록. */
    const val SAMPLE_INTERVAL_SIM_MS = 10 * 60 * 1000L

    /** 샘플이 2개 이상이어야 리포트를 만든다. */
    fun buildReport(
        id: String,
        spot: Spot,
        samples: List<CampingSample>,
        car: CarProfile,
        month: Int,
        createdAt: String,
    ): MeasurementReport? {
        if (samples.size < 2) return null
        val temps = samples.mapNotNull { it.cabinTempC?.toDouble() }
        if (temps.isEmpty()) return null
        val first = samples.first()
        val last = samples.last()
        val durationH = (last.simTimeMs - first.simTimeMs) / 3_600_000.0
        if (durationH <= 0) return null

        val energyFirst = first.energyPct
        val energyLast = last.energyPct
        val valid = energyFirst != null && energyLast != null && energyFirst in 0f..100f &&
            energyLast in 0f..energyFirst && samples.zipWithNext().none { (a, b) ->
                a.energyPct != null && b.energyPct != null && b.energyPct > a.energyPct
            }
        val used = if (valid) (energyFirst!! - energyLast!!).toDouble() else 0.0
        val hvacHours = samples.zipWithNext().sumOf { (a, b) ->
            if (b.hvacOn) (b.simTimeMs - a.simTimeMs) / 3_600_000.0 else 0.0
        }

        return MeasurementReport(
            id = id,
            spotId = spot.id,
            month = month,
            powerType = car.powerType,
            vehicleClass = car.vehicleClass,
            minCabinTempC = temps.min().round1(),
            avgCabinTempC = temps.average().round1(),
            hvacHours = hvacHours.round1(),
            hasEnergyReading = valid,
            energyUsedPct = used.round1(),
            durationHours = durationH.round1(),
            rained = samples.any { it.wiperActive },
            createdAt = createdAt,
        )
    }

    private fun Double.round1(): Double = Math.round(this * 10) / 10.0
}
