package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
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
        // onRouteEnded는 '경로 탐색 완료'라 안내 중인 목적지를 지우지 않는다.
        listener.onRouteEnded()
        assertEquals(camp, gateway.destination.value)
        // 내비가 경로 없이 주행하면(FREE_DRIVE) 안내가 끝난 것이다.
        listener.onNavigationStatus(NavigationStatus.FREE_DRIVE)
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
    fun silentNaviLaunchesMapAppAndRetriesOnce() = runBlocking {
        var launched = 0
        val waking = PleosNaviGateway(sdk, launchNaviApp = { launched++; true }, noResponseMs = 50, appStartMs = 10, bringBackSettleMs = 10)
        doAnswer { it.getArgument<() -> Unit>(0).invoke(); Unit }.whenever(sdk).initialize(any(), any())
        waking.start()
        val captor = argumentCaptor<NaviHelperEventListener>()
        verify(sdk).addListener(captor.capture())
        val request = async(start = CoroutineStart.UNDISPATCHED) { waking.requestRoute(camp) }
        // 지도 앱이 꺼져 있어 아무 이벤트도 없다 → 지도 앱을 띄우고 한 번 더 요청
        kotlinx.coroutines.delay(150)
        assertEquals(1, launched)
        verify(sdk, times(2)).requestRoute(any())
        captor.firstValue.onRoutingCompleted()
        assertTrue(request.await())
        assertEquals(camp, waking.destination.value)
    }

    @Test
    fun respondingNaviIsNotRelaunched() = runBlocking {
        var launched = 0
        val waking = PleosNaviGateway(sdk, launchNaviApp = { launched++; true }, noResponseMs = 50, appStartMs = 10, bringBackSettleMs = 10)
        doAnswer { it.getArgument<() -> Unit>(0).invoke(); Unit }.whenever(sdk).initialize(any(), any())
        waking.start()
        val captor = argumentCaptor<NaviHelperEventListener>()
        verify(sdk).addListener(captor.capture())
        val request = async(start = CoroutineStart.UNDISPATCHED) { waking.requestRoute(camp) }
        // 내비가 기존 경로 취소로 응답했고, 경로 탐색은 오래 걸린다
        captor.firstValue.onRouteCancelled()
        kotlinx.coroutines.delay(150)
        assertEquals(0, launched)
        captor.firstValue.onRouteEnded()
        assertTrue(request.await())
        verify(sdk, times(1)).requestRoute(any())
    }

    @Test
    fun previousRouteCancellationDuringRequestIsIgnored() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        // 새 경로를 요청하면 내비가 먼저 기존 경로를 취소한다.
        listener.onRouteCancelled()
        listener.onRoutingCompleted()
        assertTrue(request.await())
        assertEquals(camp, gateway.destination.value)
    }

    @Test
    fun emulatorEventOrderConfirmsRoute() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        // Pleos 에뮬레이터에서 실제로 본 순서 (onRoutingCompleted는 오지 않음)
        listener.onRouteCancelled()
        listener.onNavigationStatus(NavigationStatus.FREE_DRIVE)
        listener.onRouteEnded()
        listener.onNavigationStatus(NavigationStatus.ROUTE_DRIVE)
        assertTrue(request.await())
        assertEquals(camp, gateway.destination.value)
    }

    @Test
    fun sdkCancellationWithoutPendingRequestEndsGuidance() = runBlocking {
        val listener = connect()
        val request = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestRoute(camp) }
        listener.onRoutingCompleted()
        assertTrue(request.await())
        listener.onRouteCancelled()
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
