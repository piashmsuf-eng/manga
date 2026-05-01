package com.piashmsuf.manga

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.color.DynamicColors

class MangaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_overlay_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_capture_desc) }
        )
    }

    companion object {
        const val CHANNEL_OVERLAY = "manga_overlay"
        const val CHANNEL_CAPTURE = "manga_capture"
    }
}
