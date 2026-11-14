package com.example.meomunjari.pleos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * 길안내 중에만 켜 두는 포그라운드 서비스.
 *
 * 길안내가 시작되면 내비 화면이 앞으로 나오고 이 앱은 뒤로 간다. Android는 뒤에 있는 앱을 몇 초 뒤 얼리므로
 * (cached app freezer) 그대로 두면 위치·기어를 읽지 못해 목적지 도착을 알아채지 못한다.
 * 이 서비스가 켜져 있는 동안은 앱이 얼려지지 않아, 도착을 인증하고 차박 모드 화면으로 돌아올 수 있다.
 */
class GuidanceKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification(intent?.getStringExtra(EXTRA_DESTINATION).orEmpty())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            PleosLog.info("길안내 유지 서비스 시작 (앱이 뒤에 있어도 도착을 감지)")
        }.onFailure {
            PleosLog.warn("✗ 길안내 유지 서비스 시작 실패: ${it.message}", it)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PleosLog.info("길안내 유지 서비스 종료")
        super.onDestroy()
    }

    private fun notification(destination: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "길안내 중 도착 감지", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("머문자리")
            .setContentText(if (destination.isBlank()) "도착하면 차박 모드로 안내해요" else "$destination 도착하면 차박 모드로 안내해요")
            .setOngoing(true)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 7001
        private const val EXTRA_DESTINATION = "destination"

        fun start(context: Context, destination: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GuidanceKeepAliveService::class.java).putExtra(EXTRA_DESTINATION, destination),
                )
            }.onFailure { PleosLog.warn("✗ 길안내 유지 서비스 요청 실패: ${it.message}", it) }
        }

        private const val ARRIVAL_CHANNEL_ID = "arrival"
        private const val ARRIVAL_NOTIFICATION_ID = 7002

        /**
         * 목적지 도착 알림. 뒤에 있는 앱은 화면을 직접 띄울 수 없어서(Background activity launch 차단),
         * 전체 화면 인텐트를 단 알림으로 이 앱 화면을 연다. 시스템이 전체 화면을 허용하지 않으면 알림으로 뜨고 누르면 열린다.
         */
        fun notifyArrived(context: Context) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(ARRIVAL_CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(ARRIVAL_CHANNEL_ID, "차박지 도착", NotificationManager.IMPORTANCE_HIGH),
                    )
                }
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) ?: return
                val open = PendingIntent.getActivity(
                    context, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = Notification.Builder(context, ARRIVAL_CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle("차박지에 도착했어요")
                    .setContentText("눌러서 차박 모드를 시작하세요")
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setAutoCancel(true)
                    .build()
                manager.notify(ARRIVAL_NOTIFICATION_ID, notification)
                PleosLog.info("도착 알림 표시 (전체 화면 인텐트로 앱 열기 시도)")
            }.onFailure { PleosLog.warn("✗ 도착 알림 실패: ${it.message}", it) }
        }

        fun clearArrived(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(ARRIVAL_NOTIFICATION_ID) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuidanceKeepAliveService::class.java)) }
        }
    }
}
