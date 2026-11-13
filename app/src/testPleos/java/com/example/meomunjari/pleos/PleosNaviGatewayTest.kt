package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.util.Log
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.navi.ChargingStop
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
    private val camp = NaviDestination("spot", "차박지", LatLng(37.1, 128.1))
    private val via = ChargingStop(NaviDestination("ev", "충전소", LatLng(37.2, 128.2)), 15)

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
    fun newRouteIncludesWaypointAndOnlyPublishesOnConfirmation() = runBlocking {
        val listener = connect()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp, via) }
        val request = argumentCaptor<RouteWithWaypointsRequest>()
        verify(sdk).requestRouteWithWaypoints(request.capture())
        assertEquals(camp.name, request.firstValue.destination.poiName)
        val station = request.firstValue.waypoints.single()
        assertEquals(WaypointIndex.FIRST, station.waypointIndex)
        assertEquals(via.station.location.lat, station.latitude, 0.0)
        assertEquals(via.station.location.lng, station.longitude, 0.0)
        assertNull(gateway.destination.value)
        listener.onRoutingCompleted()
        assertTrue(pending.await())
        assertEquals(camp, gateway.destination.value)
        assertEquals(via, gateway.chargingStop.value)
    }

    @Test
    fun addingToCurrentRouteRequiresWaypointAcknowledgement() = runBlocking {
        val listener = connect()
        val direct = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRoutingCompleted()
        assertTrue(direct.await())
        val adding = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp, via) }
        verify(sdk).addWaypoint(argThat { poiName == via.station.name && waypointIndex == WaypointIndex.FIRST })
        listener.onRoutingCompleted()
        assertFalse(adding.isCompleted)
        listener.onWaypointChanged(WaypointChangedInfo(WaypointIndex.FIRST, WaypointCmdType.ADD, mock()))
        assertTrue(adding.await())
        assertEquals(camp, gateway.destination.value)
        assertEquals(via, gateway.chargingStop.value)
    }

    @Test
    fun disconnectedOrPermissionDeniedDoesNotPublishRoute() = runBlocking {
        assertFalse(gateway.requestRoute(camp, via))
        verifyNoInteractions(sdk)
        connect()
        doThrow(SecurityException("denied")).whenever(sdk).requestRouteWithWaypoints(any())
        mockStatic(Log::class.java).use { assertFalse(gateway.requestRoute(camp, via)) }
        assertNull(gateway.destination.value)
        assertNull(gateway.chargingStop.value)
    }

    @Test
    fun waypointFailurePreservesActiveDestination() = runBlocking {
        val listener = connect()
        val direct = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRoutingCompleted()
        assertTrue(direct.await())
        val adding = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp, via) }
        mockStatic(Log::class.java).use { listener.onError(NaviErrorCode.CAUSE_SEARCH_ROUTE) }
        assertFalse(adding.await())
        assertEquals(camp, gateway.destination.value)
        assertNull(gateway.chargingStop.value)
    }
}
