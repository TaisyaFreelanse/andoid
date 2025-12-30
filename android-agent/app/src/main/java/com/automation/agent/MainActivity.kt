package com.automation.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.automation.agent.services.ControllerService
import com.automation.agent.utils.DeviceInfo
import com.automation.agent.utils.RootUtils
import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Activity for Android Automation Agent
 * 
 * This activity serves as the entry point and provides UI for monitoring
 * the agent status. The actual automation work is done by ControllerService.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1002
    }

    private lateinit var statusText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var lastHeartbeatText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var startStopButton: Button

    private lateinit var deviceInfo: DeviceInfo
    private lateinit var rootUtils: RootUtils
    
    private var controllerService: ControllerService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Service is running as foreground, we don't bind to it
            // This is just for status updates
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controllerService = null
            isServiceBound = false
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // CRITICAL: Log immediately BEFORE anything else
        android.util.Log.e("MainActivity", "=== MainActivity.onCreate() STARTED ===")
        android.util.Log.e("MainActivity", "Thread: ${Thread.currentThread().name}")
        android.util.Log.e("MainActivity", "Intent: ${intent?.action}")
        try {
            android.util.Log.e("MainActivity", "Calling super.onCreate()...")
            super.onCreate(savedInstanceState)
            android.util.Log.e("MainActivity", "super.onCreate() completed")
            android.util.Log.e("MainActivity", "onCreate: Starting")
            
            try {
                setContentView(R.layout.activity_main)
                android.util.Log.e("MainActivity", "onCreate: Layout set")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to set layout: ${e.message}", e)
                // Show error message instead of crashing
                setContentView(android.R.layout.simple_list_item_1)
                val textView = findViewById<android.widget.TextView>(android.R.id.text1)
                textView?.text = "Ошибка загрузки интерфейса: ${e.message}"
                return
            }

            // Initialize views
            try {
                initViews()
                android.util.Log.e("MainActivity", "onCreate: Views initialized")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to init views: ${e.message}", e)
                // Continue anyway
            }
            
            // Initialize utilities
            try {
                deviceInfo = DeviceInfo(this)
                rootUtils = RootUtils()
                android.util.Log.e("MainActivity", "onCreate: Utilities initialized")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to init utilities: ${e.message}", e)
                // Continue anyway
            }
            
            // Check and request permissions
            try {
                checkPermissions()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to check permissions: ${e.message}", e)
            }
            
            // Check overlay permission for WebView
            try {
                checkOverlayPermission()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to check overlay: ${e.message}", e)
            }
            
            // Display device info
            try {
                displayDeviceInfo()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to display device info: ${e.message}", e)
            }
            
            // Check root status asynchronously (to avoid blocking UI)
            try {
                checkRootStatusAsync()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to check root: ${e.message}", e)
            }
            
            // Start service automatically
            try {
                startControllerService()
                android.util.Log.e("MainActivity", "onCreate: Service started")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onCreate: Failed to start service: ${e.message}", e)
            }
            
            android.util.Log.e("MainActivity", "onCreate: Completed successfully")
            android.util.Log.e("MainActivity", "=== MainActivity.onCreate() COMPLETED SUCCESSFULLY ===")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "=== CRITICAL EXCEPTION in MainActivity.onCreate() ===")
            android.util.Log.e("MainActivity", "Error: ${e.message}", e)
            android.util.Log.e("MainActivity", "Exception class: ${e.javaClass.name}")
            e.printStackTrace()
            // Try to show error screen
            try {
                super.onCreate(savedInstanceState)
                setContentView(android.R.layout.simple_list_item_1)
                val textView = findViewById<android.widget.TextView>(android.R.id.text1)
                textView?.text = "Ошибка запуска: ${e.message}"
            } catch (e2: Exception) {
                android.util.Log.e("MainActivity", "CRITICAL: Even error screen failed: ${e2.message}", e2)
            }
        }
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        lastHeartbeatText = findViewById(R.id.lastHeartbeatText)
        rootStatusText = findViewById(R.id.rootStatusText)
        startStopButton = findViewById(R.id.startStopButton)
        
        startStopButton.setOnClickListener {
            toggleService()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Для работы автоматизации требуется разрешение 'Поверх других приложений'",
                    Toast.LENGTH_LONG
                ).show()
                
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Разрешение на overlay получено!", Toast.LENGTH_SHORT).show()
                    // Restart service to apply new permissions
                    stopControllerService()
                    startControllerService()
                } else {
                    Toast.makeText(
                        this,
                        "Разрешение не получено. Автоматизация может работать некорректно.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // Internet - always granted for INTERNET permission
        
        // Location
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Storage (for older Android versions)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.filterIndexed { index, _ -> 
                grantResults[index] != PackageManager.PERMISSION_GRANTED 
            }
            
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Некоторые разрешения не предоставлены. Функционал может быть ограничен.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun displayDeviceInfo() {
        val info = StringBuilder()
        info.append("Model: ${deviceInfo.getModel()}\n")
        info.append("Manufacturer: ${deviceInfo.getManufacturer()}\n")
        info.append("Android: ${deviceInfo.getVersion()} (SDK ${deviceInfo.getSdkVersion()})\n")
        info.append("Android ID: ${deviceInfo.getAndroidId().take(8)}...\n")
        info.append("Timezone: ${deviceInfo.getTimezone()}\n")
        
        val resolution = deviceInfo.getScreenResolution()
        info.append("Screen: ${resolution.first}x${resolution.second}")
        
        deviceInfoText.text = info.toString()
    }

    private fun checkRootStatusAsync() {
        // Show loading state
        rootStatusText.text = "Root: Проверка..."
        rootStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        
        // Run check in background thread
        Thread {
            try {
                android.util.Log.d("MainActivity", "=== Checking root status (async) ===")
                
                // Check if root was already granted
                val rootGranted = try {
                    Shell.isAppGrantedRoot() == true
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Error checking libsu: ${e.message}")
                    false
                }
                android.util.Log.d("MainActivity", "libsu granted: $rootGranted")
                
                val hasRoot = rootUtils.isRootAvailable()
                android.util.Log.d("MainActivity", "Root available: $hasRoot")
                
                // Update UI on main thread
                runOnUiThread {
                    val statusText = when {
                        rootGranted -> {
                            android.util.Log.i("MainActivity", "Root status: ✓ Доступен (granted)")
                            "Root: ✓ Доступен"
                        }
                        hasRoot -> {
                            android.util.Log.w("MainActivity", "Root status: ⚠ Доступен (требуется разрешение)")
                            "Root: ⚠ Доступен (требуется разрешение)"
                        }
                        else -> {
                            android.util.Log.w("MainActivity", "Root status: ✗ Недоступен")
                            "Root: ✗ Недоступен"
                        }
                    }
                    
                    rootStatusText.text = statusText
                    
                    rootStatusText.setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            when {
                                rootGranted -> android.R.color.holo_green_dark
                                hasRoot -> android.R.color.holo_orange_dark
                                else -> android.R.color.holo_red_dark
                            }
                        )
                    )
                    
                    // Make root status clickable to re-check
                    rootStatusText.setOnClickListener {
                        android.util.Log.d("MainActivity", "Root status clicked - re-checking...")
                        checkRootStatusAsync()
                    }
                    
                    android.util.Log.d("MainActivity", "=== Root status check complete ===")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error checking root: ${e.message}", e)
                runOnUiThread {
                    rootStatusText.text = "Root: Ошибка проверки"
                    rootStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                }
            }
        }.start()
    }
    
    private fun checkRootStatus() {
        // Synchronous version for compatibility
        checkRootStatusAsync()
    }

    private fun startControllerService() {
        val serviceIntent = Intent(this, ControllerService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        updateUI()
    }

    private fun stopControllerService() {
        val serviceIntent = Intent(this, ControllerService::class.java)
        stopService(serviceIntent)
        updateUI()
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            stopControllerService()
            startStopButton.text = "Запустить"
        } else {
            startControllerService()
            startStopButton.text = "Остановить"
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (ControllerService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateUI() {
        val isRunning = isServiceRunning()
        
        statusText.text = if (isRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }
        
        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (isRunning) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        
        startStopButton.text = if (isRunning) "Остановить" else "Запустить"
        
        // Update connection status
        connectionStatusText.text = if (isRunning) {
            "Соединение: Активно"
        } else {
            "Соединение: Отключено"
        }
        
        // Update heartbeat
        lastHeartbeatText.text = "Последний heartbeat: --"
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        // Re-check root status in case user granted root permissions
        checkRootStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
