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
            _location.value = location?.takeIf {
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }?.let { LatLng(it.latitude, it.longitude) }
        }

        override fun onError(exception: Exception) {
            _location.value = null
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    override fun start() {
        if (initialized) return
        runCatching {
            if (!fused.isSupported()) {
                Log.w(TAG, "FusedLocation not supported on this vehicle")
                return
            }
            fused.initialize()
            initialized = true
            fused.getLastKnownLocation(listener)
            fused.registerFusedLocationCallback(listener)
        }.onFailure { Log.e(TAG, "FusedLocation start failed", it) }
    }

    override fun stop() {
        if (!initialized) return
        runCatching {
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        }
        initialized = false
        _location.value = null
    }

    private companion object {
        const val TAG = "PleosLocation"
    }
}
