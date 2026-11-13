package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.example.meomunjari.core.navi.NaviDestination
import com.example.meomunjari.core.navi.NaviGateway
import kotlinx.coroutines.CancellationException
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
    private val requests = Mutex()
    private val routeState = Any()
    @Volatile private var connected = false
    private var pending: CompletableDeferred<Boolean>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() {
            synchronized(routeState) { pending?.complete(true) }
        }

        override fun onError(errorCode: NaviErrorCode) {
            synchronized(routeState) { pending?.complete(false) }
            Log.w(TAG, "Navigation error: $errorCode")
        }

        override fun onRouteEnded() = clearRoute()

        override fun onRouteCancelled() = clearRoute()
    }

    private fun clearRoute() {
        synchronized(routeState) {
            pending?.complete(false)
            pending = null
            _destination.value = null
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
        clearRoute()
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    override suspend fun requestRoute(destination: NaviDestination): Boolean = requests.withLock {
        val request = CompletableDeferred<Boolean>()
        try {
            synchronized(routeState) {
                if (!connected) return@withLock false
                if (_destination.value == destination) return@withLock true
                pending = request
            }
            navi.requestRoute(RouteInfo(
                latitude = destination.location.lat, longitude = destination.location.lng,
                displayLatitude = null, displayLongitude = null, poiName = destination.name,
                poiId = "", poiSubId = "0", address = destination.address,
            ))
            val confirmed = withTimeoutOrNull(10_000) { request.await() } == true
            synchronized(routeState) {
                if (!confirmed || pending !== request) return@withLock false
                _destination.value = destination
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not set route", e)
            false
        } finally {
            synchronized(routeState) { pending = null }
        }
    }

    override fun cancelRoute() {
        clearRoute()
        runCatching { navi.cancelRoute() }
    }

    private companion object { const val TAG = "PleosNavi" }
}
