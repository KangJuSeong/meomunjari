package com.example.meomunjari.trip

import com.example.meomunjari.core.Geo
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.core.model.BatteryComparison
import com.example.meomunjari.core.model.Amenities
import com.example.meomunjari.core.model.LatLng
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.RestrictedPlaceReport
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagCount
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.core.model.Visit
import com.example.meomunjari.domain.CampingEnergyPlanner
import com.example.meomunjari.core.navi.NaviDestination
import com.example.meomunjari.core.vehicle.Gear
import com.example.meomunjari.core.vehicle.ApplyItemResult
import com.example.meomunjari.core.vehicle.ApplyStatus
import com.example.meomunjari.core.vehicle.VehicleSnapshot
import com.example.meomunjari.data.Repositories
import com.example.meomunjari.domain.ArrivalStatus
import com.example.meomunjari.domain.ArrivalVerifier
import com.example.meomunjari.domain.BadgeEngine
import com.example.meomunjari.domain.DriveGuard
import com.example.meomunjari.domain.DriveState
import com.example.meomunjari.domain.HeadingTracker
import com.example.meomunjari.domain.ParkingAdvice
import com.example.meomunjari.domain.ParkingAdvisor
import com.example.meomunjari.domain.SunCalculator
import com.example.meomunjari.domain.SunriseMode
import com.example.meomunjari.domain.MemberStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.meomunjari.core.vehicle.ControlItem
import com.example.meomunjari.domain.SettingCompatibility
import com.example.meomunjari.domain.ReportAggregator
import com.example.meomunjari.domain.SpotSearch
import com.example.meomunjari.domain.TagNormalizer
import com.example.meomunjari.domain.camping.CampingRecorder
import com.example.meomunjari.domain.camping.CampingSample
import com.example.meomunjari.domain.camping.CampingState
import com.example.meomunjari.domain.camping.FinishedCamping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.meomunjari.domain.WakeSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDate

data class RouteProgress(val remainingKm: Double, val remainingMin: Int)

sealed interface StartCampingResult {
    data class Started(val state: CampingState) : StartCampingResult
    data object NotParked : StartCampingResult
    data object NoSpot : StartCampingResult
}

sealed interface RegisterResult {
    data class Registered(val spot: Spot) : RegisterResult
    data class Restricted(val zoneName: String, val reason: String) : RegisterResult
    data object NoLocation : RegisterResult
    data object NameRequired : RegisterResult
    data object NotParked : RegisterResult
    data object Duplicate : RegisterResult
}

/**
 * 한 번의 차박 흐름(목적지 → 도착 인증 → 차박 모드 → 실측 기록 → 리뷰)을 관리하는 앱 상태.
 * Android에 의존하지 않아 단위 테스트할 수 있다.
 */
class TripStore(
    private val scope: CoroutineScope,
    private val gateways: Gateways,
    private val repos: Repositories,
    private val tags: TagNormalizer,
    /** 시연 가속 배율. 1 = 실제 시간, 600 = 1초에 10분. */
    val timeScale: MutableStateFlow<Int>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    val vehicle: StateFlow<VehicleSnapshot> get() = gateways.vehicle.snapshot
    val location: StateFlow<LatLng?> get() = gateways.location.location

    val driveState: StateFlow<DriveState> = gateways.vehicle.snapshot
        .map { DriveGuard.evaluate(it) }
        .stateIn(scope, SharingStarted.Eagerly, DriveState.PARKED)

    private val _destination = MutableStateFlow<Spot?>(null)
    val destination: StateFlow<Spot?> = _destination.asStateFlow()

    val route: StateFlow<RouteProgress?> = combine(_destination, location) { spot, loc ->
        if (spot == null || loc == null) return@combine null
        val km = CampingEnergyPlanner.roadKm(loc, spot.location)
        RouteProgress(km, CampingEnergyPlanner.drivingMinutes(km))
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _arrival = MutableStateFlow<ArrivalStatus?>(null)
    val arrival: StateFlow<ArrivalStatus?> = _arrival.asStateFlow()

    private val _certifiedVisit = MutableStateFlow<Visit?>(null)
    val certifiedVisit: StateFlow<Visit?> = _certifiedVisit.asStateFlow()

    private val _wakeTime = MutableStateFlow<LocalTime?>(null)
    val wakeTime = _wakeTime.asStateFlow()

    fun selectWakeTime(time: LocalTime?) {
        _wakeTime.value = time?.withSecond(0)?.withNano(0)
    }

    private val _camping = MutableStateFlow<CampingState?>(null)
    val camping: StateFlow<CampingState?> = _camping.asStateFlow()

    private val _finished = MutableStateFlow<FinishedCamping?>(null)
    val finished: StateFlow<FinishedCamping?> = _finished.asStateFlow()

    private val verifier = ArrivalVerifier()
    private val headingTracker = HeadingTracker()

    private var sunriseMode = SunriseMode.WINDSHIELD
    private val _parkingAdvice = MutableStateFlow<ParkingAdvice?>(null)
    val parkingAdvice = _parkingAdvice.asStateFlow()

    /**
     * 차박을 마친 스팟. 차가 반경 밖으로 떠나기 전까지는 같은 자리에 서 있어도 다시 인증하지 않는다.
     * (차박 종료 직후 재인증 → 리뷰 화면이 도착 화면으로 덮이던 문제)
     */
    private var finishedSpotId: String? = null

    /**
     * 앱을 켤 때 이미 주차해 있던 스팟. 그 자리에서 앱을 켰다는 이유만으로 도착 인증하지 않는다.
     * 그 스팟을 목적지로 정하면 예외이고, 반경의 2배 밖으로 나갔다 오면 다시 인증할 수 있다.
     */
    private var launchSpotId: String? = null
    private var launchChecked = false

    /** 앱 실행(start) 때 켠다. 단위 테스트는 evaluateArrival을 직접 부르므로 기본은 꺼 둔다. */
    internal var skipSpotParkedAtLaunch = false

    /** 이번 방문 직전의 회원 상태. 차박 후 '이번에 오른 진행도'를 보여줄 기준. */
    private var statusBeforeVisit: MemberStatus? = null
    private var clockAnchorMs = 0L
    private var elapsedAtAnchorMs = 0L

    private fun campingElapsedMs(): Long =
        elapsedAtAnchorMs + (elapsedMs() - clockAnchorMs).coerceAtLeast(0) * timeScale.value.coerceAtLeast(1)

    fun setTimeScale(scale: Int) {
        if (_camping.value != null) {
            elapsedAtAnchorMs = campingElapsedMs()
            clockAnchorMs = elapsedMs()
        }
        timeScale.value = scale.coerceAtLeast(1)
    }

    private var loops: List<Job> = emptyList()

    fun start() {
        if (loops.isNotEmpty()) return
        skipSpotParkedAtLaunch = true
        loops = listOf(
            scope.launch { while (isActive) { evaluateArrival(); delay(500) } },
            scope.launch { campingLoop() },
            scope.launch {
                gateways.navi.destination.collect { active ->
                    if (active == null && gateways.navi.destination.value == null) _destination.value = null
                }
            },
        )
    }

    fun stop() {
        loops.forEach { it.cancel() }
        loops = emptyList()
    }

    // ---------- 목적지 ----------

    suspend fun navigateTo(spot: Spot): Boolean {
        val ok = gateways.navi.requestRoute(
            NaviDestination(spot.id, spot.name, spot.location, spot.region),
        )
        if (ok) _destination.value = spot
        return ok
    }

    fun cancelRoute() {
        gateways.navi.cancelRoute()
        _destination.value = null
    }

    // ---------- 도착 인증 ----------

    internal fun evaluateArrival() {
        val loc = location.value
        val snap = vehicle.value
        val spots = repos.spots.spots.value
        val certified = _certifiedVisit.value

        if (loc == null || !snap.connected || snap.gear == Gear.UNKNOWN || snap.gear == Gear.NEUTRAL) {
            headingTracker.reset()
        } else {
            headingTracker.onLocation(
                loc, moving = snap.gear == Gear.DRIVE || snap.gear == Gear.REVERSE,
                reversing = snap.gear == Gear.REVERSE,
            )
        }

        // 인증 후 멀리 떠나면(차박 중 아님) 인증을 해제한다.
        if (certified != null && _camping.value == null && loc != null) {
            val spot = repos.spots.get(certified.spotId)
            if (spot != null && Geo.distanceM(loc, spot.location) > spot.radiusM * 2) {
                repos.spots.leave(spot.id)
                _certifiedVisit.value = null
            }
        }

        // 차박을 마친 스팟을 떠나면(반경의 2배 밖) 다음 방문을 다시 인증할 수 있다.
        finishedSpotId?.let { id ->
            val done = repos.spots.get(id)
            if (done == null || loc == null || Geo.distanceM(loc, done.location) > done.radiusM * 2) finishedSpotId = null
        }

        // 앱을 켠 순간의 위치가 스팟 안이면 기억해 두고, 그 스팟은 목적지가 아닐 때 자동 인증 대상에서 뺀다.
        if (skipSpotParkedAtLaunch && !launchChecked && loc != null) {
            launchChecked = true
            launchSpotId = SpotSearch.spotAt(spots, loc)?.id
        }
        launchSpotId?.let { id ->
            val launched = repos.spots.get(id)
            if (launched == null || (loc != null && Geo.distanceM(loc, launched.location) > launched.radiusM * 2)) launchSpotId = null
        }
        val nearby = loc?.let { SpotSearch.spotAt(spots, it) }?.takeUnless { it.id == launchSpotId }
        val target = _destination.value ?: nearby
            ?: _certifiedVisit.value?.let { repos.spots.get(it.spotId) }
        val already = target != null &&
            (_certifiedVisit.value?.spotId == target.id || finishedSpotId == target.id)
        val status = verifier.evaluate(target, loc, snap, nowMs(), already)
        _arrival.value = status
        if (status.justCertified && target != null) certify(target)
        refreshParkingAdvice()
    }

    private fun certify(spot: Spot, beforeVisit: MemberStatus = status()) {
        statusBeforeVisit = beforeVisit
        val date = today()
        val visit = Visit(newId("visit"), spot.id, date.toString(), date.monthValue)
        val car = repos.user.user.value.car
        repos.user.update { it.copy(visits = it.visits + visit) }
        repos.spots.recordVisit(spot.id, car.vehicleClass, date.toString())
        _certifiedVisit.value = visit
        if (_destination.value?.id == spot.id) {
            // 길안내로 도착했으면 내비 화면에 머물지 않고 차박 모드 화면으로 넘어오게 한다(안내를 끄기 전에).
            gateways.navi.returnToApp()
            gateways.navi.cancelRoute()
            _destination.value = null
        }
    }

    /** 반경 안에 등록된 스팟이 없는 곳에 주차 중인가 (신규 등록 제안용). */
    fun isParkedAtUnknownPlace(): Boolean {
        val loc = location.value ?: return false
        if (!DriveGuard.canRegisterSpot(vehicle.value)) return false
        return SpotSearch.spotAt(repos.spots.spots.value, loc) == null
    }

    // ---------- 주차 방향(일출) ----------

    fun setSunriseMode(mode: SunriseMode) {
        sunriseMode = mode
        refreshParkingAdvice()
    }

    fun flipHeading() {
        if (_parkingAdvice.value?.currentHeadingDeg == null) return
        headingTracker.flip()
        refreshParkingAdvice()
    }

    private fun refreshParkingAdvice() {
        val status = _arrival.value
        val spot = status?.spot
        if (spot == null || !status.withinRadius || !status.parked) {
            _parkingAdvice.value = null
            return
        }
        val sun = SunCalculator.nextSunrise(spot.location, Instant.ofEpochMilli(nowMs()))
        // 가상 콘솔에서 좌표만 옮기거나 위치가 크게 튀면 이전 장소의 방위를 쓰지 않는다.
        val heading = headingTracker.headingDeg.takeIf {
            headingTracker.measuredAt?.let { Geo.distanceM(it, spot.location) <= spot.radiusM + 30.0 } == true
        }
        _parkingAdvice.value = sun?.let { ParkingAdvisor.advise(it, sunriseMode, heading, headingTracker.flipped) }
    }

    // ---------- 차박 모드 ----------

    @Synchronized
    fun setConsent(consent: Boolean) {
        repos.user.update { it.copy(consentToRecord = consent) }
        if (!consent) {
            _camping.update { it?.copy(recording = false, samples = emptyList()) }
        }
    }

    suspend fun startCamping(
        preset: SettingPreset,
        includeDoor: Boolean,
        loadedPresetId: String? = null,
        devices: List<String> = emptyList(),
    ): StartCampingResult {
        if (!DriveGuard.canControl(vehicle.value)) return StartCampingResult.NotParked
        val visit = _certifiedVisit.value ?: return StartCampingResult.NoSpot
        val spot = repos.spots.get(visit.spotId) ?: return StartCampingResult.NoSpot

        val results: List<ApplyItemResult> = gateways.vehicle.apply(preset, includeDoor)
        val user = repos.user.user.value
        if (loadedPresetId == preset.id && preset.spotId == spot.id && results.any { it.status == ApplyStatus.APPLIED }) {
            repos.presets.recordLoad(preset.id, user.nickname, visit.id)
        }
        val snapshot = vehicle.value
        val wakeAt = wakeTime.value
        val startTime = Instant.ofEpochMilli(nowMs()).atZone(ZoneId.systemDefault()).toLocalTime()
        val plannedMinutes = WakeSchedule.stayMinutes(startTime, wakeAt)
        val initialMorningPct = if (user.car.powerType == PowerType.EV && snapshot.connected && snapshot.hvacPowerKnown) {
            val night = CampingEnergyPlanner.estimateNight(
                spot, startTime, user.car, snapshot.hvacTargetC?.toDouble(), snapshot.hvacOn, stayMinutes = plannedMinutes,
            )
            val battery = snapshot.batteryPct
            if (battery != null && battery in 0f..100f && night != null) (battery - night.drainPct).coerceAtLeast(0.0)
            else null
        } else null
        clockAnchorMs = elapsedMs()
        elapsedAtAnchorMs = 0
        val state = CampingState(
            spot = spot,
            visit = visit,
            preset = preset,
            applyResults = results,
            recording = user.consentToRecord,
            samples = if (user.consentToRecord) listOf(sample(0L)) else emptyList(),
            initialMorningPct = initialMorningPct,
            plannedMinutes = plannedMinutes,
            wakeAt = wakeAt,
            startedAt = startTime,
            devices = devices,
        )
        _camping.value = state
        _finished.value = null
        return StartCampingResult.Started(state)
    }

    private val seatMutex = Mutex()

    /**
     * 차박 중에 마이 화면에서 내 세팅의 시트(메모리 번호·좌석별 등받이 각도)를 바꾸면 차에도 바로 적용한다.
     * 지금 차박에 쓴 세팅이 내 세팅일 때, 주차 상태일 때만 움직이고 아니면 null을 돌려준다.
     */
    suspend fun applyMySeatToCamping(): ApplyItemResult? {
        val mine = repos.user.user.value.mySetting
        val state = _camping.value ?: return null
        if (state.preset.id != mine.id || !DriveGuard.canControl(vehicle.value)) return null
        val result = seatMutex.withLock { gateways.vehicle.applySeat(mine) }
        _camping.update { cur ->
            cur?.copy(
                preset = cur.preset.copy(seatMemorySlot = mine.seatMemorySlot, backrestPctBySeat = mine.backrestPctBySeat),
                applyResults = cur.applyResults.filterNot { it.item == ControlItem.SEAT } + result,
            )
        }
        return result
    }

    fun setProtectionThreshold(pct: Int) = _camping.update { it?.copy(protectionThresholdPct = pct) }

    suspend fun setHvac(on: Boolean): Boolean = gateways.vehicle.setHvacPower(on)

    private suspend fun campingLoop() {
        while (scope.isActive) {
            delay(500L)
            val current = _camping.value ?: continue
            if (campingElapsedMs() - current.elapsedSimMs >= CampingRecorder.SAMPLE_INTERVAL_SIM_MS) {
                recordSample(current)
            }
        }
    }

    internal suspend fun recordSample(current: CampingState) {
        val elapsed = campingElapsedMs()
        val s = sample(elapsed)
        var notice = current.notice
        var triggered = current.protectionTriggered && vehicle.value.connected && vehicle.value.hvacPowerKnown && !s.hvacOn
        val energy = s.energyPct
        if (energy != null && energy in 0f..current.protectionThresholdPct.toFloat() && s.hvacOn) {
            val accepted = vehicle.value.connected && DriveGuard.canControl(vehicle.value) && gateways.vehicle.setHvacPower(false)
            triggered = accepted && withTimeoutOrNull(3_000L) {
                vehicle.first { it.connected && it.hvacPowerKnown && !it.hvacOn }
            } != null
            notice = when {
                triggered -> "잔량 보호를 위해 공조를 껐어요"
                !accepted -> "공조 끄기에 실패했어요. 차량의 공조 상태를 확인해 주세요."
                else -> "공조 끄기를 요청했지만 꺼짐을 확인하지 못했어요. 차량 상태를 확인해 주세요."
            }
        } else if (s.hvacOn || !triggered) {
            notice = null
        }
        _camping.update { active ->
            if (active == null || active.visit?.id != current.visit?.id) active
            else active.copy(
                samples = if (active.recording) active.samples + s else emptyList(),
                elapsedSimMs = elapsed,
                protectionTriggered = triggered,
                notice = notice,
            )
        }
    }

    private fun sample(elapsedMs: Long): CampingSample {
        val snap = vehicle.value
        val energy = when (repos.user.user.value.car.powerType) {
            PowerType.EV -> snap.batteryPct
            PowerType.ICE -> snap.fuelPct
        }
        return CampingSample(elapsedMs, snap.cabinTempC, energy, snap.hvacOn, snap.wiperActive, snap.outsideTempC)
    }

    @Synchronized
    fun endCamping(): FinishedCamping? {
        val state = _camping.value ?: return null
        val date = today()
        val userBefore = repos.user.user.value
        val before = statusBeforeVisit ?: status()

        val comparison = if (userBefore.car.powerType == PowerType.EV) {
            val snap = vehicle.value
            BatteryComparison(state.initialMorningPct, snap.batteryPct?.takeIf { snap.connected && it in 0f..100f }?.toDouble(),
                campingElapsedMs() / 60_000L, state.plannedMinutes, state.wakeAt?.toString())
        } else null
        val report = if (state.recording && userBefore.consentToRecord) {
            CampingRecorder.buildReport(
                id = newId("report"),
                spot = state.spot,
                samples = state.samples,
                car = userBefore.car,
                month = date.monthValue,
                createdAt = date.toString(),
                devices = state.devices,
            )?.copy(batteryComparison = comparison)
        } else {
            null
        }

        var credits = 0
        if (report != null) {
            val season = ReportAggregator.season(report.month)
            val firstInSeason = repos.reports.forSpot(report.spotId).none { ReportAggregator.season(it.month) == season }
            credits = if (firstInSeason) 2 else 1
            repos.reports.saveMine(report)
            repos.user.update { it.copy(reportCount = it.reportCount + 1, reportCredits = it.reportCredits + credits) }
        }

        repos.spots.leave(state.spot.id)
        selectWakeTime(null)
        finishedSpotId = state.spot.id
        _camping.value = null
        _certifiedVisit.value = null
        statusBeforeVisit = null

        val finished = FinishedCamping(
            spot = state.spot,
            visit = state.visit,
            report = report,
            batteryComparison = comparison,
            creditsEarned = credits,
            before = before,
            after = status(),
        )
        _finished.value = finished
        return finished
    }

    // ---------- 리뷰·태그 ----------

    @Synchronized
    fun submitReview(rating: Int, text: String, rawTags: List<String>, sharePreset: Boolean): FinishedCamping? {
        val finished = _finished.value ?: return null
        if (finished.reviewSubmitted) return finished
        val user = repos.user.user.value
        val normalized = tags.normalizeAll(rawTags)

        var presetId: String? = null
        if (sharePreset) {
            val preset = SettingCompatibility.forOthers(user.mySetting).copy(
                id = newId("preset"),
                spotId = finished.spot.id,
                ownerNickname = user.nickname,
                ownerGrade = status().grade.label,
                ownerRating = rating.toDouble(),
                loadCount = 0,
            )
            repos.presets.publish(preset)
            presetId = preset.id
        }

        repos.reviews.add(
            Review(
                id = newId("review"),
                spotId = finished.spot.id,
                author = user.nickname,
                authorGrade = status().grade.label,
                rating = rating,
                text = text.trim(),
                createdAt = today().toString(),
                tags = normalized,
                presetId = presetId,
            ),
        )
        repos.spots.addRating(finished.spot.id, rating)
        if (normalized.isNotEmpty()) repos.spots.addTags(finished.spot.id, normalized)
        repos.user.update { it.copy(reviewCount = it.reviewCount + 1) }

        val updated = finished.copy(after = status(), reviewSubmitted = true)
        _finished.value = updated
        return updated
    }

    // ---------- 스팟 등록 ----------

    fun reportRestrictedPlace(spotId: String, note: String): RestrictedPlaceReport? =
        repos.spots.reportRestrictedPlace(
            RestrictedPlaceReport(spotId, repos.user.user.value.nickname, note.trim(), today().toString()),
        )

    fun registerSpot(
        name: String,
        description: String,
        amenities: Amenities,
        rawTags: List<String>,
        maxVehicleClass: VehicleClass,
        region: String = "",
    ): RegisterResult {
        if (name.isBlank()) return RegisterResult.NameRequired
        val loc = location.value ?: return RegisterResult.NoLocation
        if (!DriveGuard.canRegisterSpot(vehicle.value)) return RegisterResult.NotParked
        restrictedZoneAt(loc)?.let {
            return RegisterResult.Restricted(it.name, it.reason)
        }
        if (SpotSearch.spotAt(repos.spots.spots.value, loc) != null) return RegisterResult.Duplicate

        val user = repos.user.user.value
        val spot = Spot(
            id = newId("spot"),
            name = name.trim(),
            region = region.trim(),
            lat = loc.lat,
            lng = loc.lng,
            description = description.trim(),
            amenities = amenities,
            tags = tags.normalizeAll(rawTags).map { TagCount(it, 1) },
            createdAt = today().toString(),
            registeredBy = user.nickname,
            maxVehicleClass = maxVehicleClass,
            photoColors = listOf("#2E4A3F", "#0F1A17"),
        )
        val beforeRegistration = status()
        repos.spots.register(spot)
        repos.user.update { it.copy(registeredSpotIds = it.registeredSpotIds + spot.id) }
        certify(spot, beforeRegistration)
        return RegisterResult.Registered(spot)
    }

    /** 등록 화면에서 실시간으로 보여줄 금지구역 대조 결과. */
    fun restrictedZoneAt(loc: LatLng?) = loc?.let { point ->
        repos.reference.zones.firstOrNull { it.contains(point) }
    }

    // ---------- 공통 ----------

    fun status(): MemberStatus = BadgeEngine.evaluate(repos.user.user.value, repos.spots.spots.value)

    private var seq = 0
    private fun newId(prefix: String): String = "$prefix-${nowMs().toString(36)}-${seq++}"
}
