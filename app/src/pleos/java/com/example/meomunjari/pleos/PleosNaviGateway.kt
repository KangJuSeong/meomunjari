package com.example.meomunjari.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
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
            PleosLog.info("⇠ NaviHelper.onRoutingCompleted()")
            confirmPending("onRoutingCompleted")
        }

        override fun onError(errorCode: NaviErrorCode) {
            PleosLog.warn("✗ NaviHelper.onError = ${PleosLog.fmt(errorCode)}")
            synchronized(routeState) { pending?.complete(false) }
            Log.w(TAG, "Navigation error: $errorCode")
        }

        /**
         * SDK 문서상 '경로 탐색 완료'. Pleos 에뮬레이터 내비는 onRoutingCompleted 대신 이것을 보낸다
         * (onRouteStarted → onRouteEnded → onNavigationStatus(ROUTE_DRIVE)). 안내가 끝났다는 뜻이 아니므로
         * 요청 중이면 성공으로 보고, 아니면 목적지를 지우지 않는다. 안내 종료는 FREE_DRIVE로 판단한다.
         */
        override fun onRouteEnded() {
            PleosLog.info("⇠ NaviHelper.onRouteEnded()")
            confirmPending("onRouteEnded")
        }

        /** 새 경로를 요청하면 내비가 먼저 기존 경로를 취소한다. 요청 중의 취소는 그 기존 경로 것이라 무시한다. */
        override fun onRouteCancelled() {
            val requesting = synchronized(routeState) { pending != null }
            if (requesting) {
                PleosLog.info("⇠ NaviHelper.onRouteCancelled() → 새 경로 요청 중이라 기존 경로 취소로 보고 무시")
            } else {
                PleosLog.info("⇠ NaviHelper.onRouteCancelled() → 안내 종료로 처리")
                clearRoute()
            }
        }

        // ---- 아래는 앱 동작에는 쓰지 않고 로그로만 남기는 이벤트 ----
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            PleosLog.info("⇠ NaviHelper.onRouteStarted = ${PleosLog.fmt(routeStartInfo)}")
        override fun onReRouteEnded() = PleosLog.info("⇠ NaviHelper.onReRouteEnded()")
        override fun onRouteOptionChanged(routeOption: RouteOption) =
            PleosLog.info("⇠ NaviHelper.onRouteOptionChanged = ${PleosLog.fmt(routeOption)}")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            PleosLog.info("⇠ NaviHelper.onWaypointChanged = ${PleosLog.fmt(waypointChangedInfo)}")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            PleosLog.info("⇠ NaviHelper.onWayPointArrived = ${PleosLog.fmt(waypointArrivedInfo)}")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            PleosLog.info("⇠ NaviHelper.onDestinationArrived = ${PleosLog.fmt(destinationArrivedInfo)}")
        override fun onBookMarkChanged() = PleosLog.debug("⇠ NaviHelper.onBookMarkChanged()")
        override fun onDestinationChanged() = PleosLog.info("⇠ NaviHelper.onDestinationChanged()")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) =
            PleosLog.info("⇠ NaviHelper.onRouteStateInfo = ${PleosLog.fmt(routeStateInfo)}")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) =
            PleosLog.info("⇠ NaviHelper.onDestinationInfo = ${PleosLog.fmt(destinationInfo)}")
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) =
            PleosLog.info("⇠ NaviHelper.onWayPointInfo = ${PleosLog.fmt(waypointInfoList)}")
        override fun onNavigationStatus(status: NavigationStatus) {
            PleosLog.info("⇠ NaviHelper.onNavigationStatus = ${PleosLog.fmt(status)}")
            when (status) {
                NavigationStatus.ROUTE_DRIVE -> confirmPending("onNavigationStatus(ROUTE_DRIVE)")
                // 경로를 바꾸는 중에도 기존 경로를 지우며 잠깐 FREE_DRIVE가 오므로, 요청 중이 아닐 때만 안내 종료로 본다.
                NavigationStatus.FREE_DRIVE -> {
                    val requesting = synchronized(routeState) { pending != null }
                    if (!requesting && _destination.value != null) {
                        PleosLog.info("내비가 경로 없이 주행 중(FREE_DRIVE) → 안내 종료로 처리")
                        clearRoute()
                    }
                }
                else -> Unit
            }
        }
        // 주행 중 자주(초 단위로) 들어오는 이벤트는 Debug 수준으로 둔다.
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            PleosLog.debug("⇠ NaviHelper.onCurrentLocationInfo = ${PleosLog.fmt(currentLocationInfo)}")
        override fun onDrivingInfo(drivingInfo: DrivingInfo) =
            PleosLog.debug("⇠ NaviHelper.onDrivingInfo = ${PleosLog.fmt(drivingInfo)}")
        override fun onTripInfo(tripInfo: TripInfo) = PleosLog.debug("⇠ NaviHelper.onTripInfo = ${PleosLog.fmt(tripInfo)}")
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = PleosLog.debug("⇠ NaviHelper.onTBTInfo = ${PleosLog.fmt(tbtInfoList)}")
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = PleosLog.debug("⇠ NaviHelper.onCurrentRoadSpeedLimit = $speedLimit")
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) =
            PleosLog.debug("⇠ NaviHelper.onCameraAlert = ${PleosLog.fmt(cameraAlertList)}")
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) =
            PleosLog.debug("⇠ NaviHelper.onCautionPointInfo = ${PleosLog.fmt(cautionPointList)}")
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) =
            PleosLog.debug("⇠ NaviHelper.onChargerOperatorInfo = ${PleosLog.fmt(chargerOperatorList)}")
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) =
            PleosLog.debug("⇠ NaviHelper.onRecentDestinationInfo = ${PleosLog.fmt(recentDestinationInfoList)}")
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) =
            PleosLog.debug("⇠ NaviHelper.onBookmarkInfo = ${PleosLog.fmt(bookmarkInfoList)}")
    }

    /** 경로 요청이 진행 중이면 성공으로 끝낸다. */
    private fun confirmPending(by: String) {
        val confirmed = synchronized(routeState) { pending?.complete(true) == true }
        if (confirmed) PleosLog.info("경로 설정 확인: $by")
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
            PleosLog.info("→ NaviHelper.initialize()")
            navi.initialize(onConnected = {
                PleosLog.info("← NaviHelper.initialize onConnected → addListener(listener)")
                navi.addListener(listener)
                connected = true
            }, onFailed = {
                PleosLog.warn("✗ NaviHelper.initialize onFailed: ${it.message}", it)
                Log.w(TAG, "Navigation unavailable", it)
            })
        }.onFailure {
            PleosLog.warn("✗ NaviHelper.initialize: ${it.message}", it)
            Log.w(TAG, "Navigation initialization failed", it)
        }
    }

    override fun stop() {
        connected = false
        clearRoute()
        PleosLog.info("→ NaviHelper.removeListener(listener) / release()")
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    override suspend fun requestRoute(destination: NaviDestination): Boolean = requests.withLock {
        val request = CompletableDeferred<Boolean>()
        try {
            synchronized(routeState) {
                if (!connected) {
                    PleosLog.warn("NaviHelper 미연결이라 경로 요청 생략: ${destination.name}")
                    return@withLock false
                }
                if (_destination.value == destination) {
                    PleosLog.info("이미 같은 목적지로 안내 중: ${destination.name}")
                    return@withLock true
                }
                pending = request
            }
            val route = RouteInfo(
                latitude = destination.location.lat, longitude = destination.location.lng,
                displayLatitude = null, displayLongitude = null, poiName = destination.name,
                poiId = "", poiSubId = "0", address = destination.address,
            )
            PleosLog.info("→ NaviHelper.requestRoute(${PleosLog.fmt(route)})")
            navi.requestRoute(route)
            val confirmed = withTimeoutOrNull(10_000) { request.await() } == true
            PleosLog.info("← NaviHelper 경로 결과: ${if (confirmed) "성공" else "실패 또는 10초 시간 초과"} (${destination.name})")
            synchronized(routeState) {
                if (!confirmed || pending !== request) return@withLock false
                _destination.value = destination
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PleosLog.warn("✗ NaviHelper 경로 요청: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.w(TAG, "Could not set route", e)
            false
        } finally {
            synchronized(routeState) { pending = null }
        }
    }

    override fun cancelRoute() {
        clearRoute()
        PleosLog.info("→ NaviHelper.cancelRoute()")
        runCatching { navi.cancelRoute() }.onFailure { PleosLog.warn("✗ NaviHelper.cancelRoute: ${it.message}", it) }
    }

    private companion object { const val TAG = "PleosNavi" }
}
