package kr.contest.wakecar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 낮잠이 진행되는 동안 앱을 살려 두는 서비스.
 * 다른 화면으로 넘어가도 알람이 울리도록, 낮잠을 시작할 때 켜고 끝날 때 끈다.
 */
class NapService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "낮잠", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("일어나차")
            .setContentText("낮잠 중이에요. 정한 시각에 깨워 드려요.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "nap"

        fun setActive(context: Context, active: Boolean) {
            val intent = Intent(context, NapService::class.java)
            runCatching {
                if (active) context.startForegroundService(intent) else context.stopService(intent)
            }
        }
    }
}
