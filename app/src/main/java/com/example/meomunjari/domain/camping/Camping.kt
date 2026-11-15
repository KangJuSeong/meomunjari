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
    val outsideTempC: Float? = null,
)

data class CampingState(
    val car: CarProfile,
    val spot: Spot,
    val startedAt: LocalTime,
    val visit: Visit?,
    val preset: SettingPreset,
    val applyResults: List<ApplyItemResult>,
    val recording: Boolean,
    /** 차박 시작 때 고른 외부 기기. 리포트에 그대로 담긴다. */
    val devices: List<String> = emptyList(),
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

/** 차박 시작 때 고를 수 있는 외부 기기. */
object ExternalDevices {
    val ALL = listOf("전기장판", "캠핑 냉장고", "전기포트", "노트북·충전", "LED 랜턴", "휴대용 선풍기")
}

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
        devices: List<String> = emptyList(),
    ): MeasurementReport? {
        if (samples.size < 2) return null
        val temps = samples.mapNotNull { it.cabinTempC?.toDouble() }
        if (temps.isEmpty()) return null
        val first = samples.first()
        val last = samples.last()
        val durationH = (last.simTimeMs - first.simTimeMs) / 3_600_000.0
        if (durationH <= 0) return null

        val used = energyUsed(samples)
        val hvacHours = samples.zipWithNext().sumOf { (a, b) ->
            if (b.hvacOn) (b.simTimeMs - a.simTimeMs) / 3_600_000.0 else 0.0
        }
        val outside = samples.mapNotNull { it.outsideTempC?.toDouble() }

        return MeasurementReport(
            id = id,
            spotId = spot.id,
            month = month,
            powerType = car.powerType,
            vehicleClass = car.vehicleClass,
            minCabinTempC = temps.min().round1(),
            avgCabinTempC = temps.average().round1(),
            hvacHours = hvacHours.round1(),
            hasEnergyReading = used != null,
            energyUsedPct = used?.round1() ?: 0.0,
            durationHours = durationH.round1(),
            rained = samples.any { it.wiperActive },
            createdAt = createdAt,
            outsideMinC = outside.minOrNull()?.round1(),
            outsideMaxC = outside.maxOrNull()?.round1(),
            devices = devices,
        )
    }

    private fun energyUsed(samples: List<CampingSample>): Double? {
        val first = samples.first().energyPct?.takeIf { it in 0f..100f } ?: return null
        var previous = first
        for (sample in samples) {
            val level = sample.energyPct?.takeIf { it in 0f..previous } ?: return null
            previous = level
        }
        return (first - previous).toDouble()
    }

    private fun Double.round1(): Double = Math.round(this * 10) / 10.0
}
