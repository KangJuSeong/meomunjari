package com.example.meomunjari.di

import android.content.Context
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.sim.SimulatedWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** mock 플레이버: 시뮬레이터 + 가상 차량 콘솔. */
object GatewayFactory {
    /** 앱 시작 시 요청할 런타임 권한. */
    val runtimePermissions: List<String> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun create(
        context: Context,
        scope: CoroutineScope,
        timeScale: StateFlow<Int>,
        spotsProvider: () -> List<Spot>,
        batteryKWh: Double,
    ): Gateways {
        val world = SimulatedWorld(scope, timeScale, spotsProvider, batteryKWh)
        return Gateways(
            vehicle = world,
            location = world,
            navi = world,
            console = world,
            flavorLabel = "mock",
        )
    }
}
