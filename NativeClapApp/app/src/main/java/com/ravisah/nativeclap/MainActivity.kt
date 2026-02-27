package com.ravisah.nativeclap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var btnStopAlarm: Button
    private lateinit var tvStatus: TextView
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnStopAlarm = findViewById(R.id.btnStopAlarm)

        checkPermissions()

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopClapService()
            } else {
                startClapService()
            }
        }

        btnStopAlarm.setOnClickListener {
            // Send broadcast to Service to stop alarm
            val intent = Intent("com.ravisah.nativeclap.STOP_ALARM")
            sendBroadcast(intent)
            btnStopAlarm.visibility = Button.GONE
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun startClapService() {
        val intent = Intent(this, ClapService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        tvStatus.text = "Status: Shield Active \uD83D\uDEE1\uFE0F\uD83D\uDC42"
        btnToggle.text = "Stop Service"
    }

    private fun stopClapService() {
        val intent = Intent(this, ClapService::class.java)
        stopService(intent)
        isServiceRunning = false
        tvStatus.text = "Status: Inactive \uD83D\uDE34"
        btnToggle.text = "Start Service"
        btnStopAlarm.visibility = Button.GONE
    }
}
