package com.example.meomunjari

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.meomunjari.di.GatewayFactory
import com.example.meomunjari.ui.MeomunjariRoot
import com.example.meomunjari.ui.theme.MeomunjariTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as MeomunjariApp).container

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { container.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsThenStart()
        setContent {
            MeomunjariTheme {
                MeomunjariRoot(container)
            }
        }
    }

    private fun requestRuntimePermissionsThenStart() {
        val missing = GatewayFactory.runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) container.start() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        // SDK release()는 앱이 SDK를 더 이상 쓰지 않을 때만 호출한다(공식 가이드).
        if (isFinishing) container.stop()
        super.onDestroy()
    }
}
