package com.automation.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.automation.agent.services.ControllerService
import com.automation.agent.utils.DeviceInfo
import com.automation.agent.utils.RootUtils
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        initViews()
        
        // Initialize utilities
        deviceInfo = DeviceInfo(this)
        rootUtils = RootUtils()
        
        // Check and request permissions
        checkPermissions()
        
        // Display device info
        displayDeviceInfo()
        
        // Check root status
        checkRootStatus()
        
        // Start service automatically
        startControllerService()
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

    private fun checkRootStatus() {
        val hasRoot = rootUtils.isRootAvailable()
        rootStatusText.text = if (hasRoot) {
            "Root: ✓ Доступен"
        } else {
            "Root: ✗ Недоступен"
        }
        rootStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasRoot) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
