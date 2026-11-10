package com.example.meomunjari.pleos

import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.util.Log
import com.example.meomunjari.core.navi.NaviDestination
import com.example.meomunjari.core.navi.NaviGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.primaryConstructor

/**
 * NaviHelper SDK 3.0.5. 생성·initialize·cancelRoute·release는 문서 예제 그대로,
 * RouteInfo는 import 경로를 확인하지 못해 requestRoute의 파라미터 타입에서 리플렉션으로 만든다.
 */
class PleosNaviGateway(context: Context) : NaviGateway {
    private val navi = NaviHelper(context.applicationContext)
    private val _destination = MutableStateFlow<NaviDestination?>(null)
    override val destination: StateFlow<NaviDestination?> = _destination.asStateFlow()

    private var connected = false

    override fun start() {
        runCatching {
            navi.initialize(
                onConnected = { connected = true },
                onFailed = { t -> Log.w(TAG, "NaviHelper connect failed: ${t.message}") },
            )
        }.onFailure { Log.e(TAG, "NaviHelper initialize failed", it) }
    }

    override fun stop() {
        runCatching { navi.release() }
        connected = false
    }

    override suspend fun requestRoute(destination: NaviDestination): Boolean = runCatching {
        val fn = NaviHelper::class.declaredMemberFunctions.first { f ->
            f.name == "requestRoute" && f.parameters.size == 2
        }
        val routeInfoClass = fn.parameters[1].type.classifier as KClass<*>
        val ctor = routeInfoClass.primaryConstructor ?: error("RouteInfo constructor not found")
        val values = mapOf(
            "latitude" to destination.location.lat,
            "longitude" to destination.location.lng,
            "poiName" to destination.name,
            "poiId" to destination.spotId,
            "poiSubId" to "0",
            "address" to destination.address,
        )
        val args = ctor.parameters.associateWith { p -> values[p.name] }
            .filter { (p, v) -> v != null || !p.isOptional }
        fn.call(navi, ctor.callBy(args))
        _destination.value = destination
        true
    }.onFailure { Log.e(TAG, "requestRoute failed", it) }.getOrDefault(false)

    override fun cancelRoute() {
        runCatching { navi.cancelRoute() }
        _destination.value = null
    }

    private companion object {
        const val TAG = "PleosNavi"
    }
}
