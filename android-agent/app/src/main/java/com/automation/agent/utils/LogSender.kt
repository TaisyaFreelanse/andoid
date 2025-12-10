package com.automation.agent.utils

import android.util.Log
import com.automation.agent.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LogSender - Utility for sending Android logs to backend
 * 
 * Intercepts Log calls and sends them to backend via ApiClient
 */
class LogSender(
    private var apiClient: ApiClient?,
    private var deviceId: String?
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var currentTaskId: String? = null
    
    fun setApiClient(apiClient: ApiClient?) {
        this.apiClient = apiClient
    }
    
    fun setDeviceId(deviceId: String?) {
        this.deviceId = deviceId
    }
    
    companion object {
        private const val TAG = "LogSender"
        private const val MAX_QUEUE_SIZE = 1000
        private const val BATCH_SIZE = 10
        private const val BATCH_DELAY_MS = 1000L
    }
    
    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val taskId: String?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Set current task ID for log context
     */
    fun setTaskId(taskId: String?) {
        currentTaskId = taskId
    }
    
    /**
     * Send log to backend
     */
    fun log(level: String, tag: String, message: String, taskId: String? = null) {
        val client = apiClient
        val devId = deviceId
        if (client == null || devId == null) {
            return
        }
        
        val logEntry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            taskId = taskId ?: currentTaskId
        )
        
        // Add to queue
        if (logQueue.size >= MAX_QUEUE_SIZE) {
            logQueue.poll() // Remove oldest entry
        }
        logQueue.offer(logEntry)
        
        // Send in background
        scope.launch {
            try {
                client.sendLog(level, tag, message, logEntry.taskId)
            } catch (e: Exception) {
                // Silently fail - don't log errors about logging
            }
        }
    }
    
    /**
     * Send batch of logs
     */
    fun sendBatch() {
        val client = apiClient
        val devId = deviceId
        if (client == null || devId == null || logQueue.isEmpty()) {
            return
        }
        
        scope.launch {
            val batch = mutableListOf<LogEntry>()
            repeat(BATCH_SIZE) {
                logQueue.poll()?.let { batch.add(it) }
            }
            
            if (batch.isNotEmpty()) {
                batch.forEach { entry ->
                    try {
                        client.sendLog(entry.level, entry.tag, entry.message, entry.taskId)
                    } catch (e: Exception) {
                        // Silently fail
                    }
                }
            }
        }
    }
    
    /**
     * Clear log queue
     */
    fun clear() {
        logQueue.clear()
    }
}

