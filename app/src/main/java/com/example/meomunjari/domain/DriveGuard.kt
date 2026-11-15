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

    /** 연결된 차량의 주차 증거. 기어가 없으면 유효한 정지 속도가 필요하다. */
    fun isParked(s: VehicleSnapshot): Boolean {
        if (!s.connected) return false
        val speed = s.speedKmh
        if (speed != null && (!speed.isFinite() || speed < 0f || speed >= 0.5f)) return false
        return s.gear == Gear.PARK || (s.gear == Gear.UNKNOWN && speed != null)
    }

    fun canControl(s: VehicleSnapshot): Boolean = isParked(s)

    fun canShowReviewQr(s: VehicleSnapshot): Boolean = s.gear == Gear.PARK && isParked(s)

    fun canRegisterSpot(s: VehicleSnapshot): Boolean = isParked(s)
}
