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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class SignalingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ch_signaling"
        private const val TAG = "SignalingService"
        const val ACTION_START_CALL = "com.p2p.ACTION_START_CALL"
        const val ACTION_END_CALL = "com.p2p.ACTION_END_CALL"
    }

    @Inject
    lateinit var signalingServer: SignalingServer

    @Inject
    lateinit var tailscaleDetector: TailscaleDetector

    @Inject
    lateinit var tailscaleScanner: TailscaleScanner

    private var publishJob: Job? = null

    override fun onCreate() {
        super.onCreate()
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
                ACTION_END_CALL -> updateForegroundServiceType(false)
            }
        }

        // Sticky so system restarts service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("SignalingService onDestroy")
        signalingServer.stop()
        publishJob?.cancel()
        publishJob = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundServiceNotification() {
        updateForegroundServiceType(false)
    }

    private fun updateForegroundServiceType(inCall: Boolean) {
        val addrs = tailscaleDetector.getAddresses()
        val addressSummary = if (addrs.isEmpty()) {
            "Address: No Tailscale IP found"
        } else {
            "IP: ${addrs.ipv4 ?: addrs.ipv6}"
        }

        val title = if (inCall) "Active Call" else "Private VoIP Signaling Active"
        val content = if (inCall) "Voice/video connection is active" else "Listening on $addressSummary:${SignalingServer.PORT}"

        // Build a notification for the foreground service
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_call) // Standard system icon for simplicity
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
}
