package com.example.meomunjari.di

import android.content.Context
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.pleos.PleosLocationGateway
import com.example.meomunjari.pleos.PleosNaviGateway
import com.example.meomunjari.pleos.PleosVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** pleos 플레이버: Pleos Connect SDK. 값 변경은 에뮬레이터 Virtual Console에서 한다. */
object GatewayFactory {
    /** AAOS 속도 권한은 런타임 승인이 필요할 수 있다. */
    val runtimePermissions: List<String> = listOf(
        "android.car.permission.CAR_SPEED",
        // FusedLocation SDK 위치. Pleos 위치 서비스가 dangerous 권한으로 선언해 실행 중에 받아야 한다.
        "pleos.car.permission.FUSED_LOCATION",
        // 목적지 도착 알림(Android 13+)
        "android.permission.POST_NOTIFICATIONS",
    )

    @Suppress("UNUSED_PARAMETER")
    fun create(
        context: Context,
        scope: CoroutineScope,
        timeScale: StateFlow<Int>,
        spotsProvider: () -> List<Spot>,
        batteryKWh: Double,
    ): Gateways = Gateways(
        vehicle = PleosVehicleGateway(context, scope),
        location = PleosLocationGateway(context),
        navi = PleosNaviGateway(context),
        console = null,
        flavorLabel = "pleos",
    )
}
