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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.automation.agent.MainActivity
import com.automation.agent.R
import com.automation.agent.network.ApiClient
import com.automation.agent.network.ProxyManager
import com.automation.agent.utils.AutoLogcatSender
import com.automation.agent.utils.DeviceInfo
import com.automation.agent.utils.LogInterceptor
import com.automation.agent.utils.RootUtils
import com.topjohnwu.superuser.Shell
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
        private const val TAG = "ControllerService"
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
    private lateinit var uniquenessService: UniquenessService
    private lateinit var rootUtils: RootUtils
    private var autoLogcatSender: AutoLogcatSender? = null
    
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
        try {
            LogInterceptor.i(TAG, "ControllerService.onCreate() started")
            super.onCreate()
            LogInterceptor.i(TAG, "ControllerService super.onCreate() completed")
            
            // Register with Application for exception handling
            try {
                (application as? com.automation.agent.App)?.let { app ->
                    Log.d(TAG, "Application instance found")
                    // Will be set after apiClient is created
                } ?: Log.w(TAG, "Application is not App instance")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register with App: ${e.message}", e)
            }
            
            // Initialize components step by step with error handling
            Log.i(TAG, "Initializing SharedPreferences...")
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Log.i(TAG, "SharedPreferences initialized")
            
            Log.i(TAG, "Initializing DeviceInfo...")
            deviceInfo = DeviceInfo(this)
            Log.i(TAG, "DeviceInfo initialized")
            
            Log.i(TAG, "Initializing ProxyManager...")
            proxyManager = ProxyManager()
            Log.i(TAG, "ProxyManager initialized")
            
            Log.i(TAG, "Initializing ApiClient...")
            apiClient = ApiClient(getBackendUrl(), proxyManager)
            Log.i(TAG, "ApiClient initialized")
            
            // Initialize LogInterceptor for automatic log forwarding (works without root)
            Log.i(TAG, "Initializing LogInterceptor...")
            try {
                LogInterceptor.init(apiClient, null)
                Log.i(TAG, "LogInterceptor initialized - all logs will be forwarded to backend")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize LogInterceptor: ${e.message}", e)
            }
            
            // Initialize AutoLogcatSender for additional logcat reading (requires root)
            Log.i(TAG, "Initializing AutoLogcatSender...")
            try {
                autoLogcatSender = AutoLogcatSender(apiClient, null)
                // Add important tags to filter
                autoLogcatSender?.addFilterTag("ControllerService")
                autoLogcatSender?.addFilterTag("UniquenessService")
                autoLogcatSender?.addFilterTag("TaskExecutor")
                autoLogcatSender?.addFilterTag("App")
                autoLogcatSender?.addFilterTag("MainActivity")
                autoLogcatSender?.addFilterTag("AutoLogcatSender")
                Log.i(TAG, "AutoLogcatSender initialized (will work on root devices)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AutoLogcatSender: ${e.message}", e)
            }
            
            Log.i(TAG, "Initializing TaskExecutor...")
            taskExecutor = TaskExecutor(this, apiClient, proxyManager)
            Log.i(TAG, "TaskExecutor initialized")
            
            Log.i(TAG, "Initializing RootUtils...")
            rootUtils = RootUtils()
            Log.i(TAG, "RootUtils initialized")
            
            Log.i(TAG, "Initializing UniquenessService...")
            try {
                uniquenessService = UniquenessService(this, rootUtils)
                Log.i(TAG, "UniquenessService initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to initialize UniquenessService: ${e.message}", e)
                // Don't crash - set to null and handle later
                throw e // Re-throw to prevent service from starting in broken state
            }
        
        // Register API client with Application
        try {
            (application as? com.automation.agent.App)?.setApiClient(apiClient)
            // Also register with LogInterceptor and AutoLogcatSender
            LogInterceptor.setApiClient(apiClient)
            autoLogcatSender?.setApiClient(apiClient)
            deviceId?.let { (application as? com.automation.agent.App)?.setDeviceId(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register API client: ${e.message}")
        }
        
            // Setup task executor callbacks
            Log.i(TAG, "Setting up task executor callbacks...")
            setupTaskExecutorCallbacks()
            Log.i(TAG, "Task executor callbacks set up")
            
            // Load saved device ID and token
            Log.i(TAG, "Loading saved device ID and token...")
            deviceId = prefs.getString(KEY_DEVICE_ID, null)
            isRegistered.set(prefs.getBoolean(KEY_IS_REGISTERED, false))
            Log.i(TAG, "Device ID loaded: ${deviceId?.take(8)}..., isRegistered: ${isRegistered.get()}")
            
            // Set device ID in API client and TaskExecutor
            deviceId?.let { 
                apiClient.setDeviceId(it)
                taskExecutor.setDeviceId(it)
                Log.i(TAG, "Device ID set in API client and TaskExecutor")
            }
            
            // Load and set auth token
            prefs.getString(KEY_AGENT_TOKEN, null)?.let { 
                apiClient.setAuthToken(it)
                Log.i(TAG, "Auth token set in API client")
            }
            
            // Create notification channel
            Log.i(TAG, "Creating notification channel...")
            createNotificationChannel()
            Log.i(TAG, "Notification channel created")
            
            // Acquire wake lock to keep service running
            Log.i(TAG, "Acquiring wake lock...")
            acquireWakeLock()
            Log.i(TAG, "Wake lock acquired")
            
            Log.i(TAG, "ControllerService.onCreate() completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Exception in ControllerService.onCreate(): ${e.message}", e)
            e.printStackTrace()
            // Try to send error to backend if possible
            try {
                safeSendLog("error", TAG, "CRITICAL: Service onCreate failed: ${e.message}\n${e.stackTraceToString().take(500)}")
            } catch (logError: Exception) {
                Log.w(TAG, "Failed to send error log: ${logError.message}")
            }
            // Re-throw to prevent service from starting in broken state
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Запуск..."))
        
        if (!isRunning.getAndSet(true)) {
            // Start AutoLogcatSender for automatic log forwarding
            try {
                autoLogcatSender?.start()
                Log.i(TAG, "AutoLogcatSender started")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start AutoLogcatSender: ${e.message}", e)
            }
            
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
        // Stop AutoLogcatSender
        try {
            autoLogcatSender?.stop()
            Log.i(TAG, "AutoLogcatSender stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop AutoLogcatSender: ${e.message}")
        }
        
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
        
        // Get existing deviceId if available (for re-registration after reinstall)
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        
        // Check root status with detailed information for logging and sending to backend
        val rootCheck = rootUtils.checkRootDetailed()
        val isRooted = rootCheck.isAvailable
        val rootGranted = rootCheck.isGranted
        
        Log.i(TAG, "Device registration - Root check: available=$isRooted, granted=$rootGranted")
        Log.i(TAG, "Root check details: ${rootCheck.details}")
        Log.i(TAG, "Root check methods: ${rootCheck.checkMethods.joinToString(" | ")}")
        if (rootCheck.foundSuPath != null) {
            Log.i(TAG, "Found su path: ${rootCheck.foundSuPath}")
        }
        
        // Ensure we have root check details
        val rootDetails = rootCheck.details ?: "Root check not performed"
        val rootMethods = rootCheck.checkMethods.joinToString(" | ") ?: "No methods tried"
        
        Log.i(TAG, "=== Creating DeviceRegistrationRequest ===")
        Log.i(TAG, "isRooted: $isRooted (type: ${isRooted::class.java.simpleName})")
        Log.i(TAG, "rootCheckDetails: $rootDetails")
        Log.i(TAG, "rootCheckMethods: $rootMethods")
        
        val request = ApiClient.DeviceRegistrationRequest(
            androidId = deviceInfo.getAndroidId(),
            aaid = aaid,
            model = deviceInfo.getModel(),
            manufacturer = deviceInfo.getManufacturer(),
            version = deviceInfo.getVersion(),
            userAgent = deviceInfo.getUserAgent(),
            existingDeviceId = existingId,  // Send existing ID for re-registration
            isRooted = isRooted,  // Send root status to backend
            rootCheckDetails = rootDetails,  // Send detailed root check info
            rootCheckMethods = rootMethods  // Send methods tried
        )
        
        Log.i(TAG, "Request created - isRooted: ${request.isRooted}, rootCheckDetails: ${request.rootCheckDetails}, rootCheckMethods: ${request.rootCheckMethods}")
        Log.d(TAG, "Registering device with root status: $isRooted (granted: $rootGranted)")
        Log.d(TAG, "Root check details sent to backend: $rootDetails")
        
        val response = apiClient.registerDevice(request)
        
        return if (response != null && response.deviceId.isNotEmpty()) {
            // Save device ID and token
            deviceId = response.deviceId
            isRegistered.set(true)
            
            // Set device ID in API client and TaskExecutor
            apiClient.setDeviceId(response.deviceId)
            taskExecutor.setDeviceId(response.deviceId)
            
            // Update LogInterceptor and AutoLogcatSender with device ID
            LogInterceptor.setDeviceId(response.deviceId)
            autoLogcatSender?.setDeviceId(response.deviceId)
            
            // Register device ID with Application for exception handling
            try {
                (application as? com.automation.agent.App)?.setDeviceId(response.deviceId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register device ID: ${e.message}")
            }
            
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
                    Log.i(TAG, "Heartbeat received ${taskList.size} tasks")
                    safeSendLog("info", TAG, "Heartbeat received ${taskList.size} tasks")
                    try {
                        updateNotification("Получено задач: ${taskList.size}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update notification: ${e.message}")
                    }
                    
                    // Add new tasks to queue (accept both "pending" and "assigned" status)
                    taskList.forEach { task ->
                        try {
                            Log.d(TAG, "Processing task: id=${task.id}, type=${task.type}, status=${task.status}")
                            if (!pendingTasks.any { it.id == task.id }) {
                                // Accept tasks with "pending" or "assigned" status
                                if (task.status == "pending" || task.status == "assigned") {
                                    pendingTasks.add(task)
                                    Log.i(TAG, "Added task to queue: ${task.id} (${task.type}, status=${task.status})")
                                    safeSendLog("info", TAG, "Added task to queue: ${task.id} (${task.type}, status=${task.status})")
                                } else {
                                    Log.d(TAG, "Skipping task ${task.id} with status: ${task.status}")
                                }
                            } else {
                                Log.d(TAG, "Task ${task.id} already in queue")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing task ${task.id}: ${e.message}", e)
                            safeSendLog("error", TAG, "Error processing task ${task.id}: ${e.message}")
                        }
                    }
                    
                    Log.i(TAG, "Pending tasks count: ${pendingTasks.size}, isExecuting: ${taskExecutor.isExecutingTask()}")
                    safeSendLog("info", TAG, "Pending tasks count: ${pendingTasks.size}, isExecuting: ${taskExecutor.isExecutingTask()}")
                    
                    // Start executing if not already
                    if (!taskExecutor.isExecutingTask() && pendingTasks.isNotEmpty()) {
                        Log.i(TAG, "Starting task execution...")
                        safeSendLog("info", TAG, "Starting task execution...")
                        try {
                            executeNextTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "CRITICAL: Error in executeNextTask: ${e.message}", e)
                            safeSendLog("error", TAG, "CRITICAL: Error in executeNextTask: ${e.message}\n${e.stackTraceToString().take(500)}")
                        }
                    } else {
                        Log.d(TAG, "Not starting task execution: isExecuting=${taskExecutor.isExecutingTask()}, pendingCount=${pendingTasks.size}")
                    }
                } else {
                    try {
                        updateNotification("Подключено • Heartbeat OK")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update notification: ${e.message}")
                    }
                }
            } ?: run {
                try {
                    updateNotification("Подключено • Heartbeat OK")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update notification: ${e.message}")
                }
            }
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
        LogInterceptor.i(TAG, "executeNextTask() called: isExecuting=${taskExecutor.isExecutingTask()}, pendingCount=${pendingTasks.size}")
        
        if (taskExecutor.isExecutingTask()) {
            LogInterceptor.d(TAG, "Task executor is busy, skipping")
            return
        }
        
        if (pendingTasks.isEmpty()) {
            LogInterceptor.d(TAG, "No pending tasks, skipping")
            return
        }
        
        val task = pendingTasks.removeAt(0)
        LogInterceptor.i(TAG, "=== Starting task execution: ${task.id} (${task.type}, status=${task.status}) ===")
        
        taskExecutionJob = lifecycleScope.launch {
            try {
                LogInterceptor.i(TAG, "=== TASK EXECUTION COROUTINE STARTED ===")
                LogInterceptor.i(TAG, "Task ID: ${task.id}, Type: ${task.type}, Name: ${task.name}")
                
                try {
                    updateNotification("Выполнение задачи: ${task.name} (${task.type})")
                } catch (e: Exception) {
                    LogInterceptor.w(TAG, "Failed to update notification: ${e.message}")
                }
                
                LogInterceptor.i(TAG, "Task execution coroutine started for: ${task.id}")
                
                // Handle different task types
                when (task.type.lowercase()) {
                    "uniqueness" -> {
                        // Execute uniqueness task via UniquenessService
                        // Execute directly in this coroutine with proper error handling
                        LogInterceptor.i(TAG, "=== UNIQUENESS TASK DETECTED ===")
                        LogInterceptor.i(TAG, "About to execute uniqueness task: ${task.id}")
                        
                        try {
                            // Execute in IO dispatcher to prevent blocking main thread
                            LogInterceptor.i(TAG, "Switching to IO dispatcher for uniqueness task...")
                            withContext(Dispatchers.IO) {
                                LogInterceptor.i(TAG, "=== IN IO DISPATCHER ===")
                                LogInterceptor.i(TAG, "Executing uniqueness task in IO dispatcher: ${task.id}")
                                executeUniquenessTask(task)
                                LogInterceptor.i(TAG, "executeUniquenessTask returned successfully")
                            }
                            LogInterceptor.i(TAG, "Uniqueness task execution completed: ${task.id}")
                        } catch (e: Exception) {
                            LogInterceptor.e(TAG, "=== FATAL ERROR IN UNIQUENESS TASK ===")
                            LogInterceptor.e(TAG, "Fatal error executing uniqueness task: ${e.message}", e)
                            e.printStackTrace()
                            
                            // Send error log
                            safeSendLog("error", TAG, "Fatal error in uniqueness task: ${e.message}\n${e.stackTraceToString().take(1000)}")
                            
                            // Update task status
                            try {
                                apiClient.updateTaskStatus(task.id, "failed")
                                val request = ApiClient.TaskResultRequest(
                                    success = false,
                                    data = mapOf(
                                        "fatal_error" to (e.message ?: "Unknown error"),
                                        "stackTrace" to e.stackTraceToString().take(500)
                                    ),
                                    error = e.message ?: "Unknown error",
                                    executionTime = System.currentTimeMillis()
                                )
                                apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                            } catch (sendError: Exception) {
                                LogInterceptor.e(TAG, "Error sending fatal error result: ${sendError.message}", sendError)
                            }
                            
                            updateNotification("Критическая ошибка: ${e.message}")
                            
                            // Don't re-throw - let service continue
                            LogInterceptor.w(TAG, "Uniqueness task failed but service continues")
                        }
                    }
                    "surfing", "parsing", "screenshot" -> {
                        // Execute via TaskExecutor with type-specific handling
                        val taskConfig = convertToTaskConfig(task)
                        val result = taskExecutor.executeTask(taskConfig)
                        
                        if (result.success) {
                            updateNotification("Задача выполнена: ${task.name}")
                        } else {
                            updateNotification("Ошибка задачи: ${result.error}")
                            onError?.invoke("Ошибка задачи ${task.name}: ${result.error}")
                        }
                    }
                    else -> {
                        // Default: treat as surfing
                        val taskConfig = convertToTaskConfig(task)
                        val result = taskExecutor.executeTask(taskConfig)
                        
                        if (!result.success) {
                            onError?.invoke("Ошибка задачи ${task.name}: ${result.error}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                onError?.invoke("Ошибка выполнения задачи: ${e.message}")
                apiClient.updateTaskStatus(task.id, "failed")
            }
            
            // Execute next task after completion
            delay(1000) // Small delay between tasks
            executeNextTask()
        }
    }
    
    /**
     * Execute uniqueness task
     */
    private suspend fun executeUniquenessTask(task: ApiClient.TaskResponse) {
        try {
            LogInterceptor.i(TAG, "=== executeUniquenessTask ENTRY ===")
            LogInterceptor.i(TAG, "Task ID: ${task.id}")
            LogInterceptor.i(TAG, "Task type: ${task.type}")
            LogInterceptor.i(TAG, "Task config keys: ${task.config?.keys}")
            
            // Send log to backend
            safeSendLog("info", TAG, "Starting uniqueness task: ${task.id}")
            
            LogInterceptor.i(TAG, "Updating task status to 'running'...")
            try {
                apiClient.updateTaskStatus(task.id, "running")
                LogInterceptor.i(TAG, "Task status updated to 'running' successfully")
            } catch (e: Exception) {
                LogInterceptor.e(TAG, "Failed to update task status: ${e.message}", e)
            }
            
            // Check if uniquenessService is initialized
            LogInterceptor.i(TAG, "Checking UniquenessService initialization...")
            if (!::uniquenessService.isInitialized) {
                LogInterceptor.e(TAG, "UniquenessService not initialized - attempting to initialize...")
                try {
                    uniquenessService = UniquenessService(this, rootUtils)
                    LogInterceptor.i(TAG, "UniquenessService initialized successfully")
                } catch (e: Exception) {
                    LogInterceptor.e(TAG, "Failed to initialize UniquenessService: ${e.message}", e)
                    throw Exception("UniquenessService not initialized and failed to initialize: ${e.message}")
                }
            } else {
                LogInterceptor.i(TAG, "UniquenessService is already initialized")
            }
            
            // Check and request root access automatically
            LogInterceptor.i(TAG, "Checking root access...")
            val rootCheck = rootUtils.checkRootDetailed()
            LogInterceptor.i(TAG, "Root check: available=${rootCheck.isAvailable}, granted=${rootCheck.isGranted}, details=${rootCheck.details}")
            
            if (!rootCheck.isAvailable) {
                Log.e(TAG, "Device is not rooted")
                val errorMsg = "Device is not rooted. Root access required for uniqueness tasks."
                val request = ApiClient.TaskResultRequest(
                    success = false,
                    data = mapOf("error" to errorMsg, "rootCheck" to rootCheck.details),
                    error = errorMsg,
                    executionTime = System.currentTimeMillis()
                )
                apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                apiClient.updateTaskStatus(task.id, "failed")
                updateNotification("Ошибка: устройство не имеет root")
                return
            }
            
            // Request root access if not granted
            if (!rootCheck.isGranted) {
                Log.i(TAG, "Root not granted, requesting...")
                updateNotification("Запрос root доступа...")
                
                try {
                    // Use libsu to request root - this will show system dialog
                    // Execute in IO dispatcher to avoid blocking main thread
                    val hasRoot = withContext(Dispatchers.IO) {
                        try {
                            val shell = com.topjohnwu.superuser.Shell.getShell()
                            shell.isRoot
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception in Shell.getShell(): ${e.message}", e)
                            false
                        }
                    }
                    
                    if (!hasRoot) {
                        Log.e(TAG, "Failed to get root access after request")
                        val errorMsg = "Root permission denied. Please grant root access to the app."
                        val request = ApiClient.TaskResultRequest(
                            success = false,
                            data = mapOf("error" to errorMsg, "rootCheck" to rootCheck.details),
                            error = errorMsg,
                            executionTime = System.currentTimeMillis()
                        )
                        apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                        apiClient.updateTaskStatus(task.id, "failed")
                        updateNotification("Ошибка: root доступ отклонен")
                        return
                    }
                    
                    Log.i(TAG, "Root access granted successfully")
                    updateNotification("Root доступ получен")
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting root: ${e.message}", e)
                    // Send error log to backend
                    try {
                        safeSendLog("error", TAG, "Failed to request root: ${e.message}\n${e.stackTraceToString().take(500)}")
                    } catch (logError: Exception) {
                        Log.w(TAG, "Failed to send error log: ${logError.message}")
                    }
                    
                    val errorMsg = "Failed to request root access: ${e.message}"
                    val request = ApiClient.TaskResultRequest(
                        success = false,
                        data = mapOf("error" to errorMsg, "exception" to e.stackTraceToString().take(500)),
                        error = errorMsg,
                        executionTime = System.currentTimeMillis()
                    )
                    apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                    apiClient.updateTaskStatus(task.id, "failed")
                    updateNotification("Ошибка запроса root: ${e.message}")
                    return
                }
            } else {
                Log.i(TAG, "Root already granted")
            }
            
            val actions = task.config?.get("actions") as? List<Map<String, Any>> ?: emptyList()
            if (actions.isEmpty()) {
                Log.w(TAG, "No actions found in uniqueness task config")
                val request = ApiClient.TaskResultRequest(
                    success = false,
                    data = mapOf("error" to "No actions found in task config"),
                    error = "No actions found in task config",
                    executionTime = System.currentTimeMillis()
                )
                apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                apiClient.updateTaskStatus(task.id, "failed")
                return
            }
            
            Log.i(TAG, "Found ${actions.size} actions to execute")
            val results = mutableMapOf<String, Any>()
            
            for ((index, action) in actions.withIndex()) {
                try {
                    val actionType = action["type"] as? String ?: continue
                    val actionId = action["id"] as? String ?: "action_${index + 1}"
                    
                    Log.i(TAG, "Executing action $index/${actions.size}: $actionType (id: $actionId)")
                    updateNotification("Уникализация: $actionType ($index/${actions.size})")
                    
                    // Send log to backend
                    try {
                        safeSendLog("info", TAG, "Executing uniqueness action: $actionType (id: $actionId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send log: ${e.message}")
                    }
                    
                    val actionResult = when (actionType) {
                        "regenerate_android_id" -> {
                            uniquenessService.regenerateAndroidId()
                        }
                        "regenerate_aaid" -> {
                            uniquenessService.regenerateAaid()
                        }
                        "clear_chrome_data" -> {
                            uniquenessService.clearChromeData()
                        }
                        "clear_webview_data" -> {
                            uniquenessService.clearWebViewData()
                        }
                        "change_user_agent" -> {
                            val ua = action["ua"] as? String
                            uniquenessService.changeUserAgent(
                                if (ua == "random") null else ua
                            )
                        }
                        "change_timezone" -> {
                            val tz = action["timezone"] as? String
                            val countryCode = action["country_code"] as? String
                            if (tz == "random" || tz == "auto") {
                                val country = countryCode ?: "US"
                                uniquenessService.changeTimezoneByCountry(country)
                            } else if (tz != null) {
                                uniquenessService.changeTimezone(tz)
                            } else {
                                false
                            }
                        }
                        "change_location" -> {
                            val lat = action["latitude"]
                            val lng = action["longitude"]
                            val countryCode = action["country_code"] as? String
                            
                            if (lat == "auto" || lng == "auto") {
                                val country = countryCode ?: "US"
                                uniquenessService.changeLocationByCountry(country)
                            } else if (lat is Number && lng is Number) {
                                uniquenessService.changeLocation(lat.toDouble(), lng.toDouble())
                            } else {
                                false
                            }
                        }
                        "modify_build_prop" -> {
                            @Suppress("UNCHECKED_CAST")
                            val params = action["params"] as? Map<String, String> ?: emptyMap()
                            uniquenessService.modifyBuildProp(params)
                        }
                        "detect_proxy_location" -> {
                            // This action doesn't return boolean, handle separately
                            Log.i(TAG, "detect_proxy_location action - skipping (not implemented)")
                            true
                        }
                        "quick_reset" -> {
                            val result = uniquenessService.quickReset()
                            results["quick_reset"] = result.success
                            results.putAll(result.results.mapValues { it.value.toString() })
                            result.success
                        }
                        "full_reset" -> {
                            val country = action["country"] as? String
                            val result = uniquenessService.fullReset(country)
                            results["full_reset"] = result.success
                            results.putAll(result.results.mapValues { it.value.toString() })
                            result.success
                        }
                        else -> {
                            Log.w(TAG, "Unknown action type: $actionType")
                            results["${actionId}_error"] = "Unknown action type: $actionType"
                            false
                        }
                    }
                    
                    results[actionId] = actionResult
                    Log.i(TAG, "Action $actionId completed: $actionResult")
                    
                    // Send success log
                    safeSendLog("info", TAG, "Uniqueness action completed: $actionId = $actionResult")
                    
                } catch (e: Exception) {
                    val actionId = action["id"] as? String ?: "action_${index + 1}"
                    Log.e(TAG, "Error executing action $actionId: ${e.message}", e)
                    
                    // Send error log to backend
                    safeSendLog("error", TAG, "Uniqueness action failed: $actionId - ${e.message}")
                    
                    results["${actionId}_error"] = e.message ?: "Unknown error"
                    results[actionId] = false
                }
            }
            
            // Send result to backend
            val success = results.values.filterIsInstance<Boolean>().all { it } && 
                         results.values.filterIsInstance<String>().none { it.contains("error") }
            
            Log.i(TAG, "Uniqueness task completed: success=$success, results=${results.keys}")
            
            // Send completion log
            safeSendLog("info", TAG, "Uniqueness task completed: success=$success, actions=${results.size}")
            
            val request = ApiClient.TaskResultRequest(
                success = success,
                data = results,
                error = if (!success) "Some uniqueness actions failed. Check results for details." else null,
                executionTime = System.currentTimeMillis()
            )
            
            try {
                apiClient.sendTaskResult(task.id, request, deviceId ?: "")
                apiClient.updateTaskStatus(task.id, if (success) "completed" else "failed")
                updateNotification("Уникализация завершена: ${if (success) "успешно" else "с ошибками"}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending task result: ${e.message}", e)
                onError?.invoke("Ошибка отправки результата: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in uniqueness task: ${e.message}", e)
            
            // Send fatal error log to backend
            try {
                safeSendLog("error", TAG, "Fatal error in uniqueness task ${task.id}: ${e.message}\n${e.stackTraceToString().take(1000)}")
            } catch (logError: Exception) {
                Log.w(TAG, "Failed to send fatal error log: ${logError.message}")
            }
            
            try {
                apiClient.updateTaskStatus(task.id, "failed")
                val request = ApiClient.TaskResultRequest(
                    success = false,
                    data = mapOf(
                        "error" to (e.message ?: "Unknown error"), 
                        "stackTrace" to (e.stackTraceToString().take(500)),
                        "taskId" to task.id,
                        "taskType" to "uniqueness"
                    ),
                    error = e.message ?: "Unknown error",
                    executionTime = System.currentTimeMillis()
                )
                apiClient.sendTaskResult(task.id, request, deviceId ?: "")
            } catch (sendError: Exception) {
                Log.e(TAG, "Error sending error result: ${sendError.message}", sendError)
            }
            
            onError?.invoke("Ошибка уникализации: ${e.message}")
            updateNotification("Ошибка уникализации: ${e.message}")
            
            // Don't let the exception crash the service
            Log.w(TAG, "Uniqueness task failed but service continues running")
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
    /**
     * Safely send log to backend without throwing exceptions
     * Now uses LogInterceptor which automatically captures all Log calls
     */
    private fun safeSendLog(level: String, tag: String, message: String) {
        try {
            // Use LogInterceptor which automatically forwards to backend
            when (level.lowercase()) {
                "error", "e" -> LogInterceptor.e(TAG, "[$tag] $message")
                "warn", "w" -> LogInterceptor.w(TAG, "[$tag] $message")
                "debug", "d" -> LogInterceptor.d(TAG, "[$tag] $message")
                else -> LogInterceptor.i(TAG, "[$tag] $message")
            }
        } catch (e: Exception) {
            // Last resort - try to log to logcat with minimal code
            try {
                android.util.Log.e("ControllerService", "Error in safeSendLog: ${e.message}")
            } catch (e2: Exception) {
                // If even this fails, we're in serious trouble - do nothing
            }
        }
    }
    
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
    fun getAgentDeviceId(): String? = deviceId

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
