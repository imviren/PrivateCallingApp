package com.p2p.core.network

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class SignalingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ch_signaling"
        private const val TAG = "SignalingService"
    }

    @Inject
    lateinit var signalingServer: SignalingServer

    @Inject
    lateinit var tailscaleDetector: TailscaleDetector

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("SignalingService onCreate")
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).i("SignalingService onStartCommand")
        signalingServer.start()
        // Sticky so system restarts service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("SignalingService onDestroy")
        signalingServer.stop()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundServiceNotification() {
        val addrs = tailscaleDetector.getAddresses()
        val addressSummary = if (addrs.isEmpty()) {
            "Address: No Tailscale IP found"
        } else {
            "IP: ${addrs.ipv4 ?: addrs.ipv6}"
        }

        // Build a notification for the foreground service
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Private VoIP Signaling Active")
            .setContentText("Listening on $addressSummary:${SignalingServer.PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_call) // Standard system icon for simplicity
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
