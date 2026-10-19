package com.chiti.phonemic.service

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chiti.phonemic.MainActivity
import com.chiti.phonemic.R
import com.chiti.phonemic.audio.AudioSessionManager
import com.chiti.phonemic.audio.AudioState
import com.chiti.phonemic.model.NetworkRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service ensuring uninterrupted low-latency audio capture
 * while the screen is locked or the app is backgrounded.
 */
class MicForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phonemic_live_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.chiti.phonemic.ACTION_START"
        const val ACTION_STOP = "com.chiti.phonemic.ACTION_STOP"
        const val ACTION_TOGGLE_MUTE = "com.chiti.phonemic.ACTION_TOGGLE_MUTE"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, MicForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MicForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session by lazy { AudioSessionManager.get(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMuted = false
    private var deviceName = "Bluetooth Speaker"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            session.pttPressed.collectLatest {
                if (session.audioState.value is AudioState.Active) updateNotification()
            }
        }
        scope.launch {
            session.audioState.collectLatest { state ->
                isMuted = state is AudioState.Muted
                if (state is AudioState.Active || state is AudioState.Muted) updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Bluetooth Speaker"
                if (!startForegroundWithNotification()) {
                    session.serviceStartFailed("Android could not start the required foreground audio service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                acquireWakeLock()
                scope.launch { if (!session.startFromService()) stopSelf() }
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                session.setMuted(session.audioState.value !is AudioState.Muted)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.serviceDestroyed()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(): Boolean {
        return runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = when (session.role.value) {
                    NetworkRole.RECEIVER_MIXER -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    NetworkRole.TRANSMITTER -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    NetworkRole.STANDALONE, NetworkRole.WALKIE_TALKIE -> {
                        if (Build.VERSION.SDK_INT >= 34) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                    }
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { e ->
            android.util.Log.e("MicForegroundService", "Failed to startForeground notification", e)
        }.isSuccess
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { e ->
            android.util.Log.w("MicForegroundService", "Failed to update notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val muteIntent = Intent(this, MicForegroundService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MicForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isReceiver = session.role.value == NetworkRole.RECEIVER_MIXER || session.role.value == NetworkRole.WALKIE_TALKIE
        val title = when {
            session.role.value == NetworkRole.WALKIE_TALKIE -> "PhoneMic Walkie-Talkie · LAN-1"
            isReceiver -> "PhoneMic Speaker Hub 🔊"
            else -> "PhoneMic is Live 🎙️"
        }
        val statusText = when {
            isMuted -> "MUTED — Tap to resume"
            session.role.value == NetworkRole.WALKIE_TALKIE -> if (session.pttPressed.value) "TRANSMITTING — Release PTT to listen" else "LISTENING — Hold PTT in the app to speak"
            isReceiver -> "PLAYING — Receiving wireless audio"
            else -> "LIVE — Broadcasting to $deviceName"
        }
        val muteActionText = if (isMuted) "UNMUTE" else "MUTE"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_microphone)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_microphone, muteActionText, mutePendingIntent)
            .addAction(R.drawable.ic_microphone, "STOP", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhoneMic Live Broadcasting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when your microphone is actively broadcasting to external speakers"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic:AudioBroadcastWakeLock").apply {
                acquire(120 * 60 * 1000L) // 2 hours safety timeout
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
