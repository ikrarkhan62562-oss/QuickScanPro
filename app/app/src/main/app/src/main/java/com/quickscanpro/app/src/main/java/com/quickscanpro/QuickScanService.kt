package com.quickscanpro

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.SoundPool
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class QuickScanService : Service() {

    private lateinit var soundPool: SoundPool
    private var soundId: Int = 0
    private var isSoundPlaying = false
    private var isMutedForAdmin = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    // Volume Guard: Har 500ms mein check karega
    private val volumeGuardRunnable = object : Runnable {
        override fun run() {
            ensureMaxVolume()
            handler.postDelayed(this, 500)
        }
    }

    // Battery Monitor: Har 30 second mein check karega
    private val batteryMonitorRunnable = object : Runnable {
        override fun run() {
            checkBatteryStatus()
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        initializeSound()
        
        // Volume Guard Start
        handler.post(volumeGuardRunnable)
        
        // Battery Monitor Start
        handler.post(batteryMonitorRunnable)
        
        // WakeLock Acquire (Power Off Block)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or 
            PowerManager.ACQUIRE_CAUSES_WAKEUP or 
            PowerManager.ON_AFTER_RELEASE
        )
        wakeLock?.acquire()
    }

    private fun createNotification(): Notification {
        val channelId = "quickscan_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "QuickScan Pro",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("QuickScan Pro Active")
            .setContentText("Verifying payment status...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun initializeSound() {
        val soundPoolAttributes = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()
        soundPool = soundPoolAttributes

        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0 && !isMutedForAdmin) {
                playSoundLoop()
            }
        }

        // Load alarm.mp3 from res/raw
        soundId = soundPool.load(this, R.raw.alarm, 1)
    }

    private fun playSoundLoop() {
        if (!isSoundPlaying && !isMutedForAdmin) {
            isSoundPlaying = true
            // Full volume, infinite loop
            soundPool.play(soundId, 1.0f, 1.0f, 0, -1, 1)
        }
    }

    private fun stopSoundLoop() {
        if (isSoundPlaying) {
            soundPool.stop(soundId)
            isSoundPlaying = false
        }
    }

    // Volume Guard: Agar user volume ghataye toh foran full karo
    private fun ensureMaxVolume() {
        if (isMutedForAdmin) return
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume < maxVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        }
    }

    // Battery Monitor: Charging status check karo
    private fun checkBatteryStatus() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL)
        
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
        val percent = if (scale > 0) (level * 100) / scale else 0

        // Agar charging hai, toh notification update karo
        if (isCharging) {
            updateNotification("QuickScan Pro Active", "Charging • Verifying payment...")
        } else if (percent <= 10) {
            updateNotification("QuickScan Pro Active", "Low Battery: $percent% • Verifying...")
        }
    }

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, "quickscan_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_SOUND" -> {
                isMutedForAdmin = false
                playSoundLoop()
            }
            "STOP_SOUND" -> {
                stopSoundLoop()
            }
            "MUTE_TEMP" -> {
                isMutedForAdmin = true
                stopSoundLoop()
            }
            "UNLOCK" -> {
                isMutedForAdmin = false
                stopSoundLoop()
                // Service ko stop karo
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(volumeGuardRunnable)
        handler.removeCallbacks(batteryMonitorRunnable)
        stopSoundLoop()
        soundPool.release()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
