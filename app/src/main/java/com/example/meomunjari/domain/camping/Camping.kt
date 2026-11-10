package com.example.meomunjari.domain.camping

import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.Visit
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.domain.MemberStatus
import kotlin.math.max

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
    val visit: Visit?,
    val preset: SettingPreset,
    val applyResults: List<ApplyItemResult>,
    val recording: Boolean,
    val samples: List<CampingSample> = emptyList(),
    val elapsedSimMs: Long = 0,
    val protectionThresholdPct: Int = 20,
    val protectionTriggered: Boolean = false,
    val notice: String? = null,
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

        val energyFirst = samples.firstNotNullOfOrNull { it.energyPct }
        val energyLast = samples.lastOrNull { it.energyPct != null }?.energyPct
        val used = if (energyFirst != null && energyLast != null) max(0f, energyFirst - energyLast).toDouble() else 0.0
        val hvacSamples = samples.drop(1).count { it.hvacOn }
        val hvacHours = hvacSamples * SAMPLE_INTERVAL_SIM_MS / 3_600_000.0

        return MeasurementReport(
            id = id,
            spotId = spot.id,
            month = month,
            powerType = car.powerType,
            vehicleClass = car.vehicleClass,
            minCabinTempC = temps.min().round1(),
            avgCabinTempC = temps.average().round1(),
            hvacHours = hvacHours.round1(),
            energyUsedPct = used.round1(),
            durationHours = durationH.round1(),
            rained = samples.any { it.wiperActive },
            createdAt = createdAt,
        )
    }

    private fun Double.round1(): Double = Math.round(this * 10) / 10.0
}
