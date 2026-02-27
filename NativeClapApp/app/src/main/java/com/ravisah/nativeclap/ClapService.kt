package com.ravisah.nativeclap

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.log10

class ClapService : Service() {

    private val CHANNEL_ID = "ClapServiceChannel"
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var listenThread: Thread? = null

    // Audio Configuration
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val CLAP_THRESHOLD_DB = 88.0 // Increased to prevent false positives

    private val stopAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopAlarm()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(stopAlarmReceiver, IntentFilter("com.ravisah.nativeclap.STOP_ALARM"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopAlarmReceiver, IntentFilter("com.ravisah.nativeclap.STOP_ALARM"))
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NativeClapApp::MicWakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification("Listening for claps..."))
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (isListening) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isListening = true

        val startTime = System.currentTimeMillis()

        listenThread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isListening) {
                val readResult = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (readResult > 0 && mediaPlayer?.isPlaying != true) {
                    
                    // Ignore the first 1.5 seconds to avoid detecting the physical "Start Service" button tap
                    if (System.currentTimeMillis() - startTime < 1500) {
                        continue
                    }

                    var maxAmplitude = 0
                    for (i in 0 until readResult) {
                        if (Math.abs(buffer[i].toInt()) > maxAmplitude) {
                            maxAmplitude = Math.abs(buffer[i].toInt())
                        }
                    }

                    // Convert amplitude to dB (simplified)
                    val db = if (maxAmplitude > 0) 20 * log10(maxAmplitude.toDouble()) else 0.0

                    if (db > CLAP_THRESHOLD_DB) {
                        triggerAlarm()
                    }
                }
            }
        }
        listenThread?.start()
    }

    private fun triggerAlarm() {
        isListening = false
        audioRecord?.stop()
        
        // Update notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification("🚨 ALARM RINGING! 🚨"))

        mediaPlayer = MediaPlayer.create(this, R.raw.alarm)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification("Listening for claps..."))

        // Resume listening
        isListening = true
        audioRecord?.startRecording()
    }

    private fun createNotification(bodyText: String): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Native Clap Shield")
            .setContentText(bodyText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Clap Detection Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        unregisterReceiver(stopAlarmReceiver)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
