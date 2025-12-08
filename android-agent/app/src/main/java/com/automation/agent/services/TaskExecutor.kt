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
import com.automation.agent.services.UniquenessService
import com.automation.agent.utils.GeoLocationHelper
import com.automation.agent.utils.RootUtils
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
    private val proxyManager: ProxyManager,
    private var deviceId: String = ""
) {
    
    fun setDeviceId(id: String) {
        deviceId = id
    }
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
        const val STEP_LOOP = "loop"
        
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
    
    // Uniqueness services
    private val rootUtils = RootUtils()
    private val uniquenessService = UniquenessService(context, rootUtils)
    private val geoLocationHelper = GeoLocationHelper()
    
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
            
            // Apply uniqueness (proxy, timezone, geolocation, language) if proxy is set
            if (task.proxy != null && task.proxy != "none") {
                applyUniqueness(task.proxy)
            }
            
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
            STEP_LOOP -> executeLoop(browser, step)
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
            // Clear intercepted URLs before navigation to start fresh
            browser.clearInterceptedUrls()
            
            Log.d(TAG, "Navigating to: $url")
            browser.navigate(url)
            
            // Wait for page to fully load (default 3 seconds)
            val waitAfter = (step.config["waitAfter"] as? Number)?.toLong() ?: 3000L
            Log.d(TAG, "Waiting ${waitAfter}ms for page load...")
            
            // Try to wait for page load
            val loadTimeout = (step.config["loadTimeout"] as? Number)?.toLong() ?: 10000L
            val loaded = browser.waitForPageLoad(loadTimeout)
            Log.d(TAG, "Page load complete: $loaded, current URL: ${browser.getCurrentUrl()}")
            
            // Additional wait after load
            delay(waitAfter)
            
            StepResult(
                success = true,
                data = mapOf("url" to url, "currentUrl" to browser.getCurrentUrl())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed: ${e.message}", e)
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
        
        Log.d(TAG, "Extracting data: selector='$selector', attribute='$attribute', saveAs='$saveAs'")
        
        return try {
            // Try JavaScript extraction first (more reliable for WebView)
            val results = extractViaJavaScript(browser, selector, attribute)
            
            Log.d(TAG, "Extracted ${results.size} results for '$saveAs': ${results.take(3)}")
            
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
                val parser = Parser(browser)
                val currentUrl = try { browser.getCurrentUrl() } catch (e: Exception) { "" }
                val currentDomain = try {
                    if (currentUrl.isNotEmpty()) {
                        java.net.URL(currentUrl).host
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    ""
                }
                
                // First, try to parse adurl from links
                val adUrls = results.mapNotNull { link ->
                    val adUrl = parser.parseAdUrl(link)
                    // Filter out adUrls that match the current domain
                    if (adUrl != null && currentDomain.isNotEmpty()) {
                        try {
                            val adDomain = java.net.URL(adUrl).host
                            if (adDomain == currentDomain || adDomain.contains(currentDomain) || currentDomain.contains(adDomain)) {
                                Log.d(TAG, "Filtered out adUrl matching current domain: $adUrl (domain: $adDomain)")
                                null
                            } else {
                                adUrl
                            }
                        } catch (e: Exception) {
                            // If URL parsing fails, check if it's the same as current URL
                            if (adUrl == currentUrl) {
                                Log.d(TAG, "Filtered out adUrl matching current URL: $adUrl")
                                null
                            } else {
                                adUrl
                            }
                        }
                    } else {
                        adUrl
                    }
                }.filterNotNull()
                
                // Also, if link itself contains adurl parameter or is from ad network, use it directly
                val directAdUrls = results.mapNotNull { link ->
                    // Check if link itself is an ad URL (contains adurl parameter or is from ad network)
                    if (link.contains("adurl") || link.contains("googleads") || link.contains("doubleclick") || 
                        link.contains("googlesyndication") || link.contains("adservice")) {
                        // Try to parse adurl from the link
                        val parsed = parser.parseAdUrl(link)
                        if (parsed != null && currentDomain.isNotEmpty()) {
                            try {
                                val adDomain = java.net.URL(parsed).host
                                if (adDomain != currentDomain && !adDomain.contains(currentDomain) && !currentDomain.contains(adDomain)) {
                                    parsed
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                if (parsed != currentUrl) parsed else null
                            }
                        } else {
                            parsed
                        }
                    } else {
                        null
                    }
                }.filterNotNull()
                
                // Also try to extract all links from page that might be ad links
                // This helps find links that can be copied (like in the screenshot)
                val allPageLinks = try {
                    extractAllPageLinks(browser, currentDomain)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract all page links: ${e.message}")
                    emptyList()
                }
                
                Log.d(TAG, "Extracted ${allPageLinks.size} page links for adurl extraction")
                
                val pageAdUrls = allPageLinks.mapNotNull { link ->
                    val adUrl = parser.parseAdUrl(link)
                    if (adUrl != null && currentDomain.isNotEmpty()) {
                        try {
                            val adDomain = java.net.URL(adUrl).host
                            if (adDomain != currentDomain && !adDomain.contains(currentDomain) && !currentDomain.contains(adDomain)) {
                                Log.d(TAG, "Found adUrl from page link: $adUrl (from: $link)")
                                adUrl
                            } else {
                                Log.d(TAG, "Filtered out adUrl from page link (same domain): $adUrl")
                                null
                            }
                        } catch (e: Exception) {
                            if (adUrl != currentUrl) {
                                Log.d(TAG, "Found adUrl from page link (parsing error): $adUrl")
                                adUrl
                            } else {
                                null
                            }
                        }
                    } else {
                        if (adUrl != null) {
                            Log.d(TAG, "Found adUrl from page link (no domain check): $adUrl")
                        }
                        adUrl
                    }
                }.filterNotNull()
                
                // Get intercepted ad URLs from network requests (passive method - no clicks)
                val interceptedAdUrls = try {
                    val intercepted = browser.getInterceptedAdUrls()
                    Log.d(TAG, "Intercepted ${intercepted.size} ad URLs from network requests")
                    intercepted.filter { url ->
                        // Filter out URLs matching current domain
                        if (currentDomain.isNotEmpty()) {
                            try {
                                val urlDomain = java.net.URL(url).host
                                urlDomain != currentDomain && !urlDomain.contains(currentDomain) && !currentDomain.contains(urlDomain)
                            } catch (e: Exception) {
                                url != currentUrl
                            }
                        } else {
                            true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get intercepted ad URLs: ${e.message}")
                    emptySet()
                }
                
                Log.d(TAG, "Parsed adUrls: ${adUrls.size}, directAdUrls: ${directAdUrls.size}, pageAdUrls: ${pageAdUrls.size}, interceptedAdUrls: ${interceptedAdUrls.size}")
                Log.d(TAG, "Results to parse: ${results.size} links")
                if (results.isNotEmpty()) {
                    Log.d(TAG, "First few results: ${results.take(3)}")
                }
                if (interceptedAdUrls.isNotEmpty()) {
                    Log.d(TAG, "Sample intercepted adUrls: ${interceptedAdUrls.take(3)}")
                }
                
                val allAdUrls = (adUrls + directAdUrls + pageAdUrls + interceptedAdUrls).distinct()
                
                Log.d(TAG, "Total unique adUrls extracted: ${allAdUrls.size}")
                if (allAdUrls.isNotEmpty()) {
                    Log.d(TAG, "Sample adUrls: ${allAdUrls.take(3)}")
                }
                
                val existingAdUrls = executionResults["ad_urls"] as? MutableList<String> ?: mutableListOf()
                existingAdUrls.addAll(allAdUrls)
                executionResults["ad_urls"] = existingAdUrls
                
                // Extract domains
                val domains = parser.deduplicateDomains(allAdUrls)
                val existingDomains = executionResults["ad_domains"] as? MutableList<String> ?: mutableListOf()
                existingDomains.addAll(domains)
                executionResults["ad_domains"] = existingDomains.distinct()
                
                Log.d(TAG, "Extracted ${allAdUrls.size} ad URLs from ${results.size} links + ${allPageLinks.size} page links")
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
        
        // Support both "text" and "value" fields for compatibility
        val text = (step.config["text"] as? String) 
            ?: (step.config["value"] as? String)
            ?: return StepResult(success = false, error = "Text not specified (neither 'text' nor 'value' field found)")
        
        val clearBefore = step.config["clear_before"] as? Boolean ?: false
        val submitAfter = step.config["submit"] as? Boolean ?: false
        
        Log.d(TAG, "executeInput: selector=$selector, text=$text, clearBefore=$clearBefore, submitAfter=$submitAfter")
        
        return try {
            // Clear if needed
            if (clearBefore) {
                browser.clear(selector)
                delay(200)
            }
            
            // Try to click to focus first (may fail if element not found yet)
            try {
                browser.click(selector)
                delay(300)
            } catch (e: Exception) {
                Log.w(TAG, "Click to focus failed: ${e.message}, continuing with input")
            }
            
            // Input text - try multiple times with delays
            var success = false
            var lastError: String? = null
            
            for (attempt in 1..3) {
                Log.d(TAG, "Input attempt $attempt/3")
                success = browser.input(selector, text)
                
                if (success) {
                    Log.d(TAG, "Input successful on attempt $attempt")
                    break
                }
                
                lastError = "Attempt $attempt failed"
                Log.w(TAG, lastError)
                
                // Wait and retry
                delay(500L * attempt)
            }
            
            if (success) {
                // Submit form if requested (press Enter)
                if (submitAfter) {
                    delay(300)
                    browser.submit(null) // Submit without selector = press Enter
                    delay(500)
                }
                
                StepResult(
                    success = true,
                    data = mapOf("selector" to selector, "text" to text, "submitted" to submitAfter)
                )
            } else {
                // Even if input "failed", continue with warning instead of hard fail
                Log.w(TAG, "Input may have failed, but continuing: $lastError")
                StepResult(
                    success = true, // Changed to true to allow scenario to continue
                    data = mapOf(
                        "selector" to selector, 
                        "text" to text, 
                        "warning" to "Input may have failed: $lastError"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Input failed with exception: ${e.message}", e)
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
     * Execute loop - repeat nested steps multiple times
     */
    private suspend fun executeLoop(browser: BrowserController, step: TaskStep): StepResult {
        val maxIterations = (step.config["max_iterations"] as? Number)?.toInt() ?: 5
        val nestedSteps = step.config["steps"] as? List<*>
        
        if (nestedSteps.isNullOrEmpty()) {
            Log.w(TAG, "Loop has no nested steps")
            return StepResult(
                success = true,
                data = mapOf("iterations" to 0, "warning" to "No nested steps in loop")
            )
        }
        
        Log.i(TAG, "Starting loop with $maxIterations iterations and ${nestedSteps.size} nested steps")
        
        var completedIterations = 0
        var lastError: String? = null
        
        try {
            for (iteration in 1..maxIterations) {
                Log.d(TAG, "Loop iteration $iteration/$maxIterations")
                
                for ((stepIndex, nestedStepData) in nestedSteps.withIndex()) {
                    val nestedStepMap = nestedStepData as? Map<*, *> ?: continue
                    
                    // Convert nested step to TaskStep
                    val nestedStep = TaskStep(
                        type = nestedStepMap["type"] as? String ?: continue,
                        config = nestedStepMap.mapKeys { it.key.toString() }
                            .filterKeys { it != "type" && it != "id" && it != "description" }
                            .mapValues { it.value ?: "" }
                    )
                    
                    Log.d(TAG, "Loop[$iteration] step ${stepIndex + 1}: ${nestedStep.type}")
                    
                    // Execute nested step
                    val stepResult = executeStep(nestedStep)
                    
                    if (!stepResult.success) {
                        // Check if step is optional
                        val isOptional = nestedStepMap["optional"] as? Boolean ?: false
                        if (!isOptional) {
                            Log.w(TAG, "Loop[$iteration] non-optional step failed: ${nestedStep.type} - ${stepResult.error}")
                            lastError = stepResult.error
                            // Continue to next iteration instead of failing completely
                        }
                    }
                    
                    // Add delay between steps for more human-like behavior
                    val delayMs = (nestedStepMap["delay"] as? Number)?.toLong() 
                        ?: (100L + (Math.random() * 200).toLong())
                    delay(delayMs)
                }
                
                completedIterations++
                
                // Add delay between iterations
                val iterationDelay = (step.config["iteration_delay"] as? Number)?.toLong() ?: 500L
                delay(iterationDelay)
            }
            
            Log.i(TAG, "Loop completed: $completedIterations/$maxIterations iterations")
            
            return StepResult(
                success = true,
                data = mapOf(
                    "iterations" to completedIterations,
                    "maxIterations" to maxIterations,
                    "nestedSteps" to nestedSteps.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Loop execution error: ${e.message}", e)
            return StepResult(
                success = completedIterations > 0,
                error = "Loop error after $completedIterations iterations: ${e.message}",
                data = mapOf(
                    "iterations" to completedIterations,
                    "maxIterations" to maxIterations,
                    "error" to (e.message ?: "Unknown error")
                )
            )
        }
    }

    /**
     * Extract data via JavaScript (more reliable for WebView)
     */
    private suspend fun extractViaJavaScript(
        browser: BrowserController, 
        selector: String, 
        attribute: String?
    ): List<String> {
        val attrScript = when {
            attribute == null || attribute == "text" || attribute == "textContent" -> 
                "el.textContent || el.innerText || ''"
            attribute == "html" || attribute == "innerHTML" -> 
                "el.innerHTML || ''"
            attribute == "outerHtml" -> 
                "el.outerHTML || ''"
            attribute == "href" -> 
                "el.href || el.getAttribute('href') || ''"
            else -> 
                "el.getAttribute('$attribute') || ''"
        }
        
        // Special handling for href attribute - try multiple ways to get the link
        val hrefScript = if (attribute == "href") {
            """
            (function() {
                try {
                    var elements = document.querySelectorAll('$selector');
                    var results = [];
                    elements.forEach(function(el) {
                        // Try multiple ways to get href
                        var href = el.href || el.getAttribute('href') || '';
                        // If element is not a link, try to find link inside it
                        if (!href && el.querySelector) {
                            var linkInside = el.querySelector('a[href]');
                            if (linkInside) {
                                href = linkInside.href || linkInside.getAttribute('href') || '';
                            }
                        }
                        // If still no href, try to get onclick handler or data attributes
                        if (!href) {
                            var onclick = el.getAttribute('onclick') || '';
                            var dataHref = el.getAttribute('data-href') || el.getAttribute('data-url') || '';
                            href = dataHref || onclick;
                        }
                        if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                            results.push(href.trim());
                        }
                    });
                    return JSON.stringify(results);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                try {
                    var elements = document.querySelectorAll('$selector');
                    var results = [];
                    elements.forEach(function(el) {
                        var value = $attrScript;
                        if (value && value.trim()) {
                            results.push(value.trim());
                        }
                    });
                    return JSON.stringify(results);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })();
            """.trimIndent()
        }
        
        val result = browser.evaluateJavascript(hrefScript)
        Log.d(TAG, "JavaScript extraction result: ${result?.take(200)}")
        
        return try {
            if (result.isNullOrEmpty() || result == "null" || result == "[]") {
                emptyList()
            } else {
                // Parse JSON array
                val cleanResult = result.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                
                if (cleanResult.startsWith("[") && cleanResult.endsWith("]")) {
                    // Simple JSON array parsing
                    cleanResult
                        .removeSurrounding("[", "]")
                        .split("\",\"")
                        .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                        .filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JavaScript result: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract all links from page that might be ad links
     * This helps find links that can be copied (like "Copy link address" in context menu)
     */
    private suspend fun extractAllPageLinks(browser: BrowserController, excludeDomain: String): List<String> {
        val script = """
            (function() {
                try {
                    var links = [];
                    // Get all links on page
                    var allLinks = document.querySelectorAll('a[href]');
                    allLinks.forEach(function(a) {
                        var href = a.href || a.getAttribute('href') || '';
                        if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                            links.push(href.trim());
                        }
                    });
                    // Also try to get links from ad containers
                    var adContainers = document.querySelectorAll('ins.adsbygoogle, .ad, .ads, .advertisement, [class*="ad-"], [id*="ad-"]');
                    adContainers.forEach(function(container) {
                        var containerLinks = container.querySelectorAll('a[href]');
                        containerLinks.forEach(function(a) {
                            var href = a.href || a.getAttribute('href') || '';
                            if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                                links.push(href.trim());
                            }
                        });
                    });
                    // Also try to get links near iframes (sibling elements)
                    var iframes = document.querySelectorAll('iframe[src*="googlesyndication"], iframe[src*="doubleclick"]');
                    iframes.forEach(function(iframe) {
                        // Try to get parent element and find links inside
                        var parent = iframe.parentElement;
                        if (parent) {
                            var parentLinks = parent.querySelectorAll('a[href]');
                            parentLinks.forEach(function(a) {
                                var href = a.href || a.getAttribute('href') || '';
                                if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                                    links.push(href.trim());
                                }
                            });
                        }
                        // Try to get next sibling
                        var nextSibling = iframe.nextElementSibling;
                        if (nextSibling && nextSibling.tagName === 'A') {
                            var href = nextSibling.href || nextSibling.getAttribute('href') || '';
                            if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                                links.push(href.trim());
                            }
                        }
                        // Try to get previous sibling
                        var prevSibling = iframe.previousElementSibling;
                        if (prevSibling && prevSibling.tagName === 'A') {
                            var href = prevSibling.href || prevSibling.getAttribute('href') || '';
                            if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                                links.push(href.trim());
                            }
                        }
                    });
                    // Try to get links from elements that might wrap iframes
                    var iframeWrappers = document.querySelectorAll('div:has(iframe[src*="googlesyndication"]), div:has(iframe[src*="doubleclick"])');
                    iframeWrappers.forEach(function(wrapper) {
                        var wrapperLinks = wrapper.querySelectorAll('a[href]');
                        wrapperLinks.forEach(function(a) {
                            var href = a.href || a.getAttribute('href') || '';
                            if (href && href.trim() && href !== '#' && !href.startsWith('javascript:')) {
                                links.push(href.trim());
                            }
                        });
                    });
                    // Remove duplicates
                    return JSON.stringify([...new Set(links)]);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })();
        """.trimIndent()
        
        val result = browser.evaluateJavascript(script)
        
        return try {
            if (result.isNullOrEmpty() || result == "null" || result == "[]") {
                emptyList()
            } else {
                val cleanResult = result.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                
                if (cleanResult.startsWith("[") && cleanResult.endsWith("]")) {
                    cleanResult
                        .removeSurrounding("[", "]")
                        .split("\",\"")
                        .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                        .filter { it.isNotEmpty() && it.startsWith("http") }
                        .filter { link ->
                            // Filter out links from current domain
                            try {
                                val linkDomain = java.net.URL(link).host
                                linkDomain != excludeDomain && !linkDomain.contains(excludeDomain) && !excludeDomain.contains(linkDomain)
                            } catch (e: Exception) {
                                true
                            }
                        }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse page links: ${e.message}")
            emptyList()
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
            
            Log.d(TAG, "Sending task result to backend: taskId=$taskId, deviceId=$deviceId, success=${result.success}")
            val sent = apiClient.sendTaskResult(taskId, request, deviceId)
            Log.d(TAG, "Task result sent: $sent")
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

    /**
     * Apply uniqueness settings (proxy, timezone, geolocation, language)
     */
    private suspend fun applyUniqueness(proxyString: String) {
        if (proxyString == "auto" || proxyString.isEmpty()) {
            Log.i(TAG, "Proxy is 'auto', skipping uniqueness")
            return
        }

        Log.i(TAG, "Applying uniqueness with proxy: $proxyString")

        try {
            // 1. Parse and apply proxy
            val proxyConfig = when {
                proxyString.startsWith("socks5://") -> ProxyManager.parseSocks5(proxyString)
                proxyString.startsWith("http://") || proxyString.startsWith("https://") -> ProxyManager.parseHttp(proxyString)
                else -> ProxyManager.parse(proxyString)
            }

            if (proxyConfig != null) {
                proxyManager.clearProxies()
                proxyManager.addProxy(proxyConfig)
                apiClient.updateProxy()
                Log.i(TAG, "Proxy applied: ${proxyConfig.host}:${proxyConfig.port}")
            } else {
                Log.w(TAG, "Failed to parse proxy: $proxyString")
                return
            }

            // 2. Get geolocation from proxy IP
            val geoLocation = withContext(Dispatchers.IO) {
                delay(2000) // Wait for proxy to be active
                geoLocationHelper.getCurrentIpLocation()
            }

            if (geoLocation != null) {
                Log.i(TAG, "Proxy geolocation: ${geoLocation.country} (${geoLocation.city}), timezone: ${geoLocation.timezone}")

                // 3. Change timezone
                if (geoLocation.timezone.isNotEmpty()) {
                    uniquenessService.changeTimezone(geoLocation.timezone)
                    Log.i(TAG, "Timezone changed to: ${geoLocation.timezone}")
                } else if (geoLocation.country.isNotEmpty()) {
                    uniquenessService.changeTimezoneByCountry(geoLocation.country)
                    Log.i(TAG, "Timezone changed by country: ${geoLocation.country}")
                }

                // 4. Change geolocation (GPS coordinates)
                if (geoLocation.latitude != null && geoLocation.longitude != null) {
                    uniquenessService.changeLocation(geoLocation.latitude, geoLocation.longitude)
                    Log.i(TAG, "Location changed to: ${geoLocation.latitude}, ${geoLocation.longitude}")
                } else if (geoLocation.country.isNotEmpty()) {
                    uniquenessService.changeLocationByCountry(geoLocation.country)
                    Log.i(TAG, "Location changed by country: ${geoLocation.country}")
                }

                // 5. Change language/locale
                if (geoLocation.country.isNotEmpty()) {
                    uniquenessService.changeLocaleByCountry(geoLocation.country)
                    Log.i(TAG, "Locale changed by country: ${geoLocation.country}")
                }
            } else {
                Log.w(TAG, "Could not determine proxy geolocation, using defaults")
                // Fallback: use US defaults
                uniquenessService.changeTimezoneByCountry("US")
                uniquenessService.changeLocationByCountry("US")
                uniquenessService.changeLocaleByCountry("US")
            }

            // Wait a bit for settings to apply
            delay(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying uniqueness: ${e.message}", e)
        }
    }

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
