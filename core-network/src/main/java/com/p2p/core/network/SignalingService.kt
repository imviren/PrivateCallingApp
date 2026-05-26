package com.p2p.core.network

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
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SignalingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val TEXT_MSG_NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "ch_signaling"
        const val CHANNEL_TEXT_ID = "ch_text_msg"
        private const val TAG = "SignalingService"
        const val ACTION_START_CALL = "com.p2p.ACTION_START_CALL"
        const val ACTION_END_CALL = "com.p2p.ACTION_END_CALL"
        const val ACTION_TEXT_MESSAGE = "com.p2p.ACTION_TEXT_MESSAGE"
        const val EXTRA_TEXT_SENDER = "extra_text_sender"
        const val EXTRA_TEXT_BODY = "extra_text_body"
    }

    @Inject
    lateinit var signalingServer: SignalingServer

    @Inject
    lateinit var tailscaleDetector: TailscaleDetector

    @Inject
    lateinit var tailscaleScanner: TailscaleScanner

    private var publishJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        Timber.tag(TAG).i("SignalingService onCreate")
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).i("SignalingService onStartCommand")
        signalingServer.start()

        if (publishJob == null) {
            publishJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    tailscaleScanner.publishPresence()
                    delay(15000L)
                }
            }
        }

        intent?.action?.let { action ->
            Timber.tag(TAG).i("onStartCommand action: $action")
            when (action) {
                ACTION_START_CALL -> updateForegroundServiceType(true)
                ACTION_END_CALL   -> updateForegroundServiceType(false)
                ACTION_TEXT_MESSAGE -> {
                    val sender = intent.getStringExtra(EXTRA_TEXT_SENDER) ?: "Unknown"
                    val body   = intent.getStringExtra(EXTRA_TEXT_BODY)   ?: ""
                    postTextMessageNotification(sender, body)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("SignalingService onDestroy")
        signalingServer.stop()
        publishJob?.cancel()
        publishJob = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification channels ─────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service / call channel (low importance, persistent)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VoIP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the P2P signaling server running"
                setShowBadge(false)
            }

            // Text message channel (high importance, heads-up)
            val textChannel = NotificationChannel(
                CHANNEL_TEXT_ID,
                "Text Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming peer-to-peer text messages"
                enableLights(true)
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(textChannel)
        }
    }

    // ── Foreground service notification ──────────────────────────────────────

    private fun startForegroundServiceNotification() {
        updateForegroundServiceType(false)
    }

    private fun updateForegroundServiceType(inCall: Boolean) {
        val addrs = tailscaleDetector.getAddresses()
        val addressSummary = if (addrs.isEmpty()) "No Tailscale IP"
                             else addrs.ipv4 ?: addrs.ipv6 ?: "Unknown"

        val title   = if (inCall) "🔒 Private Call Active"    else "🔒 SecureComm Running"
        val content = if (inCall) "End-to-end encrypted call" else "Listening • $addressSummary"
        val icon    = if (inCall) android.R.drawable.ic_menu_call
                      else        android.R.drawable.stat_sys_phone_call_on_hold

        // Tap notification to open app
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(if (inCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setColorized(inCall)
            .setColor(if (inCall) 0xFF1565C0.toInt() else 0xFF4D94FF.toInt())
            .build()

        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = if (inCall) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Text message heads-up notification ────────────────────────────────────

    private fun postTextMessageNotification(sender: String, body: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, TEXT_MSG_NOTIFICATION_ID, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(this, CHANNEL_TEXT_ID)
            .setContentTitle("💬 Message from $sender")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFFC580FF.toInt())
            .build()

        notificationManager.notify(TEXT_MSG_NOTIFICATION_ID, notification)
    }
}
