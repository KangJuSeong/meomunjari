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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class PleosNaviGateway internal constructor(
    private val navi: NaviHelper,
    /** 내비 지도 앱을 띄운다. 띄우기를 시도했으면 true. */
    private val launchNaviApp: () -> Boolean = { false },
    /** 지도 앱이 준비되면 이 앱을 다시 앞으로 올린다(뒤에 있으면 얼려져 내비 이벤트를 놓친다). */
    private val bringAppBack: () -> Unit = {},
    /** 목적지 도착 등으로 이 앱을 앞으로 올릴 때 쓴다. */
    private val showApp: () -> Unit = bringAppBack,
    /** 길안내가 시작/끝날 때. 안내 중에는 앱이 얼려지지 않게 포그라운드 서비스를 켜 둔다. */
    private val onGuidanceStarted: (String) -> Unit = {},
    private val onGuidanceEnded: () -> Unit = {},
    /** 경로를 요청한 뒤 내비가 아무 이벤트도 보내지 않으면 지도 앱이 꺼진 것으로 보는 시간. */
    private val noResponseMs: Long = NO_RESPONSE_MS,
    /** 지도 앱을 띄운 뒤 다시 요청하기까지 기다리는 시간. */
    private val appStartMs: Long = NAVI_APP_START_MS,
    /** 이 앱을 앞으로 되돌린 뒤 다시 요청하기까지 기다리는 시간. */
    private val bringBackSettleMs: Long = BRING_BACK_SETTLE_MS,
) : NaviGateway {
    constructor(context: Context) : this(
        NaviHelper(context.applicationContext),
        { launchNaviApp(context.applicationContext) },
        { bringAppBack(context.applicationContext) },
        {
            // 직접 띄우기를 먼저 시도하고(허용되는 차에서는 바로 넘어옴), 막히는 경우를 위해 도착 알림도 띄운다.
            bringAppBack(context.applicationContext)
            GuidanceKeepAliveService.notifyArrived(context.applicationContext)
        },
        onGuidanceStarted = { name -> GuidanceKeepAliveService.start(context.applicationContext, name) },
        onGuidanceEnded = { GuidanceKeepAliveService.stop(context.applicationContext) },
    )

    private val _destination = MutableStateFlow<NaviDestination?>(null)
    override val destination = _destination.asStateFlow()
    private val requests = Mutex()
    private val routeState = Any()
    @Volatile private var connected = false
    private var pending: CompletableDeferred<Boolean>? = null

    /** 경로 요청 뒤 내비가 무엇이든 응답했는가. 응답이 없으면 지도 앱이 안 떠 있는 것이다. */
    @Volatile private var naviResponded = false

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() {
            naviResponded = true
            PleosLog.info("⇠ NaviHelper.onRoutingCompleted()")
            confirmPending("onRoutingCompleted")
        }

        override fun onError(errorCode: NaviErrorCode) {
            naviResponded = true
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
            naviResponded = true
            PleosLog.info("⇠ NaviHelper.onRouteEnded()")
            confirmPending("onRouteEnded")
        }

        /** 새 경로를 요청하면 내비가 먼저 기존 경로를 취소한다. 요청 중의 취소는 그 기존 경로 것이라 무시한다. */
        override fun onRouteCancelled() {
            naviResponded = true
            val requesting = synchronized(routeState) { pending != null }
            if (requesting) {
                PleosLog.info("⇠ NaviHelper.onRouteCancelled() → 새 경로 요청 중이라 기존 경로 취소로 보고 무시")
            } else {
                PleosLog.info("⇠ NaviHelper.onRouteCancelled() → 안내 종료로 처리")
                clearRoute()
            }
        }

        // ---- 아래는 앱 동작에는 쓰지 않고 로그로만 남기는 이벤트 ----
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            naviResponded = true
            PleosLog.info("⇠ NaviHelper.onRouteStarted = ${PleosLog.fmt(routeStartInfo)}")
        }
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
            naviResponded = true
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
        onGuidanceEnded()
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
            naviResponded = false
            // 포그라운드 서비스는 앱이 앞에 있을 때만 시작할 수 있다. 내비가 앞으로 나오기 전, 요청하는 지금 켠다.
            onGuidanceStarted(destination.name)
            PleosLog.info("→ NaviHelper.requestRoute(${PleosLog.fmt(route)})")
            navi.requestRoute(route)
            // 에뮬레이터를 막 켠 뒤처럼 지도 앱이 안 떠 있으면 요청이 아무 응답 없이 사라진다.
            // 잠깐 기다려도 내비가 아무 이벤트도 보내지 않으면 지도 앱을 띄우고 한 번 더 요청한다.
            var result = withTimeoutOrNull(noResponseMs) { request.await() }
            if (result == null && !naviResponded) {
                PleosLog.warn("내비가 ${noResponseMs / 1000}초 동안 응답 없음 → 지도 앱을 띄우고 다시 요청")
                if (launchNaviApp()) {
                    // 지도 앱이 화면을 띄우고 내비 엔진을 준비할 시간을 준 뒤 이 앱을 앞으로 되돌린다.
                    // 지도 앱이 뜨면서 처음 요청을 처리하는 경우가 많다. 그때는 내비가 앞에 나와 있으면 되므로 그대로 둔다.
                    withTimeoutOrNull(appStartMs) { request.await() }
                    if (!request.isCompleted) {
                        bringAppBack()
                        delay(bringBackSettleMs)
                        if (!request.isCompleted) {
                            PleosLog.info("→ NaviHelper.requestRoute(재요청) ${destination.name}")
                            navi.requestRoute(route)
                        }
                    }
                } else {
                    PleosLog.warn("지도 앱을 띄우지 못함 → 남은 시간 동안 응답을 기다림")
                }
            }
            if (result == null) result = withTimeoutOrNull(ROUTE_CONFIRM_TIMEOUT_MS) { request.await() }
            val confirmed = result == true
            PleosLog.info("← NaviHelper 경로 결과: ${if (confirmed) "성공" else "실패 또는 ${ROUTE_CONFIRM_TIMEOUT_MS / 1000}초 시간 초과"} (${destination.name})")
            synchronized(routeState) {
                if (!confirmed || pending !== request) {
                    if (_destination.value == null) onGuidanceEnded()
                    return@withLock false
                }
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

    override fun returnToApp() {
        PleosLog.info("목적지 도착 → 내비 화면에서 이 앱으로 돌아옴")
        showApp()
    }

    override fun cancelRoute() {
        clearRoute()
        PleosLog.info("→ NaviHelper.cancelRoute()")
        runCatching { navi.cancelRoute() }.onFailure { PleosLog.warn("✗ NaviHelper.cancelRoute: ${it.message}", it) }
    }

    private companion object {
        const val TAG = "PleosNavi"
        /** Pleos Connect 내비 지도 앱. 경로 요청은 내비 서비스를 거쳐 이 앱이 처리한다. */
        const val NAVI_APP_PACKAGE = "ai.umos.maps.android.navigation.app"
        const val NO_RESPONSE_MS = 5_000L
        const val NAVI_APP_START_MS = 4_000L
        const val BRING_BACK_SETTLE_MS = 1_000L

        /** 이 앱의 화면을 다시 맨 앞으로 올린다. */
        fun bringAppBack(context: Context) {
            runCatching {
                val back = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
                back.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(back)
                PleosLog.info("→ 이 앱을 다시 앞으로")
            }.onFailure { PleosLog.warn("✗ 이 앱을 앞으로 올리지 못함: ${it.message}", it) }
        }

        fun launchNaviApp(context: Context): Boolean = runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(NAVI_APP_PACKAGE)
            if (intent == null) {
                PleosLog.warn("지도 앱($NAVI_APP_PACKAGE)을 찾지 못함")
                return false
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            PleosLog.info("→ 지도 앱 실행: $NAVI_APP_PACKAGE")
            true
        }.getOrElse {
            PleosLog.warn("✗ 지도 앱 실행 실패: ${it.message}", it)
            false
        }
        /** 내비가 경로를 잡기까지 기다리는 시간. 에뮬레이터에서 200km 경로에 10초 넘게 걸린 적이 있다. */
        const val ROUTE_CONFIRM_TIMEOUT_MS = 30_000L
    }
}
