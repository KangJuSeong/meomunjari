package com.example.meomunjari.domain

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.vehicle.VehicleSnapshot

data class ArrivalStatus(
    val spot: Spot?,
    val location: LatLng?,
    val distanceM: Double?,
    val withinRadius: Boolean,
    val parked: Boolean,
    /** 반경 안 + 주차 상태가 유지된 시간. */
    val heldMs: Long,
    val certified: Boolean,
    /** 이번 평가에서 막 인증됐는가. */
    val justCertified: Boolean = false,
)

/**
 * 도착 인증: 스팟 반경 안에서 P단 주차가 holdMs 이상 이어지면 방문으로 인정한다.
 * 위치가 튀거나 잠깐 정차한 경우를 거르기 위해 유지 시간을 둔다.
 */
class ArrivalVerifier(private val holdMs: Long = 5_000) {
    private var holdingSpotId: String? = null
    private var holdStartedAt: Long = 0

    fun evaluate(
        spot: Spot?,
        location: LatLng?,
        vehicle: VehicleSnapshot,
        nowMs: Long,
        alreadyCertified: Boolean,
    ): ArrivalStatus {
        val distance = if (spot != null && location != null) Geo.distanceM(location, spot.location) else null
        val within = spot != null && distance != null && distance <= spot.radiusM
        val parked = DriveGuard.isParked(vehicle)

        val holding = within && parked
        if (!holding) {
            holdingSpotId = null
        } else if (holdingSpotId != spot?.id) {
            holdingSpotId = spot?.id
            holdStartedAt = nowMs
        }
        val held = if (holding) nowMs - holdStartedAt else 0L
        val certifiedNow = holding && held >= holdMs
        return ArrivalStatus(
            spot = spot,
            location = location,
            distanceM = distance,
            withinRadius = within,
            parked = parked,
            heldMs = held,
            certified = alreadyCertified || certifiedNow,
            justCertified = certifiedNow && !alreadyCertified,
        )
    }
}
