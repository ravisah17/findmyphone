package com.ravisah.nativeclap

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleContainer: android.widget.FrameLayout
    private lateinit var tvToggleText: TextView
    private lateinit var btnStopAlarm: Button
    private lateinit var btnSelectAlarm: Button
    private lateinit var tvStatus: TextView
    private var isServiceRunning = false

    private val pickRingtoneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val prefs = getSharedPreferences("NativeClapPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("alarmUri", uri.toString()).apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Stop the alarm when the user opens the app (Guaranteed Android 14 delivery)
        if (isServiceRunning) {
            val intent = Intent(this, ClapService::class.java)
            intent.putExtra("ACTION", "STOP_ALARM")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggleContainer = findViewById(R.id.btnToggleContainer)
        tvToggleText = findViewById(R.id.tvToggleText)
        btnStopAlarm = findViewById(R.id.btnStopAlarm)
        btnSelectAlarm = findViewById(R.id.btnSelectAlarm)

        checkPermissions()

        btnSelectAlarm.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            pickRingtoneLauncher.launch(intent)
        }

        btnToggleContainer.setOnClickListener {
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
        btnToggleContainer.setBackgroundResource(R.drawable.circle_button_on)
        tvToggleText.text = "STOP"
    }

    private fun stopClapService() {
        val intent = Intent(this, ClapService::class.java)
        stopService(intent)
        isServiceRunning = false
        tvStatus.text = "Status: Inactive \uD83D\uDE34"
        btnToggleContainer.setBackgroundResource(R.drawable.circle_button_off)
        tvToggleText.text = "START"
        btnStopAlarm.visibility = Button.GONE
    }
}
