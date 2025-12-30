package com.automation.agent.utils

import android.util.Log
import com.automation.agent.network.ApiClient
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

/**
 * AutoLogcatSender - Automatically reads logcat and sends logs to backend
 * 
 * This service runs in background and continuously reads logcat output,
 * filters logs by specified tags, and sends them to backend via ApiClient.
 */
class AutoLogcatSender(
    private var apiClient: ApiClient?,
    private var deviceId: String?
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logcatJob: Job? = null
    private var isRunning = false
    
    // Tags to filter (empty = all tags)
    private val filterTags = mutableSetOf<String>()
    
    // Log queue to batch send
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var lastBatchSend = System.currentTimeMillis()
    
    companion object {
        private const val TAG = "AutoLogcatSender"
        private const val BATCH_SIZE = 20
        private const val BATCH_INTERVAL_MS = 2000L // Send batch every 2 seconds
        private const val MAX_QUEUE_SIZE = 500
    }
    
    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Set API client
     */
    fun setApiClient(client: ApiClient?) {
        this.apiClient = client
    }
    
    /**
     * Set device ID
     */
    fun setDeviceId(id: String?) {
        this.deviceId = id
    }
    
    /**
     * Add tag to filter (empty = all tags)
     */
    fun addFilterTag(tag: String) {
        filterTags.add(tag)
    }
    
    /**
     * Remove tag from filter
     */
    fun removeFilterTag(tag: String) {
        filterTags.remove(tag)
    }
    
    /**
     * Clear all filter tags (will capture all logs)
     */
    fun clearFilterTags() {
        filterTags.clear()
    }
    
    /**
     * Start reading logcat and sending logs
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "AutoLogcatSender already running")
            return
        }
        
        isRunning = true
        Log.i(TAG, "Starting AutoLogcatSender...")
        
        // Start logcat reader
        logcatJob = scope.launch {
            try {
                readLogcat()
            } catch (e: Exception) {
                Log.e(TAG, "Error in logcat reader: ${e.message}", e)
                isRunning = false
            }
        }
        
        // Start batch sender
        scope.launch {
            while (isRunning) {
                try {
                    sendBatch()
                    delay(BATCH_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in batch sender: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop reading logcat
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        Log.i(TAG, "Stopping AutoLogcatSender...")
        isRunning = false
        logcatJob?.cancel()
        logcatJob = null
    }
    
    /**
     * Read logcat output
     */
    private suspend fun readLogcat() = withContext(Dispatchers.IO) {
        try {
            // Build logcat command with filters
            // Use simpler format that works better
            val command = mutableListOf("logcat", "-v", "time")
            
            // Add tag filters if specified
            if (filterTags.isNotEmpty()) {
                filterTags.forEach { tag ->
                    command.add("$tag:*")
                }
                command.add("*:S") // Silence all other tags
            } else {
                // Default: filter by important tags
                command.addAll(listOf(
                    "ControllerService:*",
                    "UniquenessService:*",
                    "TaskExecutor:*",
                    "App:*",
                    "MainActivity:*",
                    "AutoLogcatSender:*",
                    "*:E", // All errors
                    "*:W", // All warnings
                    "*:S"  // Silence everything else
                ))
            }
            
            Log.i(TAG, "Starting logcat process: ${command.joinToString(" ")}")
            
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start logcat process (may require root): ${e.message}")
                // Try alternative: read recent logs only
                readRecentLogs()
                return@withContext
            }
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            // Pattern for "time" format: MM-DD HH:MM:SS.mmm level/tag: message
            val logPattern = Pattern.compile(
                "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEF])/([^:]+):\\s+(.*)$"
            )
            
            var line: String? = null
            while (isRunning && reader.readLine().also { line = it } != null) {
                val logLine = line ?: continue
                    try {
                        val matcher = logPattern.matcher(logLine)
                        if (matcher.find()) {
                            val timestamp = matcher.group(1)
                            val level = matcher.group(2)
                            val tag = matcher.group(3).trim()
                            val message = matcher.group(4).trim()
                            
                            // Convert Android log level to our format
                            val logLevel = when (level) {
                                "V" -> "debug"
                                "D" -> "debug"
                                "I" -> "info"
                                "W" -> "warn"
                                "E" -> "error"
                                "F" -> "error"
                                else -> "info"
                            }
                            
                            // Add to queue
                            if (logQueue.size >= MAX_QUEUE_SIZE) {
                                logQueue.poll() // Remove oldest
                            }
                            logQueue.offer(LogEntry(logLevel, tag, message))
                        } else {
                            // If pattern doesn't match, try to parse as simple log
                            if (logLine.contains("ControllerService") || 
                                logLine.contains("UniquenessService") ||
                                logLine.contains("TaskExecutor") ||
                                logLine.contains("executeUniquenessTask") ||
                                logLine.contains("executeNextTask") ||
                                logLine.contains("UNIQUENESS") ||
                                logLine.contains("TASK EXECUTION")) {
                                
                                // Extract tag and message
                                val parts = logLine.split(":", limit = 2)
                                if (parts.size == 2) {
                                    val tag = parts[0].trim()
                                    val message = parts[1].trim()
                                    if (logQueue.size < MAX_QUEUE_SIZE) {
                                        logQueue.offer(LogEntry("info", tag, message))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silently ignore parsing errors
                    }
            }
            
            reader.close()
            process.destroy()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logcat: ${e.message}", e)
            // Try alternative method: read recent logs
            try {
                readRecentLogs()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to read recent logs: ${e2.message}", e2)
            }
        }
    }
    
    /**
     * Read recent logs (fallback method)
     */
    private suspend fun readRecentLogs() = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val importantTags = listOf(
                "ControllerService", "UniquenessService", "TaskExecutor",
                "App", "MainActivity", "AutoLogcatSender"
            )
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    importantTags.forEach { tag ->
                        if (logLine.contains(tag)) {
                            val parts = logLine.split(":", limit = 2)
                            if (parts.size == 2 && logQueue.size < MAX_QUEUE_SIZE) {
                                logQueue.offer(LogEntry("info", tag, parts[1].trim()))
                            }
                        }
                    }
                }
            }
            
            reader.close()
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recent logs: ${e.message}", e)
        }
    }
    
    /**
     * Send batch of logs to backend
     */
    private suspend fun sendBatch() {
        val client = apiClient
        val devId = deviceId
        
        if (client == null || devId == null || logQueue.isEmpty()) {
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
                // Silently fail - don't log errors about logging
            }
        }
        
        lastBatchSend = System.currentTimeMillis()
    }
    
    /**
     * Force send all queued logs
     */
    fun flush() {
        scope.launch {
            sendBatch()
        }
    }
}

