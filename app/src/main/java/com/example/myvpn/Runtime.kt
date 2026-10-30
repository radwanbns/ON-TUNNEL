package com.example.myvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل الأحداث الظاهر في تبويب LOGS. */
object AppLog {
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun add(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg"
        main.post {
            lines.add(line)
            if (lines.size > 500) lines.removeAt(0)
        }
    }

    fun clear() { main.post { lines.clear() } }
}

/** خدمة أمامية تعرض إشعار "Connected" وتبقي التطبيق حياً. */
class TunnelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            if (V2rayManager.status != VpnStatus.DISCONNECTED) {
                Thread { V2rayManager.disconnect() }.start()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra("name") ?: "V2Ray"

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Connection status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ON Tunnel")
            .setContentText("Connected — $name")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        ShareProxy.sync(this) // مشاركة الإنترنت إن كانت مفعّلة
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ShareProxy.stop()
        super.onDestroy()
    }

    companion object { const val CHANNEL = "tunnel" }
}
