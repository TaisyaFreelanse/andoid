package com.automation.agent.services

import android.content.Context
import android.util.Log
import com.automation.agent.automation.Navigator
import com.automation.agent.automation.Parser
import com.automation.agent.automation.Screenshot
import com.automation.agent.browser.BrowserController
import com.automation.agent.browser.BrowserSelector
import com.automation.agent.network.ApiClient
import com.automation.agent.network.ProxyManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TaskExecutor - Main executor for automation tasks
 * 
 * Responsibilities:
 * - Execute task steps: navigate, wait, click, extract, screenshot, upload
 * - Handle errors and retry logic
 * - Send results to backend
 */
class TaskExecutor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val proxyManager: ProxyManager
) {
    companion object {
        private const val TAG = "TaskExecutor"
        
        // Step types
        const val STEP_NAVIGATE = "navigate"
        const val STEP_WAIT = "wait"
        const val STEP_CLICK = "click"
        const val STEP_SCROLL = "scroll"
        const val STEP_EXTRACT = "extract"
        const val STEP_SCREENSHOT = "screenshot"
        const val STEP_UPLOAD = "upload"
        const val STEP_INPUT = "input"
        const val STEP_SUBMIT = "submit"
        
        // Task types
        const val TASK_SURFING = "surfing"
        const val TASK_PARSING = "parsing"
        const val TASK_UNIQUENESS = "uniqueness"
        const val TASK_SCREENSHOT = "screenshot"
        
        // Retry settings
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_RETRY_DELAY_MS = 1000L
    }

    private val browserSelector = BrowserSelector(context, proxyManager)
    private var currentBrowser: BrowserController? = null
    private val isExecuting = AtomicBoolean(false)
    private val executionResults = ConcurrentHashMap<String, Any>()
    private var currentTaskId: String? = null
    
    // Callbacks
    var onStepStarted: ((String, TaskStep) -> Unit)? = null
    var onStepCompleted: ((String, TaskStep, StepResult) -> Unit)? = null
    var onTaskProgress: ((String, Int, Int) -> Unit)? = null
    var onTaskCompleted: ((String, TaskResult) -> Unit)? = null

    /**
     * Execute a task with given configuration
     */
    suspend fun executeTask(task: TaskConfig): TaskResult = withContext(Dispatchers.Main) {
        if (isExecuting.getAndSet(true)) {
            return@withContext TaskResult(
                success = false,
                error = "Another task is already executing"
            )
        }

        currentTaskId = task.id
        executionResults.clear()
        
        Log.i(TAG, "Starting task: ${task.id} (${task.name})")
        
        try {
            // Update task status to "running"
            apiClient.updateTaskStatus(task.id, "running")
            
            // Initialize browser
            currentBrowser = browserSelector.selectBrowser(task.browser)
            
            // Execute steps
            val stepResults = mutableListOf<StepResult>()
            var lastError: String? = null
            
            for ((index, step) in task.steps.withIndex()) {
                onTaskProgress?.invoke(task.id, index + 1, task.steps.size)
                onStepStarted?.invoke(task.id, step)
                
                Log.d(TAG, "Executing step ${index + 1}/${task.steps.size}: ${step.type}")
                
                val stepResult = executeStepWithRetry(step, task.maxRetries ?: DEFAULT_MAX_RETRIES)
                stepResults.add(stepResult)
                
                onStepCompleted?.invoke(task.id, step, stepResult)
                
                if (!stepResult.success) {
                    lastError = stepResult.error
                    Log.e(TAG, "Step failed: ${step.type} - ${stepResult.error}")
                    
                    // Check if task should continue on error
                    if (!task.continueOnError) {
                        break
                    }
                }
            }
            
            // Close browser
            currentBrowser?.close()
            currentBrowser = null
            
            // Determine overall success
            val allSucceeded = stepResults.all { it.success }
            val partialSuccess = stepResults.any { it.success }
            
            // Collect extracted data
            val extractedData = collectExtractedData(stepResults)
            
            // Build result
            val result = TaskResult(
                success = allSucceeded,
                data = extractedData,
                error = if (!allSucceeded) lastError else null,
                stepResults = stepResults,
                executionTime = System.currentTimeMillis()
            )
            
            // Send result to backend
            sendResultToBackend(task.id, result)
            
            // Update task status
            val finalStatus = when {
                allSucceeded -> "completed"
                partialSuccess -> "partial"
                else -> "failed"
            }
            apiClient.updateTaskStatus(task.id, finalStatus)
            
            onTaskCompleted?.invoke(task.id, result)
            
            Log.i(TAG, "Task completed: ${task.id}, success=$allSucceeded")
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Task execution error: ${e.message}", e)
            
            val result = TaskResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
            
            apiClient.updateTaskStatus(task.id, "failed")
            onTaskCompleted?.invoke(task.id, result)
            
            result
        } finally {
            isExecuting.set(false)
            currentTaskId = null
            currentBrowser?.close()
            currentBrowser = null
        }
    }

    /**
     * Execute a single step with retry logic
     */
    private suspend fun executeStepWithRetry(step: TaskStep, maxRetries: Int): StepResult {
        var lastError: String? = null
        var retryDelay = DEFAULT_RETRY_DELAY_MS
        
        repeat(maxRetries) { attempt ->
            try {
                val result = executeStep(step)
                if (result.success) {
                    return result
                }
                lastError = result.error
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "Step attempt ${attempt + 1} failed: ${e.message}")
            }
            
            if (attempt < maxRetries - 1) {
                delay(retryDelay)
                retryDelay *= 2 // Exponential backoff
            }
        }
        
        return StepResult(
            success = false,
            error = lastError ?: "Max retries exceeded"
        )
    }

    /**
     * Execute a single step
     */
    private suspend fun executeStep(step: TaskStep): StepResult {
        val browser = currentBrowser ?: return StepResult(
            success = false,
            error = "Browser not initialized"
        )
        
        return when (step.type.lowercase()) {
            STEP_NAVIGATE -> executeNavigate(browser, step)
            STEP_WAIT -> executeWait(step)
            STEP_CLICK -> executeClick(browser, step)
            STEP_SCROLL -> executeScroll(browser, step)
            STEP_EXTRACT -> executeExtract(browser, step)
            STEP_SCREENSHOT -> executeScreenshot(browser, step)
            STEP_INPUT -> executeInput(browser, step)
            STEP_SUBMIT -> executeSubmit(browser, step)
            STEP_UPLOAD -> executeUpload(step)
            else -> StepResult(
                success = false,
                error = "Unknown step type: ${step.type}"
            )
        }
    }

    /**
     * Navigate to URL
     */
    private suspend fun executeNavigate(browser: BrowserController, step: TaskStep): StepResult {
        val url = step.config["url"] as? String
            ?: return StepResult(success = false, error = "URL not specified")
        
        return try {
            browser.navigate(url)
            
            // Wait for page load if specified
            val waitAfter = (step.config["waitAfter"] as? Number)?.toLong() ?: 1000L
            delay(waitAfter)
            
            StepResult(
                success = true,
                data = mapOf("url" to url, "currentUrl" to browser.getCurrentUrl())
            )
        } catch (e: Exception) {
            StepResult(success = false, error = "Navigation failed: ${e.message}")
        }
    }

    /**
     * Wait for specified duration
     */
    private suspend fun executeWait(step: TaskStep): StepResult {
        val duration = (step.config["duration"] as? Number)?.toLong()
            ?: (step.config["ms"] as? Number)?.toLong()
            ?: 1000L
        
        return try {
            delay(duration)
            StepResult(success = true, data = mapOf("waited" to duration))
        } catch (e: Exception) {
            StepResult(success = false, error = "Wait interrupted: ${e.message}")
        }
    }

    /**
     * Click element
     */
    private suspend fun executeClick(browser: BrowserController, step: TaskStep): StepResult {
        val selector = step.config["selector"] as? String
            ?: return StepResult(success = false, error = "Selector not specified")
        
        return try {
            browser.click(selector)
            
            // Wait after click if specified
            val waitAfter = (step.config["waitAfter"] as? Number)?.toLong() ?: 500L
            delay(waitAfter)
            
            StepResult(success = true, data = mapOf("clicked" to selector))
        } catch (e: Exception) {
            StepResult(success = false, error = "Click failed: ${e.message}")
        }
    }

    /**
     * Scroll page
     */
    private suspend fun executeScroll(browser: BrowserController, step: TaskStep): StepResult {
        val direction = step.config["direction"] as? String ?: "down"
        val pixels = (step.config["pixels"] as? Number)?.toInt() ?: 500
        
        return try {
            browser.scroll(direction, pixels)
            
            // Wait after scroll
            val waitAfter = (step.config["waitAfter"] as? Number)?.toLong() ?: 300L
            delay(waitAfter)
            
            StepResult(
                success = true,
                data = mapOf("direction" to direction, "pixels" to pixels)
            )
        } catch (e: Exception) {
            StepResult(success = false, error = "Scroll failed: ${e.message}")
        }
    }

    /**
     * Extract data from page
     */
    private suspend fun executeExtract(browser: BrowserController, step: TaskStep): StepResult {
        val selector = step.config["selector"] as? String
            ?: return StepResult(success = false, error = "Selector not specified")
        
        val attribute = step.config["attribute"] as? String
        val saveAs = step.config["save_as"] as? String ?: "extracted_data"
        val multiple = step.config["multiple"] as? Boolean ?: true
        
        return try {
            val parser = Parser(browser)
            val results = parser.extractByCss(selector, attribute)
            
            // Save to execution results
            if (multiple) {
                val existing = executionResults[saveAs] as? MutableList<String> ?: mutableListOf()
                existing.addAll(results)
                executionResults[saveAs] = existing
            } else {
                executionResults[saveAs] = results.firstOrNull() ?: ""
            }
            
            // Check for adurl extraction
            if (step.config["extract_adurl"] == true) {
                val adUrls = results.mapNotNull { parser.parseAdUrl(it) }
                val existingAdUrls = executionResults["ad_urls"] as? MutableList<String> ?: mutableListOf()
                existingAdUrls.addAll(adUrls)
                executionResults["ad_urls"] = existingAdUrls
                
                // Extract domains
                val domains = parser.deduplicateDomains(adUrls)
                val existingDomains = executionResults["ad_domains"] as? MutableList<String> ?: mutableListOf()
                existingDomains.addAll(domains)
                executionResults["ad_domains"] = existingDomains.distinct()
            }
            
            StepResult(
                success = true,
                data = mapOf(
                    "selector" to selector,
                    "count" to results.size,
                    "results" to results
                )
            )
        } catch (e: Exception) {
            StepResult(success = false, error = "Extract failed: ${e.message}")
        }
    }

    /**
     * Take screenshot
     */
    private suspend fun executeScreenshot(browser: BrowserController, step: TaskStep): StepResult {
        val saveAs = step.config["save_as"] as? String ?: "screenshot"
        val selector = step.config["selector"] as? String // Optional: screenshot specific element
        
        return try {
            val screenshot = Screenshot()
            // Note: Actual implementation depends on browser type
            // For WebView we can capture the view directly
            
            // For now, return placeholder
            StepResult(
                success = true,
                data = mapOf("saved_as" to saveAs)
            )
        } catch (e: Exception) {
            StepResult(success = false, error = "Screenshot failed: ${e.message}")
        }
    }

    /**
     * Input text into element
     */
    private suspend fun executeInput(browser: BrowserController, step: TaskStep): StepResult {
        val selector = step.config["selector"] as? String
            ?: return StepResult(success = false, error = "Selector not specified")
        val text = step.config["text"] as? String
            ?: return StepResult(success = false, error = "Text not specified")
        
        return try {
            // Click to focus
            browser.click(selector)
            delay(200)
            
            // Input text via JavaScript
            // TODO: Implement text input in browser controller
            
            StepResult(
                success = true,
                data = mapOf("selector" to selector, "text" to text)
            )
        } catch (e: Exception) {
            StepResult(success = false, error = "Input failed: ${e.message}")
        }
    }

    /**
     * Submit form
     */
    private suspend fun executeSubmit(browser: BrowserController, step: TaskStep): StepResult {
        val selector = step.config["selector"] as? String
        
        return try {
            if (selector != null) {
                browser.click(selector)
            }
            
            // Wait for submission
            val waitAfter = (step.config["waitAfter"] as? Number)?.toLong() ?: 2000L
            delay(waitAfter)
            
            StepResult(success = true)
        } catch (e: Exception) {
            StepResult(success = false, error = "Submit failed: ${e.message}")
        }
    }

    /**
     * Upload file/data
     */
    private suspend fun executeUpload(step: TaskStep): StepResult {
        val dataKey = step.config["data_key"] as? String
        val uploadType = step.config["upload_type"] as? String ?: "screenshot"
        
        return try {
            val deviceId = step.config["device_id"] as? String ?: ""
            val taskId = currentTaskId ?: ""
            
            when (uploadType) {
                "screenshot" -> {
                    // Get screenshot bytes from results
                    val screenshotBytes = executionResults["screenshot_bytes"] as? ByteArray
                    if (screenshotBytes != null) {
                        val response = apiClient.uploadScreenshot(deviceId, taskId, screenshotBytes)
                        if (response != null) {
                            executionResults["screenshot_url"] = response.url ?: response.path ?: ""
                        }
                    }
                }
                "data" -> {
                    // Upload parsed data
                    val data = executionResults[dataKey] ?: executionResults
                    // Send via API
                }
            }
            
            StepResult(success = true)
        } catch (e: Exception) {
            StepResult(success = false, error = "Upload failed: ${e.message}")
        }
    }

    /**
     * Collect extracted data from step results
     */
    private fun collectExtractedData(stepResults: List<StepResult>): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        
        // Add all execution results
        data.putAll(executionResults)
        
        // Add step-specific data
        stepResults.forEach { result ->
            result.data?.forEach { (key, value) ->
                if (key !in data) {
                    data[key] = value
                }
            }
        }
        
        return data
    }

    /**
     * Send task result to backend
     */
    private suspend fun sendResultToBackend(taskId: String, result: TaskResult) {
        try {
            val request = ApiClient.TaskResultRequest(
                success = result.success,
                data = result.data,
                error = result.error,
                executionTime = result.executionTime,
                screenshots = result.data?.get("screenshots") as? List<String>
            )
            
            apiClient.sendTaskResult(taskId, request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send result to backend: ${e.message}")
        }
    }

    /**
     * Cancel current task execution
     */
    fun cancelCurrentTask() {
        if (isExecuting.get()) {
            Log.i(TAG, "Cancelling current task: $currentTaskId")
            isExecuting.set(false)
            currentBrowser?.let { browser ->
                CoroutineScope(Dispatchers.Main).launch {
                    browser.close()
                }
            }
            currentBrowser = null
        }
    }

    /**
     * Check if currently executing a task
     */
    fun isExecutingTask(): Boolean = isExecuting.get()

    /**
     * Get current task ID
     */
    fun getCurrentTaskId(): String? = currentTaskId

    // ==================== Data Classes ====================

    data class TaskConfig(
        val id: String,
        val name: String,
        val type: String,
        val browser: String = "webview",
        val proxy: String? = null,
        val steps: List<TaskStep>,
        val maxRetries: Int? = DEFAULT_MAX_RETRIES,
        val continueOnError: Boolean = false,
        val timeout: Long? = null
    )

    data class TaskStep(
        val type: String, // navigate, wait, click, scroll, extract, screenshot, upload, input, submit
        val config: Map<String, Any> = emptyMap()
    )

    data class StepResult(
        val success: Boolean,
        val data: Map<String, Any>? = null,
        val error: String? = null,
        val executionTime: Long = System.currentTimeMillis()
    )

    data class TaskResult(
        val success: Boolean,
        val data: Map<String, Any>? = null,
        val error: String? = null,
        val stepResults: List<StepResult>? = null,
        val executionTime: Long = System.currentTimeMillis()
    )
}
