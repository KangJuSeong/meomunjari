package com.example.meomunjari.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.example.meomunjari.core.location.LocationGateway
import com.example.meomunjari.core.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** FusedLocation SDK 1.1.3 (공식 샘플 pleos-sample-fusedlocation-app 과 같은 호출 순서). */
class PleosLocationGateway(context: Context) : LocationGateway {
    private val fused = FusedLocation(context.applicationContext)
    private val _location = MutableStateFlow<LatLng?>(null)
    override val location: StateFlow<LatLng?> = _location.asStateFlow()

    private var initialized = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            PleosLog.debug("⇠ FusedLocation.onChanged = ${PleosLog.fmt(location)}")
            _location.value = location?.takeIf {
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }?.let { LatLng(it.latitude, it.longitude) }
        }

        override fun onError(exception: Exception) {
            PleosLog.warn("✗ FusedLocation.onError: ${exception.javaClass.simpleName}: ${exception.message}", exception)
            _location.value = null
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    /**
     * 위치 구독을 시작한다. 이미 구독 중이면 아무것도 하지 않고, 실패했으면 다음 호출 때 다시 시도한다.
     * (권한 허용 직후 첫 호출이 SecurityException으로 실패해도, 화면에 돌아올 때 다시 불러 복구한다.)
     */
    override fun start() {
        if (initialized) return
        var sdkInitialized = false
        runCatching {
            val supported = fused.isSupported()
            PleosLog.info("FusedLocation.isSupported() = $supported")
            if (!supported) {
                Log.w(TAG, "FusedLocation not supported on this vehicle")
                return
            }
            PleosLog.info("→ FusedLocation.initialize()")
            fused.initialize()
            sdkInitialized = true
            PleosLog.info("→ FusedLocation.getLastKnownLocation(listener)")
            fused.getLastKnownLocation(listener)
            PleosLog.info("→ FusedLocation.registerFusedLocationCallback(listener)")
            fused.registerFusedLocationCallback(listener)
            initialized = true
        }.onFailure {
            PleosLog.warn("✗ FusedLocation start: ${it.javaClass.simpleName}: ${it.message} → 다음에 다시 시도", it)
            Log.e(TAG, "FusedLocation start failed", it)
            if (sdkInitialized) runCatching { fused.release() }
        }
    }

    override fun stop() {
        if (!initialized) return
        PleosLog.info("→ FusedLocation.unregisterFusedLocationCallback(listener) / release()")
        runCatching {
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        }.onFailure { PleosLog.warn("✗ FusedLocation stop: ${it.message}", it) }
        initialized = false
        _location.value = null
    }

    private companion object {
        const val TAG = "PleosLocation"
    }
}
