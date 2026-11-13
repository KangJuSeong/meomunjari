package com.example.meomunjari.core.navi

import com.example.meomunjari.core.model.LatLng
import kotlinx.coroutines.flow.StateFlow

data class NaviDestination(
    val spotId: String,
    val name: String,
    val location: LatLng,
    val address: String = "",
)

data class ChargingStop(val station: NaviDestination, val minutes: Int)

interface NaviGateway {
    val destination: StateFlow<NaviDestination?>

    val chargingStop: StateFlow<ChargingStop?>

    fun start()
    fun stop()

    /** 차량 내비에 목적지를 설정한다. 요청이 전달되면 true. */
    suspend fun requestRoute(destination: NaviDestination, via: ChargingStop? = null): Boolean

    fun cancelRoute()
}
