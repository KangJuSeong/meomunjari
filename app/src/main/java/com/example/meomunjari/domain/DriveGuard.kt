package com.example.meomunjari.domain

import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.core.vehicle.VehicleSnapshot

enum class DriveState { PARKED, DRIVING }

/** 주행 중 화면 제한과 차량 제어 허용 여부를 판단한다. */
object DriveGuard {
    private const val MOVING_KMH = 3f

    fun evaluate(s: VehicleSnapshot): DriveState {
        val moving = (s.speedKmh ?: 0f) > MOVING_KMH
        val inGear = s.gear == Gear.DRIVE || s.gear == Gear.REVERSE
        return if (moving || inGear) DriveState.DRIVING else DriveState.PARKED
    }

    /** 주차(P) 상태인가. 기어를 못 읽는 차는 정지 상태로 대신 판단한다. */
    fun isParked(s: VehicleSnapshot): Boolean = when (s.gear) {
        Gear.PARK -> true
        Gear.UNKNOWN -> (s.speedKmh ?: 0f) < 0.5f
        else -> false
    }

    /** 차량 제어는 주차 상태에서만. */
    fun canControl(s: VehicleSnapshot): Boolean = isParked(s)

    fun canShowReviewQr(s: VehicleSnapshot): Boolean =
        s.connected && s.gear == Gear.PARK && evaluate(s) == DriveState.PARKED

    /** 등록하면 현재 위치 방문도 인증하므로 차량 상태로 정차를 확인해야 한다. */
    fun canRegisterSpot(s: VehicleSnapshot): Boolean =
        s.connected && (s.gear != Gear.UNKNOWN || s.speedKmh != null) &&
            isParked(s) && evaluate(s) == DriveState.PARKED
}
