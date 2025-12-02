package com.automation.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.automation.agent.MainActivity
import com.automation.agent.R
import com.automation.agent.network.ApiClient
import com.automation.agent.network.ProxyManager
import com.automation.agent.utils.DeviceInfo
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ControllerService - Background service for device registration and heartbeat
 * 
 * Responsibilities:
 * - Register device with backend on first launch
 * - Send heartbeat every 30 seconds
 * - Auto-reconnect on connection loss
 * - Fetch and queue tasks for execution
 */
class ControllerService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "automation_agent_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "agent_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AGENT_TOKEN = "agent_token"
        private const val KEY_IS_REGISTERED = "is_registered"
        
        // Backend URL - configurable
        private const val DEFAULT_BACKEND_URL = "https://android-automation-backend.onrender.com"
        
        // Heartbeat interval
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 seconds
        
        // Retry settings
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 1_000L // 1 second
        private const val MAX_RETRY_DELAY_MS = 60_000L // 1 minute
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var deviceInfo: DeviceInfo
    private lateinit var apiClient: ApiClient
    private lateinit var proxyManager: ProxyManager
    private lateinit var taskExecutor: TaskExecutor
    
    private var deviceId: String? = null
    private val isRegistered = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    
    private var heartbeatJob: Job? = null
    private var taskPollingJob: Job? = null
    private var taskExecutionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Task queue
    private val pendingTasks = mutableListOf<ApiClient.TaskResponse>()

    // Callbacks for UI updates
    var onStatusChanged: ((ServiceStatus) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    data class ServiceStatus(
        val isRunning: Boolean,
        val isRegistered: Boolean,
        val deviceId: String?,
        val lastHeartbeat: Long?,
        val connectionStatus: ConnectionStatus
    )

    enum class ConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR
    }

    private var lastHeartbeatTime: Long? = null
    private var connectionStatus = ConnectionStatus.DISCONNECTED

    override fun onCreate() {
        super.onCreate()
        
        // Initialize components
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceInfo = DeviceInfo(this)
        proxyManager = ProxyManager()
        apiClient = ApiClient(getBackendUrl(), proxyManager)
        taskExecutor = TaskExecutor(this, apiClient, proxyManager)
        
        // Setup task executor callbacks
        setupTaskExecutorCallbacks()
        
        // Load saved device ID and token
        deviceId = prefs.getString(KEY_DEVICE_ID, null)
        isRegistered.set(prefs.getBoolean(KEY_IS_REGISTERED, false))
        
        // Load and set auth token
        prefs.getString(KEY_AGENT_TOKEN, null)?.let { 
            apiClient.setAuthToken(it) 
        }
        
        // Create notification channel
        createNotificationChannel()
        
        // Acquire wake lock to keep service running
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Запуск..."))
        
        if (!isRunning.getAndSet(true)) {
            // Start registration and heartbeat
            lifecycleScope.launch {
                startAgent()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        
        isRunning.set(false)
        heartbeatJob?.cancel()
        taskPollingJob?.cancel()
        releaseWakeLock()
        
        updateStatus(ConnectionStatus.DISCONNECTED)
    }

    /**
     * Main agent startup sequence
     */
    private suspend fun startAgent() {
        updateStatus(ConnectionStatus.CONNECTING)
        
        // Step 1: Register device if not registered
        if (!isRegistered.get() || deviceId == null) {
            val registered = registerDeviceWithRetry()
            if (!registered) {
                updateStatus(ConnectionStatus.ERROR)
                onError?.invoke("Не удалось зарегистрировать устройство")
                return
            }
        }
        
        // Step 2: Start heartbeat
        startHeartbeat()
        
        // Step 3: Start task polling
        startTaskPolling()
        
        updateStatus(ConnectionStatus.CONNECTED)
    }

    /**
     * Register device with backend (with retry logic)
     */
    private suspend fun registerDeviceWithRetry(): Boolean {
        var attempt = 0
        var delay = INITIAL_RETRY_DELAY_MS
        
        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++
            
            updateNotification("Регистрация устройства (попытка $attempt)...")
            
            try {
                val success = registerDevice()
                if (success) {
                    return true
                }
            } catch (e: Exception) {
                onError?.invoke("Ошибка регистрации: ${e.message}")
            }
            
            // Exponential backoff
            if (attempt < MAX_RETRY_ATTEMPTS) {
                delay(delay)
                delay = minOf(delay * 2, MAX_RETRY_DELAY_MS)
            }
        }
        
        return false
    }

    /**
     * Register device with backend
     */
    private suspend fun registerDevice(): Boolean {
        val aaid = try {
            deviceInfo.getAaid()
        } catch (e: Exception) {
            ""
        }
        
        val request = ApiClient.DeviceRegistrationRequest(
            androidId = deviceInfo.getAndroidId(),
            aaid = aaid,
            model = deviceInfo.getModel(),
            manufacturer = deviceInfo.getManufacturer(),
            version = deviceInfo.getVersion(),
            userAgent = deviceInfo.getUserAgent()
        )
        
        val response = apiClient.registerDevice(request)
        
        return if (response != null && response.deviceId.isNotEmpty()) {
            // Save device ID and token
            deviceId = response.deviceId
            isRegistered.set(true)
            
            // Set auth token in API client (prefer agentToken, fallback to token)
            val authToken = response.agentToken ?: response.token
            authToken?.let { apiClient.setAuthToken(it) }
            
            prefs.edit()
                .putString(KEY_DEVICE_ID, response.deviceId)
                .putString(KEY_AGENT_TOKEN, authToken)
                .putBoolean(KEY_IS_REGISTERED, true)
                .apply()
            
            updateNotification("Устройство зарегистрировано")
            true
        } else {
            false
        }
    }

    /**
     * Start periodic heartbeat
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        
        heartbeatJob = lifecycleScope.launch {
            while (isActive && isRunning.get()) {
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    onError?.invoke("Ошибка heartbeat: ${e.message}")
                    updateStatus(ConnectionStatus.ERROR)
                    
                    // Try to reconnect
                    reconnect()
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Send heartbeat to backend and process tasks from response
     */
    private suspend fun sendHeartbeat() {
        val id = deviceId ?: return
        
        val response = apiClient.sendHeartbeat(id, ApiClient.HeartbeatStatus.ONLINE)
        
        if (response != null) {
            lastHeartbeatTime = System.currentTimeMillis()
            updateStatus(ConnectionStatus.CONNECTED)
            
            // Process tasks from heartbeat response
            response.tasks?.let { taskList ->
                if (taskList.isNotEmpty()) {
                    updateNotification("Получено задач: ${taskList.size}")
                    
                    // Add new tasks to queue
                    taskList.forEach { task ->
                        if (!pendingTasks.any { it.id == task.id }) {
                            pendingTasks.add(task)
                        }
                    }
                    
                    // Start executing if not already
                    if (!taskExecutor.isExecutingTask() && pendingTasks.isNotEmpty()) {
                        executeNextTask()
                    }
                } else {
                    updateNotification("Подключено • Heartbeat OK")
                }
            } ?: updateNotification("Подключено • Heartbeat OK")
        } else {
            throw Exception("Heartbeat failed")
        }
    }

    /**
     * Start task polling
     */
    private fun startTaskPolling() {
        taskPollingJob?.cancel()
        
        taskPollingJob = lifecycleScope.launch {
            while (isActive && isRunning.get()) {
                try {
                    pollTasks()
                } catch (e: Exception) {
                    onError?.invoke("Ошибка получения задач: ${e.message}")
                }
                
                // Poll every 5 seconds
                delay(5_000)
            }
        }
    }

    /**
     * Poll for new tasks
     */
    private suspend fun pollTasks() {
        val id = deviceId ?: return
        
        // Don't poll if already executing a task
        if (taskExecutor.isExecutingTask()) {
            return
        }
        
        val tasks = apiClient.getTasks(id)
        
        tasks?.let { taskList ->
            // Filter pending tasks
            val newTasks = taskList.filter { task ->
                task.status == "pending" && !pendingTasks.any { it.id == task.id }
            }
            
            // Add to queue (sorted by priority)
            pendingTasks.addAll(newTasks.sortedByDescending { it.priority })
            
            // Execute next task if not busy
            executeNextTask()
        }
    }
    
    /**
     * Execute next task from queue
     */
    private fun executeNextTask() {
        if (taskExecutor.isExecutingTask() || pendingTasks.isEmpty()) {
            return
        }
        
        val task = pendingTasks.removeAt(0)
        
        taskExecutionJob = lifecycleScope.launch {
            try {
                updateNotification("Выполнение задачи: ${task.name}")
                
                val taskConfig = convertToTaskConfig(task)
                val result = taskExecutor.executeTask(taskConfig)
                
                if (result.success) {
                    updateNotification("Задача выполнена: ${task.name}")
                } else {
                    updateNotification("Ошибка задачи: ${result.error}")
                    onError?.invoke("Ошибка задачи ${task.name}: ${result.error}")
                }
                
            } catch (e: Exception) {
                onError?.invoke("Ошибка выполнения задачи: ${e.message}")
            }
            
            // Execute next task after completion
            delay(1000) // Small delay between tasks
            executeNextTask()
        }
    }
    
    /**
     * Convert API task response to TaskExecutor config
     */
    private fun convertToTaskConfig(task: ApiClient.TaskResponse): TaskExecutor.TaskConfig {
        val steps = task.steps?.map { step ->
            TaskExecutor.TaskStep(
                type = step.type,
                config = step.config
            )
        } ?: emptyList()
        
        return TaskExecutor.TaskConfig(
            id = task.id,
            name = task.name,
            type = task.type,
            browser = task.config?.get("browser") as? String ?: "webview",
            proxy = task.config?.get("proxy") as? String,
            steps = steps,
            maxRetries = task.config?.get("maxRetries") as? Int ?: 3,
            continueOnError = task.config?.get("continueOnError") as? Boolean ?: false
        )
    }
    
    /**
     * Setup task executor callbacks
     */
    private fun setupTaskExecutorCallbacks() {
        taskExecutor.onTaskProgress = { taskId, current, total ->
            updateNotification("Задача $taskId: шаг $current/$total")
        }
        
        taskExecutor.onTaskCompleted = { taskId, result ->
            if (result.success) {
                updateNotification("Подключено • Задача $taskId выполнена")
            } else {
                updateNotification("Подключено • Ошибка: ${result.error}")
            }
        }
        
        // Error handling is done through task callbacks
    }
    

    /**
     * Reconnect after connection loss
     */
    private suspend fun reconnect() {
        updateNotification("Переподключение...")
        updateStatus(ConnectionStatus.CONNECTING)
        
        var attempt = 0
        var delay = INITIAL_RETRY_DELAY_MS
        
        while (attempt < MAX_RETRY_ATTEMPTS && isRunning.get()) {
            attempt++
            
            try {
                sendHeartbeat()
                updateNotification("Подключено")
                updateStatus(ConnectionStatus.CONNECTED)
                return
            } catch (e: Exception) {
                // Continue retrying
            }
            
            delay(delay)
            delay = minOf(delay * 2, MAX_RETRY_DELAY_MS)
        }
        
        // Failed to reconnect - try re-registration
        if (isRunning.get()) {
            isRegistered.set(false)
            startAgent()
        }
    }

    /**
     * Create notification channel (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Android Automation Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый сервис автоматизации"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground notification
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Automation Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Update notification text
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    /**
     * Update service status
     */
    private fun updateStatus(status: ConnectionStatus) {
        connectionStatus = status
        
        onStatusChanged?.invoke(
            ServiceStatus(
                isRunning = isRunning.get(),
                isRegistered = isRegistered.get(),
                deviceId = deviceId,
                lastHeartbeat = lastHeartbeatTime,
                connectionStatus = status
            )
        )
    }

    /**
     * Acquire wake lock to keep CPU running
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutomationAgent::ServiceWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    /**
     * Get backend URL from preferences or default
     */
    private fun getBackendUrl(): String {
        return prefs.getString("backend_url", DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    /**
     * Set backend URL
     */
    fun setBackendUrl(url: String) {
        prefs.edit().putString("backend_url", url).apply()
        apiClient = ApiClient(url, proxyManager)
    }

    /**
     * Get current device ID
     */
    override fun getDeviceId(): String? = deviceId

    /**
     * Get registration status
     */
    fun isDeviceRegistered(): Boolean = isRegistered.get()

    /**
     * Force re-registration
     */
    fun forceReRegister() {
        lifecycleScope.launch {
            isRegistered.set(false)
            prefs.edit()
                .remove(KEY_DEVICE_ID)
                .putBoolean(KEY_IS_REGISTERED, false)
                .apply()
            deviceId = null
            
            startAgent()
        }
    }
}
