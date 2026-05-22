package fumi.day.literalmusi.data.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import fumi.day.literalmusi.R
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var musicPlayer: MusicPlayer
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        mediaSession = MediaSession.Builder(this, musicPlayer.player).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setSmallIcon(R.drawable.ic_notification_music_note)
                .setChannel(CHANNEL_ID, R.string.notification_channel)
                .build()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession!!
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    companion object {
        const val CHANNEL_ID = "playback"
    }
}
