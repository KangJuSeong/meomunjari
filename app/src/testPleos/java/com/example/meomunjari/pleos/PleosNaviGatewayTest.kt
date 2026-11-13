package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.util.Log
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.navi.NaviDestination
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class PleosNaviGatewayTest {
    private val sdk = mock<NaviHelper>()
    private val gateway = PleosNaviGateway(sdk)
    private val camp = NaviDestination("spot", "차박지", LatLng(37.1, 128.1), "강원도")

    private fun connect(): NaviHelperEventListener {
        doAnswer {
            it.getArgument<() -> Unit>(0).invoke()
            Unit
        }.whenever(sdk).initialize(any(), any())
        gateway.start()
        val listener = argumentCaptor<NaviHelperEventListener>()
        verify(sdk).addListener(listener.capture())
        return listener.firstValue
    }

    @Test
    fun directRoutePublishesCampsiteOnConfirmationAndEndsWithNavigation() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        val route = argumentCaptor<RouteInfo>()
        verify(sdk).requestRoute(route.capture())
        assertEquals(camp.name, route.firstValue.poiName)
        assertEquals(camp.address, route.firstValue.address)
        assertEquals(camp.location.lat, route.firstValue.latitude, 0.0)
        assertEquals(camp.location.lng, route.firstValue.longitude, 0.0)
        assertNull(gateway.destination.value)
        listener.onRoutingCompleted()
        assertTrue(request.await())
        assertEquals(camp, gateway.destination.value)
        assertTrue(gateway.requestRoute(camp))
        verify(sdk, times(1)).requestRoute(any())
        listener.onRouteEnded()
        assertNull(gateway.destination.value)
    }

    @Test
    fun cancellationWhileConfirmationIsPendingDoesNotRestoreDestination() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRoutingCompleted()
        gateway.cancelRoute()
        verify(sdk).cancelRoute()
        assertFalse(request.await())
        assertNull(gateway.destination.value)
    }

    @Test
    fun sdkCancellationStopsPendingRequest() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRouteCancelled()
        listener.onRoutingCompleted()
        assertFalse(request.await())
        assertNull(gateway.destination.value)
    }

    @Test
    fun disconnectedOrPermissionDeniedDoesNotPublishRoute() = runBlocking {
        assertFalse(gateway.requestRoute(camp))
        verifyNoInteractions(sdk)
        connect()
        doThrow(SecurityException("denied")).whenever(sdk).requestRoute(any())
        mockStatic(Log::class.java).use { assertFalse(gateway.requestRoute(camp)) }
        assertNull(gateway.destination.value)
    }

    @Test
    fun failedReplacementPreservesConfirmedDestination() = runBlocking {
        val listener = connect()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRoutingCompleted()
        assertTrue(first.await())
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.requestRoute(NaviDestination("other", "다른 스팟", LatLng(38.1, 127.5)))
        }
        mockStatic(Log::class.java).use { listener.onError(NaviErrorCode.CAUSE_SEARCH_ROUTE) }
        assertFalse(replacement.await())
        assertEquals(camp, gateway.destination.value)
    }
}
