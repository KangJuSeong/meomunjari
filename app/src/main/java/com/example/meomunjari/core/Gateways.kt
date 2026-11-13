package com.example.meomunjari.core

import com.example.meomunjari.core.location.LocationGateway
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.navi.NaviGateway
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.core.vehicle.VehicleGateway
import kotlinx.coroutines.flow.StateFlow

/** 빌드 플레이버가 만들어 주는 외부 연동 묶음. */
class Gateways(
    val vehicle: VehicleGateway,
    val location: LocationGateway,
    val navi: NaviGateway,
    /** mock 플레이버에서만 존재. */
    val console: VirtualConsole?,
    val flavorLabel: String,
) {
    fun start() {
        vehicle.start()
        location.start()
        navi.start()
    }

    fun stop() {
        navi.stop()
        location.stop()
        vehicle.stop()
    }
}

data class LocationPreset(val label: String, val location: LatLng)

/** mock 플레이버의 가상 차량 콘솔. Pleos Virtual Console이 하는 일을 앱 안에서 대신한다. */
interface VirtualConsole {
    val outsideTempC: StateFlow<Float>
    val autoDriving: StateFlow<Boolean>

    fun locationPresets(): List<LocationPreset>
    fun setLocation(location: LatLng)
    fun setGear(gear: Gear)
    fun setSpeed(kmh: Float)
    fun setBattery(pct: Float)
    fun setCabinTemp(celsius: Float)
    fun setOutsideTemp(celsius: Float)
    fun setWiper(active: Boolean)
    fun setCharging(enabled: Boolean)
    fun setAutoDrive(enabled: Boolean)
    fun setCapability(item: ControlItem, supported: Boolean)
}
