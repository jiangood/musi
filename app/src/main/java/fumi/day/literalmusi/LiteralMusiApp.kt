package fumi.day.literalmusi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import fumi.day.literalmusi.data.player.PlaybackService

@HiltAndroidApp
class LiteralMusiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            PlaybackService.CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
