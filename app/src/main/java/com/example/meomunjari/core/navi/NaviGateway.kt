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

    /** 내비 화면에 있다가 목적지에 도착했을 때 이 앱 화면을 다시 앞으로 올린다. 기본은 아무것도 하지 않는다. */
    fun returnToApp() {}
}
