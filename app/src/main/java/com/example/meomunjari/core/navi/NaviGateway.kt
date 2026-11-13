package com.example.meomunjari.core.navi

import com.example.meomunjari.core.model.LatLng
import kotlinx.coroutines.flow.StateFlow

data class NaviDestination(
    val spotId: String,
    val name: String,
    val location: LatLng,
    val address: String = "",
)

interface NaviGateway {
    val destination: StateFlow<NaviDestination?>

    fun start()
    fun stop()

    /** 차량 내비에 목적지를 설정한다. 목적지 안내가 설정되면 true. */
    suspend fun requestRoute(destination: NaviDestination): Boolean

    fun cancelRoute()
}
