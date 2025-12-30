package com.automation.agent.utils

import android.util.Log
import com.automation.agent.network.ApiClient
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LogInterceptor - Intercepts all Log calls and automatically sends to backend
 * 
 * This class wraps android.util.Log and automatically forwards all log calls
 * to the backend via ApiClient. This works without root access.
 */
object LogInterceptor {
    private var apiClient: ApiClient? = null
    private var deviceId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var isEnabled = true
    
    // Tags to always send (empty = all tags)
    private val importantTags = setOf(
        "ControllerService",
        "UniquenessService", 
        "TaskExecutor",
        "App",
        "MainActivity",
        "AutoLogcatSender",
        "LogInterceptor",
        "executeUniquenessTask",
        "executeNextTask",
        "UNIQUENESS",
        "TASK EXECUTION"
    )
    
    private const val TAG = "LogInterceptor"
    private const val BATCH_SIZE = 20
    private const val BATCH_INTERVAL_MS = 2000L
    private const val MAX_QUEUE_SIZE = 1000
    
    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Initialize with API client and device ID
     */
    fun init(client: ApiClient?, devId: String?) {
        apiClient = client
        deviceId = devId
        isEnabled = true
        
        // Start batch sender
        scope.launch {
            while (isEnabled) {
                try {
                    sendBatch()
                    delay(BATCH_INTERVAL_MS)
                } catch (e: Exception) {
                    // Silently fail
                }
            }
        }
    }
    
    /**
     * Update API client
     */
    fun setApiClient(client: ApiClient?) {
        apiClient = client
    }
    
    /**
     * Update device ID
     */
    fun setDeviceId(devId: String?) {
        deviceId = devId
    }
    
    /**
     * Enable/disable logging
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }
    
    /**
     * Intercept Log.v call
     */
    fun v(tag: String, msg: String): Int {
        val result = Log.v(tag, msg)
        intercept("debug", tag, msg)
        return result
    }
    
    /**
     * Intercept Log.d call
     */
    fun d(tag: String, msg: String): Int {
        val result = Log.d(tag, msg)
        intercept("debug", tag, msg)
        return result
    }
    
    /**
     * Intercept Log.i call
     */
    fun i(tag: String, msg: String): Int {
        val result = Log.i(tag, msg)
        intercept("info", tag, msg)
        return result
    }
    
    /**
     * Intercept Log.w call
     */
    fun w(tag: String, msg: String): Int {
        val result = Log.w(tag, msg)
        intercept("warn", tag, msg)
        return result
    }
    
    /**
     * Intercept Log.e call
     */
    fun e(tag: String, msg: String): Int {
        val result = Log.e(tag, msg)
        intercept("error", tag, msg)
        return result
    }
    
    /**
     * Intercept Log.e call with exception
     */
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        val result = Log.e(tag, msg, tr)
        val fullMsg = if (tr != null) {
            "$msg\n${tr.stackTraceToString()}"
        } else {
            msg
        }
        intercept("error", tag, fullMsg)
        return result
    }
    
    /**
     * Intercept log call
     */
    private fun intercept(level: String, tag: String, message: String) {
        if (!isEnabled) return
        
        // Check if this tag is important or if we should send all logs
        val shouldSend = importantTags.isEmpty() || 
                        importantTags.any { tag.contains(it, ignoreCase = true) } ||
                        message.contains("ControllerService", ignoreCase = true) ||
                        message.contains("UniquenessService", ignoreCase = true) ||
                        message.contains("TaskExecutor", ignoreCase = true) ||
                        message.contains("executeUniquenessTask", ignoreCase = true) ||
                        message.contains("executeNextTask", ignoreCase = true) ||
                        message.contains("UNIQUENESS", ignoreCase = true) ||
                        message.contains("TASK EXECUTION", ignoreCase = true) ||
                        level == "error" || level == "warn"
        
        if (!shouldSend) return
        
        // Add to queue
        if (logQueue.size >= MAX_QUEUE_SIZE) {
            logQueue.poll() // Remove oldest
        }
        logQueue.offer(LogEntry(level, tag, message))
        
        // CRITICAL: For important logs, send immediately to ensure they reach backend
        // This is especially important for errors and warnings that might indicate crashes
        if (level == "error" || level == "warn" || 
            importantTags.any { tag.contains(it, ignoreCase = true) } ||
            message.contains("executeUniquenessTask", ignoreCase = true) ||
            message.contains("CONFIG STRUCTURE", ignoreCase = true)) {
            scope.launch {
                try {
                    sendLogImmediately(level, tag, message)
                } catch (e: Exception) {
                    // Silently fail - will be sent in batch later
                }
            }
        }
    }
    
    /**
     * Send log immediately (for critical logs that must reach backend)
     */
    private suspend fun sendLogImmediately(level: String, tag: String, message: String) {
        val client = apiClient
        val devId = deviceId
        
        // Try to get deviceId from ApiClient if not set yet
        val finalDeviceId = devId ?: try {
            val deviceIdField = client?.javaClass?.getDeclaredField("storedDeviceId")
            deviceIdField?.isAccessible = true
            deviceIdField?.get(client) as? String
        } catch (e: Exception) {
            null
        }
        
        if (client == null || finalDeviceId == null) {
            // Will be sent in batch later when deviceId is available
            return
        }
        
        try {
            client.sendLog(level, tag, message)
        } catch (e: Exception) {
            // Silently fail - will be sent in batch later
        }
    }
    
    /**
     * Send batch of logs to backend
     */
    private suspend fun sendBatch() {
        val client = apiClient
        
        if (client == null || logQueue.isEmpty()) {
            return
        }
        
        // If deviceId is not set yet, try to get it from storedDeviceId
        val devId = deviceId ?: try {
            // Try to get deviceId from ApiClient if available
            val deviceIdField = client.javaClass.getDeclaredField("storedDeviceId")
            deviceIdField.isAccessible = true
            deviceIdField.get(client) as? String
        } catch (e: Exception) {
            null
        }
        
        if (devId == null) {
            // Don't send logs if deviceId is not available yet
            // They will be sent once deviceId is set
            return
        }
        
        val batch = mutableListOf<LogEntry>()
        repeat(BATCH_SIZE) {
            logQueue.poll()?.let { batch.add(it) }
        }
        
        if (batch.isEmpty()) {
            return
        }
        
        // Send each log entry
        batch.forEach { entry ->
            try {
                client.sendLog(entry.level, entry.tag, entry.message)
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }
    
    /**
     * Force send all queued logs
     */
    fun flush() {
        scope.launch {
            sendBatch()
        }
    }
    
    /**
     * Clear log queue
     */
    fun clear() {
        logQueue.clear()
    }
}

