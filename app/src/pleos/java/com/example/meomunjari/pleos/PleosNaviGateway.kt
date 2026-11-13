package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.example.meomunjari.core.navi.ChargingStop
import com.example.meomunjari.core.navi.NaviDestination
import com.example.meomunjari.core.navi.NaviGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class PleosNaviGateway internal constructor(private val navi: NaviHelper) : NaviGateway {
    constructor(context: Context) : this(NaviHelper(context.applicationContext))

    private val _destination = MutableStateFlow<NaviDestination?>(null)
    override val destination = _destination.asStateFlow()
    private val _chargingStop = MutableStateFlow<ChargingStop?>(null)
    override val chargingStop = _chargingStop.asStateFlow()

    private val requests = Mutex()
    @Volatile private var connected = false
    private data class Pending(val adding: Boolean, val result: CompletableDeferred<Boolean>)
    @Volatile private var pending: Pending? = null
    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() {
            pending?.takeUnless { it.adding }?.result?.complete(true)
        }

        override fun onWaypointChanged(info: WaypointChangedInfo) {
            if (info.cmdType == WaypointCmdType.ADD && info.index == WaypointIndex.FIRST)
                pending?.takeIf { it.adding }?.result?.complete(true)
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            if (info.index == WaypointIndex.FIRST) _chargingStop.value = null
        }

        override fun onError(errorCode: NaviErrorCode) {
            pending?.result?.complete(false)
            Log.w(TAG, "Navigation error: $errorCode")
        }
        override fun onRouteEnded() {
            _destination.value = null
            _chargingStop.value = null
        }

        override fun onRouteCancelled() {
            _destination.value = null
            _chargingStop.value = null
        }
    }

    override fun start() {
        runCatching {
            navi.initialize(onConnected = {
                navi.addListener(listener)
                connected = true
            }, onFailed = { Log.w(TAG, "Navigation unavailable", it) })
        }.onFailure { Log.w(TAG, "Navigation initialization failed", it) }
    }

    override fun stop() {
        connected = false
        pending?.result?.complete(false)
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        _destination.value = null
        _chargingStop.value = null
    }

    override suspend fun requestRoute(destination: NaviDestination, via: ChargingStop?): Boolean = requests.withLock {
        if (!connected) return@withLock false
        val sameDestination = _destination.value == destination
        if (sameDestination && via == _chargingStop.value) return@withLock true
        val adding = via != null && sameDestination && _chargingStop.value == null
        val request = Pending(adding, CompletableDeferred())
        pending = request
        try {
            val target = RouteInfo(latitude = destination.location.lat, longitude = destination.location.lng,
                displayLatitude = null, displayLongitude = null, poiName = destination.name,
                poiId = "", poiSubId = "0", address = destination.address)
            if (via == null) navi.requestRoute(target)
            else {
                val s = via.station
                val waypoint = RequestWaypointInfo(latitude = s.location.lat, longitude = s.location.lng,
                    poiName = s.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = s.address, poiSubId = "0")
                if (adding) navi.addWaypoint(waypoint)
                else navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(target, listOf(waypoint)))
            }
            val ok = withTimeoutOrNull(10_000) { request.result.await() } == true
            if (ok) {
                _destination.value = destination
                _chargingStop.value = via
            }
            ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not set route", e)
            false
        } finally {
            pending = null
        }
    }

    override fun cancelRoute() {
        pending?.result?.complete(false)
        runCatching { navi.cancelRoute() }
        _destination.value = null
        _chargingStop.value = null
    }

    private companion object { const val TAG = "PleosNavi" }
}
