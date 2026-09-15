package com.example.bbcplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/** Keeps audio playback alive when the Activity is stopped, folded, or recreated. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private var retryCount = 0
    private var retryPosition = 0L
    private var retryShouldPlay = false

    lateinit var player: ExoPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            retryCount = 0
                            startForeground(NOTIFICATION_ID, notification())
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount >= MAX_RETRIES) return
                        retryPosition = currentPosition.coerceAtLeast(0)
                        retryShouldPlay = playWhenReady
                        retryCount++
                        android.os.Handler(mainLooper).postDelayed({
                            if (!isPlaying) {
                                prepare()
                                seekTo(retryPosition)
                                playWhenReady = retryShouldPlay
                            }
                        }, retryCount * 1_500L)
                    }
                })
            }
    }

    fun keepAlive() {
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("오디오 재생 중")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "오디오 재생", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "bbc_player_playback"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRIES = 3
    }
}
