package com.p2p.camera

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.p2p.core.network.SignalingService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CommunicationApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging only in Debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.tag("CommunicationApp").i("Application onCreate")

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Signaling Server Channel (Low importance, persistent)
            val signalingChannel = NotificationChannel(
                SignalingService.CHANNEL_ID,
                "Signaling Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of background P2P signaling listener"
                setShowBadge(false)
            }

            // 2. Active Call Channel (High importance, system call overlay)
            val callChannel = NotificationChannel(
                "ch_call",
                "Active Call Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows active calling notifications and ringers"
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(signalingChannel)
            notificationManager.createNotificationChannel(callChannel)
            Timber.tag("CommunicationApp").i("Notification channels created successfully")
        }
    }
}
