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
import com.automation.agent.utils.LogSender
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
        logSender.setDeviceId(id)
        logSender.setTaskId(null) // Reset task ID when device changes
    }
    
    /**
     * Helper function to log and send to backend
     */
    private fun logAndSend(level: String, tag: String, message: String) {
        // Log locally
        when (level.lowercase()) {
            "error", "e" -> Log.e(tag, message)
            "warn", "w" -> Log.w(tag, message)
            "debug", "d" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }
        
        // Send to backend
        logSender.log(level, tag, message, currentTaskId)
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
        const val STEP_TAKE_SCREENSHOT = "take_screenshot"
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
    
    // Log sender for sending logs to backend
    private val logSender = LogSender(apiClient, deviceId)
    
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
        logSender.setTaskId(task.id)
        
        logAndSend("info", TAG, "Starting task: ${task.id} (${task.name})")
        
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
            STEP_SCREENSHOT, STEP_TAKE_SCREENSHOT -> executeScreenshot(browser, step)
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
                logAndSend("info", TAG, "Extracting page links with excludeDomain: '$currentDomain'")
                val allPageLinks = try {
                    extractAllPageLinks(browser, currentDomain)
                } catch (e: Exception) {
                    logAndSend("error", TAG, "Failed to extract all page links: ${e.message}")
                    Log.w(TAG, "Failed to extract all page links: ${e.message}")
                    emptyList()
                }
                
                Log.d(TAG, "Extracted ${allPageLinks.size} page links for adurl extraction")
                
                // Log all found links before parsing
                if (allPageLinks.isNotEmpty()) {
                    logAndSend("info", TAG, "=== ALL PAGE LINKS FOUND (${allPageLinks.size}) ===")
                    allPageLinks.take(30).forEachIndexed { index, link ->
                        // Check if link contains adurl or is external
                        val isExternal = try {
                            if (currentDomain.isNotEmpty()) {
                                val linkDomain = java.net.URL(link).host
                                linkDomain != currentDomain && !linkDomain.contains(currentDomain) && !currentDomain.contains(linkDomain)
                            } else {
                                true
                            }
                        } catch (e: Exception) {
                            true
                        }
                        val hasAdUrl = link.contains("adurl", ignoreCase = true)
                        val marker = when {
                            hasAdUrl -> " [HAS ADURL]"
                            isExternal -> " [EXTERNAL]"
                            else -> ""
                        }
                        logAndSend("info", TAG, "  [$index] $link$marker")
                    }
                    if (allPageLinks.size > 30) {
                        logAndSend("info", TAG, "  ... and ${allPageLinks.size - 30} more")
                    }
                } else {
                    logAndSend("warn", TAG, "⚠️ No page links found! This might indicate:")
                    logAndSend("warn", TAG, "  1. Page hasn't loaded yet")
                    logAndSend("warn", TAG, "  2. JavaScript extraction failed")
                    logAndSend("warn", TAG, "  3. All links are filtered out")
                }
                
                // First, try to parse adurl from links
                val pageAdUrls = allPageLinks.mapNotNull { link ->
                    val adUrl = parser.parseAdUrl(link)
                    if (adUrl != null) {
                        if (currentDomain.isNotEmpty()) {
                            try {
                                val adDomain = java.net.URL(adUrl).host
                                if (adDomain != currentDomain && !adDomain.contains(currentDomain) && !currentDomain.contains(adDomain)) {
                                    logAndSend("info", TAG, "✓ Found adUrl from page link: $adUrl (from: $link)")
                                    adUrl
                                } else {
                                    Log.d(TAG, "✗ Filtered out adUrl (same domain): $adUrl (domain: $adDomain, current: $currentDomain)")
                                    null
                                }
                            } catch (e: Exception) {
                                if (adUrl != currentUrl) {
                                    Log.i(TAG, "✓ Found adUrl from page link (parsing error): $adUrl (from: $link)")
                                    adUrl
                                } else {
                                    Log.d(TAG, "✗ Filtered out adUrl (same as current URL): $adUrl")
                                    null
                                }
                            }
                        } else {
                            Log.i(TAG, "✓ Found adUrl from page link (no domain check): $adUrl (from: $link)")
                            adUrl
                        }
                    } else {
                        // Log links that don't parse to adUrl but might be relevant
                        if (link.contains("adurl") || link.contains("googleads") || link.contains("doubleclick")) {
                            Log.d(TAG, "? Link contains ad keywords but didn't parse to adUrl: $link")
                        }
                        null
                    }
                }.filterNotNull()
                
                // Also, extract links that are near iframes - they might be direct ad URLs (without adurl parameter)
                // This handles cases where ad URL is directly in href, not in adurl parameter
                // JavaScript already finds these in extractAllPageLinks, but we need to check them here
                // IMPORTANT: If a link is external and near an iframe, it's likely a direct ad URL
                val directExternalUrls = allPageLinks.mapNotNull { link ->
                    // Check if link is external (different domain) and not already parsed
                    val alreadyParsed = pageAdUrls.any { it == link }
                    if (alreadyParsed) {
                        null
                    } else if (currentDomain.isNotEmpty()) {
                        try {
                            val linkDomain = java.net.URL(link).host
                            val isExternal = linkDomain != currentDomain && 
                                           !linkDomain.contains(currentDomain) && 
                                           !currentDomain.contains(linkDomain)
                            
                            // Exclude ad network domains
                            val isAdNetwork = linkDomain.contains("googlesyndication") || 
                                            linkDomain.contains("doubleclick") ||
                                            linkDomain.contains("googleadservices") ||
                                            linkDomain.contains("pagead") ||
                                            linkDomain.contains("adservice") ||
                                            (linkDomain.contains("google.com") && !linkDomain.contains("googleusercontent"))
                            
                            // If external and not ad network, and link was found near iframe (JavaScript already filtered this)
                            // Then it's likely a direct ad URL - add it!
                            if (isExternal && !isAdNetwork && link.startsWith("http")) {
                                logAndSend("info", TAG, "✓ Found external link near iframe (direct ad URL): $link (domain: $linkDomain)")
                                link
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            // If URL parsing fails, but link is external-looking, might still be ad URL
                            if (link.startsWith("http") && !link.contains("pickuplineinsider.com")) {
                                logAndSend("info", TAG, "? Found external-looking link (parsing error, might be ad URL): $link")
                                link
                            } else {
                                null
                            }
                        }
                    } else {
                        // No domain to check - be more permissive
                        if (link.startsWith("http") && !link.contains("googlesyndication") && 
                            !link.contains("doubleclick") && !link.contains("pagead")) {
                            logAndSend("info", TAG, "? Found link (no domain check, might be ad URL): $link")
                            link
                        } else {
                            null
                        }
                    }
                }.filterNotNull()
                
                logAndSend("info", TAG, "Found ${directExternalUrls.size} external links near iframes (added as direct ad URLs)")
                
                // DEBUG: Log all external links found, even if not near iframe (for debugging)
                if (allPageLinks.isNotEmpty() && currentDomain.isNotEmpty()) {
                    val allExternalLinks = allPageLinks.filter { link ->
                        try {
                            val linkDomain = java.net.URL(link).host
                            val isExternal = linkDomain != currentDomain && 
                                           !linkDomain.contains(currentDomain) && 
                                           !currentDomain.contains(linkDomain)
                            val isAdNetwork = linkDomain.contains("googlesyndication") || 
                                            linkDomain.contains("doubleclick") ||
                                            linkDomain.contains("googleadservices") ||
                                            linkDomain.contains("pagead") ||
                                            linkDomain.contains("adservice")
                            isExternal && !isAdNetwork && link.startsWith("http")
                        } catch (e: Exception) {
                            false
                        }
                    }
                    logAndSend("debug", TAG, "DEBUG: Found ${allExternalLinks.size} total external links on page (not filtered by iframe proximity)")
                    if (allExternalLinks.isNotEmpty() && allExternalLinks.size <= 20) {
                        logAndSend("debug", TAG, "DEBUG: All external links: ${allExternalLinks.joinToString(", ")}")
                    }
                }
                
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
                
                // Try to extract links from iframe content (may fail due to same-origin policy, but worth trying)
                val iframeAdUrls = try {
                    extractLinksFromIframes(browser, currentDomain)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract links from iframes: ${e.message}")
                    emptyList()
                }
                Log.d(TAG, "Extracted ${iframeAdUrls.size} ad URLs from iframe content")
                
                Log.d(TAG, "Parsed adUrls: ${adUrls.size}, directAdUrls: ${directAdUrls.size}, pageAdUrls: ${pageAdUrls.size}, interceptedAdUrls: ${interceptedAdUrls.size}, iframeAdUrls: ${iframeAdUrls.size}, directExternalUrls: ${directExternalUrls.size}")
                Log.d(TAG, "Results to parse: ${results.size} links")
                if (results.isNotEmpty()) {
                    Log.d(TAG, "First few results: ${results.take(3)}")
                }
                if (interceptedAdUrls.isNotEmpty()) {
                    Log.d(TAG, "Sample intercepted adUrls: ${interceptedAdUrls.take(3)}")
                }
                if (pageAdUrls.isNotEmpty()) {
                    Log.d(TAG, "Sample pageAdUrls: ${pageAdUrls.take(3)}")
                }
                if (iframeAdUrls.isNotEmpty()) {
                    Log.d(TAG, "Sample iframeAdUrls: ${iframeAdUrls.take(3)}")
                }
                
                val allAdUrls = (adUrls + directAdUrls + pageAdUrls + interceptedAdUrls + iframeAdUrls + directExternalUrls).distinct()
                
                logAndSend("info", TAG, "=== AD URL EXTRACTION SUMMARY ===")
                logAndSend("info", TAG, "Total unique adUrls extracted: ${allAdUrls.size}")
                logAndSend("info", TAG, "Breakdown: parsed=${adUrls.size}, direct=${directAdUrls.size}, page=${pageAdUrls.size}, intercepted=${interceptedAdUrls.size}, iframe=${iframeAdUrls.size}, external=${directExternalUrls.size}")
                if (allAdUrls.isNotEmpty()) {
                    logAndSend("info", TAG, "Sample adUrls (first 10): ${allAdUrls.take(10).joinToString(", ")}")
                } else {
                    logAndSend("warn", TAG, "⚠️ NO AD URLS FOUND!")
                    logAndSend("warn", TAG, "  - Total page links found: ${allPageLinks.size}")
                    logAndSend("warn", TAG, "  - Links with adurl param: ${allPageLinks.count { it.contains("adurl", ignoreCase = true) }}")
                    logAndSend("warn", TAG, "  - External links: ${allPageLinks.count { link ->
                        try {
                            if (currentDomain.isNotEmpty()) {
                                val linkDomain = java.net.URL(link).host
                                linkDomain != currentDomain && !linkDomain.contains(currentDomain) && !currentDomain.contains(linkDomain)
                            } else false
                        } catch (e: Exception) { false }
                    }}")
                    if (allPageLinks.isNotEmpty()) {
                        logAndSend("warn", TAG, "  - Sample page links (first 5): ${allPageLinks.take(5).joinToString(", ")}")
                    }
                    logAndSend("warn", TAG, "Possible reasons:")
                    logAndSend("warn", TAG, "  1. Ad URLs are not in DOM (they're generated dynamically on click)")
                    logAndSend("warn", TAG, "  2. Ad URLs are inside iframe with different origin (same-origin policy)")
                    logAndSend("warn", TAG, "  3. Ad URLs are in JavaScript event handlers that we haven't extracted yet")
                    logAndSend("warn", TAG, "  4. Need to wait longer for ads to load")
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
        val saveAs = step.config["save_as"] as? String 
            ?: step.config["filename"] as? String 
            ?: "screenshot"
        val uploadToServer = step.config["upload_to_server"] as? Boolean ?: true
        
        return try {
            val screenshotHelper = Screenshot(context)
            
            // Capture screenshot using browser's takeScreenshot method
            val bitmap = browser.takeScreenshot()
            
            if (bitmap == null) {
                return StepResult(success = false, error = "Failed to capture screenshot")
            }
            
            // Save locally
            val localFile = withContext(Dispatchers.IO) {
                screenshotHelper.saveWithTimestamp(bitmap, saveAs)
            }
            
            val localPath = localFile?.absolutePath ?: ""
            Log.d(TAG, "Screenshot saved locally: $localPath")
            
            // Upload to server if requested
            var uploadResult: ApiClient.UploadResponse? = null
            if (uploadToServer && localFile != null && deviceId.isNotEmpty()) {
                try {
                    val screenshotBytes = withContext(Dispatchers.IO) {
                        localFile.readBytes()
                    }
                    val filename = "${saveAs}_${System.currentTimeMillis()}.png"
                    
                    Log.d(TAG, "Uploading screenshot to server: $filename")
                    uploadResult = withContext(Dispatchers.IO) {
                        apiClient.uploadScreenshot(
                            deviceId = deviceId,
                            taskId = currentTaskId,
                            screenshotBytes = screenshotBytes,
                            filename = filename
                        )
                    }
                    
                    if (uploadResult?.success == true) {
                        Log.d(TAG, "Screenshot uploaded: ${uploadResult.path}")
                    } else {
                        Log.w(TAG, "Screenshot upload failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot upload error: ${e.message}")
                }
            }
            
            StepResult(
                success = true,
                data = mapOf(
                    "saved_as" to saveAs,
                    "local_path" to localPath,
                    "uploaded" to (uploadResult?.success == true),
                    "server_path" to (uploadResult?.path ?: "")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}", e)
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
        // Handle multiple attributes (comma-separated)
        val attributes = attribute?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(null)
        
        // If multiple attributes, extract from all of them
        if (attributes.size > 1) {
            val allResults = mutableListOf<String>()
            for (attr in attributes) {
                val results = extractViaJavaScript(browser, selector, attr)
                allResults.addAll(results)
            }
            return allResults.distinct()
        }
        
        val singleAttribute = attributes.firstOrNull()
        val attrScript = when {
            singleAttribute == null || singleAttribute == "text" || singleAttribute == "textContent" -> 
                "el.textContent || el.innerText || ''"
            singleAttribute == "html" || singleAttribute == "innerHTML" -> 
                "el.innerHTML || ''"
            singleAttribute == "outerHtml" -> 
                "el.outerHTML || ''"
            singleAttribute == "href" -> 
                "el.href || el.getAttribute('href') || ''"
            else -> 
                "el.getAttribute('$singleAttribute') || ''"
        }
        
        // Special handling for href attribute - try multiple ways to get the link
        val hrefScript = if (singleAttribute == "href") {
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
        
        val extracted = try {
            if (result.isNullOrEmpty() || result == "null" || result == "[]") {
                emptyList()
            } else {
                // Use proper JSON parsing
                val cleanResult = result.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                
                if (cleanResult.startsWith("[") && cleanResult.endsWith("]")) {
                    // Use JSONArray for proper parsing
                    try {
                        val jsonArray = org.json.JSONArray(cleanResult)
                        val results = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            val value = jsonArray.getString(i)
                            if (value.isNotEmpty()) {
                                results.add(value)
                            }
                        }
                        results
                    } catch (e: Exception) {
                        // Fallback to simple parsing
                    cleanResult
                        .removeSurrounding("[", "]")
                            .split(",")
                        .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                            .filter { it.isNotEmpty() && it != "null" }
                    }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JavaScript result: ${e.message}")
            emptyList()
        }
        
        // If no results found and selector is for iframe, try to find all iframes as fallback
        if (extracted.isEmpty() && selector.contains("iframe")) {
            Log.d(TAG, "No results found with selector '$selector', trying fallback: find all iframes")
            val fallbackScript = """
                (function() {
                    try {
                        var iframes = document.querySelectorAll('iframe[src]');
                        var results = [];
                        iframes.forEach(function(iframe) {
                            var src = iframe.src || iframe.getAttribute('src') || '';
                            if (src && src.trim()) {
                                results.push(src.trim());
                            }
                        });
                        return JSON.stringify(results);
                    } catch(e) {
                        return JSON.stringify([]);
                    }
                })();
            """.trimIndent()
            
            val fallbackResult = browser.evaluateJavascript(fallbackScript)
            Log.d(TAG, "Fallback extraction result: ${fallbackResult?.take(200)}")
            
            return try {
                if (!fallbackResult.isNullOrEmpty() && fallbackResult != "null" && fallbackResult != "[]") {
                    val cleanResult = fallbackResult.trim()
                        .removePrefix("\"")
                        .removeSuffix("\"")
                        .replace("\\\"", "\"")
                    
                    if (cleanResult.startsWith("[") && cleanResult.endsWith("]")) {
                        val jsonArray = org.json.JSONArray(cleanResult)
                        val results = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            val value = jsonArray.getString(i)
                            if (value.isNotEmpty()) {
                                results.add(value)
                            }
                        }
                        Log.d(TAG, "Fallback found ${results.size} iframes")
                        results
                    } else {
                        extracted
                    }
                } else {
                    extracted
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse fallback result: ${e.message}")
                extracted
            }
        }
        
        return extracted
    }

    /**
     * Extract all links from page that might be ad links
     * This helps find links that can be copied (like "Copy link address" in context menu)
     * Improved to find links that visually overlap with iframes (like browser does)
     */
    private suspend fun extractAllPageLinks(browser: BrowserController, excludeDomain: String): List<String> {
        val script = """
            (function() {
                try {
                    var links = [];
                    var excludeHost = '$excludeDomain';
                    var debugInfo = {
                        totalLinksFound: 0,
                        linksAfterFilter: 0,
                        iframesFound: 0,
                        errors: [],
                        filteredLinks: [],
                        sampleLinks: [],
                        excludeHost: excludeHost
                    };
                    console.log('DEBUG: excludeHost =', excludeHost);
                    
                    // Function to check if URL should be included
                    function shouldIncludeUrl(url, debugReason) {
                        if (!url || !url.trim() || url === '#' || url.startsWith('javascript:')) {
                            if (debugReason && debugInfo.filteredLinks.length < 10) {
                                debugInfo.filteredLinks.push({url: url || '(empty)', reason: 'empty_or_javascript'});
                            }
                            return false;
                        }
                        if (!url.startsWith('http')) {
                            if (debugReason && debugInfo.filteredLinks.length < 10) {
                                debugInfo.filteredLinks.push({url: url, reason: 'not_http'});
                            }
                            return false;
                        }
                        try {
                            var urlObj = new URL(url);
                            var hostname = urlObj.hostname;
                            
                            // Normalize hostnames (remove www. for comparison)
                            var normalizedHostname = hostname.replace(/^www\./, '');
                            var normalizedExcludeHost = excludeHost ? excludeHost.replace(/^www\./, '') : '';
                            
                            // Exclude current domain (with normalized comparison)
                            if (excludeHost && (normalizedHostname === normalizedExcludeHost || 
                                normalizedHostname.includes(normalizedExcludeHost) || 
                                normalizedExcludeHost.includes(normalizedHostname))) {
                                if (debugReason && debugInfo.filteredLinks.length < 10) {
                                    debugInfo.filteredLinks.push({
                                        url: url, 
                                        reason: 'same_domain', 
                                        hostname: hostname, 
                                        normalizedHostname: normalizedHostname,
                                        excludeHost: excludeHost,
                                        normalizedExcludeHost: normalizedExcludeHost
                                    });
                                }
                                return false;
                            }
                            // Exclude ad network domains
                            if (hostname.includes('googlesyndication') || 
                                hostname.includes('doubleclick') || 
                                hostname.includes('googleadservices') ||
                                hostname.includes('pagead') ||
                                hostname.includes('adservice') ||
                                (hostname.includes('google.com') && !hostname.includes('googleusercontent'))) {
                                if (debugReason && debugInfo.filteredLinks.length < 10) {
                                    debugInfo.filteredLinks.push({url: url, reason: 'ad_network', hostname: hostname});
                                }
                                return false;
                            }
                            return true;
                        } catch(e) {
                            // If URL parsing fails, include it (might be relative URL that will be resolved)
                            if (debugReason && debugInfo.filteredLinks.length < 10) {
                                debugInfo.filteredLinks.push({url: url, reason: 'parse_error', error: e.message});
                            }
                            return true;
                        }
                    }
                    
                    // Function to extract URL from onclick handler
                    function extractUrlFromOnclick(onclickStr) {
                        if (!onclickStr) return null;
                        // Try to find URL patterns in onclick
                        var patterns = [
                            /window\.open\(['"]([^'"]+)['"]/,
                            /location\.href\s*=\s*['"]([^'"]+)['"]/,
                            /window\.location\s*=\s*['"]([^'"]+)['"]/,
                            /https?:\/\/[^\s"',;\)]+/g
                        ];
                        for (var i = 0; i < patterns.length; i++) {
                            var match = onclickStr.match(patterns[i]);
                            if (match) {
                                if (Array.isArray(match)) {
                                    return match.filter(function(m) { return m && m.startsWith('http'); });
                                } else if (match[1]) {
                                    return [match[1]];
                                }
                            }
                        }
                        return null;
                    }
                    
                    // 1. Get ALL links on page (comprehensive search)
                    var allLinks = document.querySelectorAll('a[href]');
                    debugInfo.totalLinksFound = allLinks.length;
                    console.log('DEBUG: Found ' + allLinks.length + ' total links on page');
                    
                    allLinks.forEach(function(a) {
                        try {
                            var href = a.href || a.getAttribute('href') || '';
                            if (href && href.trim() && href.startsWith('http')) {
                                // Store sample links for debugging
                                if (debugInfo.sampleLinks.length < 10) {
                                    debugInfo.sampleLinks.push(href);
                                }
                                // First, add ALL http links (for debugging)
                                if (shouldIncludeUrl(href, true)) {
                                    links.push(href.trim());
                                }
                            }
                            // Also check onclick for URLs
                            var onclick = a.getAttribute('onclick') || (a.onclick ? a.onclick.toString() : '') || '';
                            if (onclick) {
                                var urls = extractUrlFromOnclick(onclick);
                                if (urls) {
                                    urls.forEach(function(url) {
                                        if (shouldIncludeUrl(url, false)) {
                                            links.push(url.trim());
                                        }
                                    });
                                }
                            }
                        } catch(e) {
                            debugInfo.errors.push('Error processing link: ' + e.message);
                        }
                    });
                    debugInfo.linksAfterFilter = links.length;
                    
                    // 2. Find links that visually overlap with ad iframes (like browser context menu does)
                    var iframes = document.querySelectorAll('iframe[src*="googlesyndication"], iframe[src*="doubleclick"], iframe[src*="pagead"], iframe[src*="googleadservices"]');
                    debugInfo.iframesFound = iframes.length;
                    console.log('DEBUG: Found ' + iframes.length + ' ad iframes');
                    
                    iframes.forEach(function(iframe) {
                        try {
                            var iframeRect = iframe.getBoundingClientRect();
                            
                            // Find all links that visually overlap with this iframe
                            var allPageLinks = document.querySelectorAll('a[href]');
                            allPageLinks.forEach(function(link) {
                                try {
                                    var linkRect = link.getBoundingClientRect();
                                    // Check if link overlaps with iframe (like browser does for context menu)
                                    var overlaps = !(linkRect.right < iframeRect.left || 
                                                    linkRect.left > iframeRect.right || 
                                                    linkRect.bottom < iframeRect.top || 
                                                    linkRect.top > iframeRect.bottom);
                                    
                                    if (overlaps) {
                                        var href = link.href || link.getAttribute('href') || '';
                                        if (shouldIncludeUrl(href, false)) {
                                            links.push(href.trim());
                                        }
                                        // Also check onclick for URLs
                                        var onclick = link.getAttribute('onclick') || link.onclick?.toString() || '';
                                        if (onclick) {
                                            var urls = extractUrlFromOnclick(onclick);
                                            if (urls) {
                                                urls.forEach(function(url) {
                                                    if (shouldIncludeUrl(url)) {
                                                        links.push(url.trim());
                                                    }
                                                });
                                            }
                                        }
                                    }
                                } catch(e) {}
                            });
                            
                            // Also check if iframe itself or its parent has onclick handlers with URLs
                            var iframeOnclick = iframe.getAttribute('onclick') || '';
                            if (iframeOnclick) {
                                var urls = extractUrlFromOnclick(iframeOnclick);
                                if (urls) {
                                    urls.forEach(function(url) {
                                        if (shouldIncludeUrl(url, false)) {
                                            links.push(url.trim());
                                        }
                                    });
                                }
                            }
                            
                            // Check parent elements for onclick handlers
                            var parent = iframe.parentElement;
                            var parentLevel = 0;
                            while (parent && parentLevel < 5) {
                                var parentOnclick = parent.getAttribute('onclick') || '';
                                if (parentOnclick) {
                                    var urls = extractUrlFromOnclick(parentOnclick);
                                    if (urls) {
                                        urls.forEach(function(url) {
                                            if (shouldIncludeUrl(url)) {
                                                links.push(url.trim());
                                            }
                                        });
                                    }
                                }
                                parent = parent.parentElement;
                                parentLevel++;
                            }
                            
                            // 3. Check all parent elements up to 5 levels for links
                            var parent = iframe.parentElement;
                            var level = 0;
                            while (parent && level < 5) {
                                // Check if parent itself is a link
                                if (parent.tagName === 'A' && parent.href) {
                                    var href = parent.href || parent.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                }
                                // Check all links inside parent
                                var parentLinks = parent.querySelectorAll('a[href]');
                                parentLinks.forEach(function(a) {
                                    var href = a.href || a.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                });
                                parent = parent.parentElement;
                                level++;
                            }
                            
                            // 4. Check siblings (next, previous, and all siblings)
                            var siblings = [];
                            var next = iframe.nextElementSibling;
                            while (next && siblings.length < 3) {
                                siblings.push(next);
                                next = next.nextElementSibling;
                            }
                            var prev = iframe.previousElementSibling;
                            while (prev && siblings.length < 6) {
                                siblings.push(prev);
                                prev = prev.previousElementSibling;
                            }
                            
                            siblings.forEach(function(sibling) {
                                if (sibling.tagName === 'A' && sibling.href) {
                                    var href = sibling.href || sibling.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                }
                                var siblingLinks = sibling.querySelectorAll('a[href]');
                                siblingLinks.forEach(function(a) {
                                    var href = a.href || a.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                });
                            });
                        } catch(e) {}
                    });
                    
                    // 5. Extract from ad containers
                    var adContainers = document.querySelectorAll('ins.adsbygoogle, .ad, .ads, .advertisement, [class*="ad-"], [id*="ad-"], [data-ad-slot]');
                    adContainers.forEach(function(container) {
                        // Check if container itself is a link
                        if (container.tagName === 'A' && container.href) {
                            var href = container.href || container.getAttribute('href') || '';
                            if (shouldIncludeUrl(href, false)) {
                                links.push(href.trim());
                            }
                        }
                        // Get all links inside container
                        var containerLinks = container.querySelectorAll('a[href]');
                        containerLinks.forEach(function(a) {
                            var href = a.href || a.getAttribute('href') || '';
                            if (shouldIncludeUrl(href, false)) {
                                links.push(href.trim());
                            }
                        });
                    });
                    
                    // 6. Extract from elements with onclick handlers that might contain URLs
                    var clickableElements = document.querySelectorAll('[onclick], [data-onclick]');
                    clickableElements.forEach(function(el) {
                        var onclick = el.getAttribute('onclick') || el.getAttribute('data-onclick') || '';
                        if (onclick) {
                            var urls = extractUrlFromOnclick(onclick);
                            if (urls) {
                                urls.forEach(function(url) {
                                    if (shouldIncludeUrl(url)) {
                                        links.push(url.trim());
                                    }
                                });
                            }
                        }
                    });
                    
                    // 7. Try to get links from iframe content (if same-origin)
                    iframes.forEach(function(iframe) {
                        try {
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow?.document;
                            if (iframeDoc) {
                                var iframeLinks = iframeDoc.querySelectorAll('a[href]');
                                iframeLinks.forEach(function(a) {
                                    var href = a.href || a.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                });
                            }
                        } catch(e) {
                            // Same-origin policy - can't access
                        }
                        
                        // Extract from data attributes on iframe and its parents
                        var dataAttrs = ['data-ad-url', 'data-adurl', 'data-dest-url', 'data-redirect', 'data-click-url', 'data-url', 'data-href', 'data-link'];
                        dataAttrs.forEach(function(attr) {
                            var value = iframe.getAttribute(attr);
                            if (value && shouldIncludeUrl(value, false)) {
                                links.push(value.trim());
                            }
                        });
                        
                        // Check parent elements for data attributes
                        var parent = iframe.parentElement;
                        var parentLevel = 0;
                        while (parent && parentLevel < 5) {
                            dataAttrs.forEach(function(attr) {
                                var value = parent.getAttribute(attr);
                                if (value && shouldIncludeUrl(value)) {
                                    links.push(value.trim());
                                }
                            });
                            parent = parent.parentElement;
                            parentLevel++;
                        }
                    });
                    
                    // 8. Extract from global JavaScript variables (Google AdSense might store URLs here)
                    try {
                        if (window.google_ads_iframe_onload || window.google_ads_iframe_loaded) {
                            // Search for URLs in window object
                            for (var key in window) {
                                try {
                                    var value = window[key];
                                    if (typeof value === 'string' && value.startsWith('http') && shouldIncludeUrl(value, false)) {
                                        links.push(value.trim());
                                    } else if (typeof value === 'object' && value !== null) {
                                        try {
                                            var jsonStr = JSON.stringify(value);
                                            var urlMatches = jsonStr.match(/https?:\/\/[^\s"',;\)]+/g);
                                            if (urlMatches) {
                                                urlMatches.forEach(function(url) {
                                                    if (shouldIncludeUrl(url)) {
                                                        links.push(url.trim());
                                                    }
                                                });
                                            }
                                        } catch(e) {}
                                    }
                                } catch(e) {}
                            }
                        }
                    } catch(e) {}
                    
                    // 9. Find ALL links on page (including hidden ones) - this mimics "copy link address" behavior
                    var allLinksOnPage = document.querySelectorAll('a[href]');
                    allLinksOnPage.forEach(function(a) {
                        try {
                            var href = a.href || a.getAttribute('href') || '';
                            if (href && href.startsWith('http')) {
                                // Check if this link is near an ad iframe or overlaps with it
                                var linkRect = a.getBoundingClientRect();
                                var nearAd = false;
                                var overlapsAd = false;
                                
                                iframes.forEach(function(iframe) {
                                    try {
                                        var iframeRect = iframe.getBoundingClientRect();
                                        
                                        // Check if link overlaps with iframe (like browser does for context menu)
                                        var overlaps = !(linkRect.right < iframeRect.left || 
                                                        linkRect.left > iframeRect.right || 
                                                        linkRect.bottom < iframeRect.top || 
                                                        linkRect.top > iframeRect.bottom);
                                        
                                        if (overlaps) {
                                            overlapsAd = true;
                                        }
                                        
                                        // Check if link is within 100px of iframe (broader search)
                                        var centerX = (linkRect.left + linkRect.right) / 2;
                                        var centerY = (linkRect.top + linkRect.bottom) / 2;
                                        var iframeCenterX = (iframeRect.left + iframeRect.right) / 2;
                                        var iframeCenterY = (iframeRect.top + iframeRect.bottom) / 2;
                                        var distance = Math.sqrt(
                                            Math.pow(centerX - iframeCenterX, 2) + 
                                            Math.pow(centerY - iframeCenterY, 2)
                                        );
                                        if (distance < 100) {
                                            nearAd = true;
                                        }
                                    } catch(e) {}
                                });
                                
                                // Include if it overlaps with ad, is near ad, or contains ad-related keywords
                                // CRITICAL: If link overlaps or is near iframe, it's likely an ad link (like "copy link address" in browser)
                                if (overlapsAd || nearAd || href.includes('adurl') || href.includes('googleads') || 
                                    href.includes('doubleclick') || href.includes('googlesyndication')) {
                                    if (shouldIncludeUrl(href)) {
                                        links.push(href.trim());
                                    }
                                } else {
                                    // Also check if it's an external link (might be ad URL without adurl parameter)
                                    try {
                                        var urlObj = new URL(href);
                                        var hostname = urlObj.hostname;
                                        // If it's external and not from ad network, might be ad URL
                                        if (excludeHost && hostname !== excludeHost && 
                                            !hostname.includes(excludeHost) && !excludeHost.includes(hostname) &&
                                            !hostname.includes('googlesyndication') && 
                                            !hostname.includes('doubleclick') &&
                                            !hostname.includes('googleadservices') &&
                                            !hostname.includes('pagead') &&
                                            !hostname.includes('adservice')) {
                                            // Check if link is in a container that might be an ad OR if it's visually near an iframe
                                            var parent = a.parentElement;
                                            var depth = 0;
                                            var inAdContainer = false;
                                            while (parent && depth < 5) {
                                                var className = parent.className || '';
                                                var id = parent.id || '';
                                                if (className.includes('ad') || id.includes('ad') || 
                                                    className.includes('ads') || id.includes('ads') ||
                                                    parent.tagName === 'INS' && parent.classList.contains('adsbygoogle')) {
                                                    inAdContainer = true;
                                                    break;
                                                }
                                                parent = parent.parentElement;
                                                depth++;
                                            }
                                            // If in ad container OR near iframe (already checked above), include it
                                            if ((inAdContainer || nearAd || overlapsAd) && shouldIncludeUrl(href, false)) {
                                                links.push(href.trim());
                                            }
                                        }
                                    } catch(e) {
                                        // If URL parsing fails but link looks external, might still be ad URL
                                        if (href.startsWith('http') && excludeHost && !href.includes(excludeHost) &&
                                            !href.includes('googlesyndication') && !href.includes('doubleclick') &&
                                            !href.includes('pagead') && (nearAd || overlapsAd)) {
                                            if (shouldIncludeUrl(href)) {
                                                links.push(href.trim());
                                            }
                                        }
                                    }
                                }
                            }
                        } catch(e) {}
                    });
                    
                    // 10. Search in shadow DOM (if available)
                    try {
                        var walker = document.createTreeWalker(
                            document.body,
                            NodeFilter.SHOW_ELEMENT,
                            null,
                            false
                        );
                        var node;
                        while (node = walker.nextNode()) {
                            if (node.shadowRoot) {
                                var shadowLinks = node.shadowRoot.querySelectorAll('a[href]');
                                shadowLinks.forEach(function(a) {
                                    var href = a.href || a.getAttribute('href') || '';
                                    if (href && shouldIncludeUrl(href, false)) {
                                        links.push(href.trim());
                                    }
                                });
                            }
                        }
                    } catch(e) {}
                    
                    // Remove duplicates and return
                    var uniqueLinks = [...new Set(links)];
                    debugInfo.linksAfterFilter = uniqueLinks.length;
                    console.log('DEBUG: Total unique links found:', uniqueLinks.length);
                    console.log('DEBUG: Sample links (first 10):', debugInfo.sampleLinks);
                    console.log('DEBUG: Filtered links (first 10):', debugInfo.filteredLinks);
                    console.log('DEBUG: Debug info:', JSON.stringify(debugInfo));
                    
                    // Return both links and debug info
                    return JSON.stringify({
                        links: uniqueLinks,
                        debug: debugInfo
                    });
                } catch(e) {
                    console.error('extractAllPageLinks error:', e);
                    return JSON.stringify({
                        links: [],
                        debug: { error: e.message, stack: e.stack }
                    });
                }
            })();
        """.trimIndent()
        
        val result = browser.evaluateJavascript(script)
        logAndSend("debug", TAG, "JavaScript extraction result (raw): ${result?.take(500)}")
        
        return try {
            if (result.isNullOrEmpty() || result == "null") {
                logAndSend("warn", TAG, "JavaScript returned null or empty result")
                emptyList()
            } else {
                // Parse JSON properly
                val cleanResult = result.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")
                
                logAndSend("debug", TAG, "JavaScript result (cleaned): ${cleanResult.take(500)}")
                
                // Try to parse as object with links and debug
                if (cleanResult.startsWith("{") && cleanResult.contains("\"links\"")) {
                    val jsonObject = org.json.JSONObject(cleanResult)
                    val linksArray = jsonObject.getJSONArray("links")
                    val debugInfo = jsonObject.optJSONObject("debug")
                    
                    if (debugInfo != null) {
                        val totalLinks = debugInfo.optInt("totalLinksFound", 0)
                        val iframes = debugInfo.optInt("iframesFound", 0)
                        val linksAfterFilter = debugInfo.optInt("linksAfterFilter", 0)
                        logAndSend("info", TAG, "JavaScript debug: totalLinks=$totalLinks, iframes=$iframes, linksAfterFilter=$linksAfterFilter")
                        
                        // Log sample links
                        val sampleLinks = debugInfo.optJSONArray("sampleLinks")
                        if (sampleLinks != null && sampleLinks.length() > 0) {
                            val sampleList = mutableListOf<String>()
                            for (i in 0 until minOf(sampleLinks.length(), 5)) {
                                sampleList.add(sampleLinks.getString(i))
                            }
                            logAndSend("info", TAG, "Sample links found: ${sampleList.joinToString(", ")}")
                        }
                        
                        // Log filtered links with reasons
                        val filteredLinks = debugInfo.optJSONArray("filteredLinks")
                        if (filteredLinks != null && filteredLinks.length() > 0) {
                            val filteredList = mutableListOf<String>()
                            for (i in 0 until minOf(filteredLinks.length(), 5)) {
                                val filtered = filteredLinks.getJSONObject(i)
                                val url = filtered.optString("url", "").take(80)
                                val reason = filtered.optString("reason", "unknown")
                                filteredList.add("$url ($reason)")
                            }
                            logAndSend("info", TAG, "Sample filtered links: ${filteredList.joinToString("; ")}")
                        }
                    }
                    
                    val links = mutableListOf<String>()
                    for (i in 0 until linksArray.length()) {
                        val value = linksArray.getString(i)
                        if (value.isNotEmpty() && value.startsWith("http")) {
                            links.add(value)
                        }
                    }
                    logAndSend("info", TAG, "Extracted ${links.size} links from JavaScript")
                    links
                } else if (cleanResult.startsWith("[") && cleanResult.endsWith("]")) {
                    // Fallback: parse as simple array
                    val jsonArray = org.json.JSONArray(cleanResult)
                    val links = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        val value = jsonArray.getString(i)
                        if (value.isNotEmpty() && value.startsWith("http")) {
                            links.add(value)
                        }
                    }
                    logAndSend("info", TAG, "Extracted ${links.size} links from JavaScript (simple array)")
                    links
                } else {
                    logAndSend("warn", TAG, "JavaScript result doesn't match expected format: ${cleanResult.take(200)}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            logAndSend("error", TAG, "Failed to parse JavaScript result: ${e.message}")
            Log.e(TAG, "Failed to parse JavaScript result: ${e.message}", e)
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
     * Extract links from iframe content (may fail due to same-origin policy)
     */
    private suspend fun extractLinksFromIframes(browser: BrowserController, excludeDomain: String): List<String> {
        val script = """
            (function() {
                try {
                    var adUrls = [];
                    var excludeHost = '$excludeDomain';
                    
                    // Function to extract URL from various sources
                    function extractUrlFromString(str) {
                        if (!str || typeof str !== 'string') return null;
                        var urlMatch = str.match(/https?:\/\/[^\s"',;\)]+/g);
                        if (urlMatch && urlMatch.length > 0) {
                            return urlMatch.filter(function(url) {
                                return !url.includes('googlesyndication') && 
                                       !url.includes('doubleclick') && 
                                       !url.includes('pagead') &&
                                       !url.includes('googleadservices') &&
                                       !url.includes('google.com/search') &&
                                       !url.includes('google.com/url');
                            });
                        }
                        return null;
                    }
                    
                    // Function to check if URL should be excluded
                    function shouldIncludeUrl(url) {
                        if (!url || !url.startsWith('http')) return false;
                        try {
                            var urlObj = new URL(url);
                            var hostname = urlObj.hostname;
                            if (excludeHost && (hostname === excludeHost || hostname.includes(excludeHost) || excludeHost.includes(hostname))) {
                                return false;
                            }
                            // Exclude ad network domains
                            if (hostname.includes('googlesyndication') || 
                                hostname.includes('doubleclick') || 
                                hostname.includes('googleadservices') ||
                                hostname.includes('google.com')) {
                                return false;
                            }
                            return true;
                        } catch(e) {
                            return true;
                        }
                    }
                    
                    // 1. Extract from iframe elements and their parents
                    var iframes = document.querySelectorAll('iframe[src*="googlesyndication"], iframe[src*="doubleclick"], iframe[src*="pagead"], iframe[src*="googleadservices"]');
                    iframes.forEach(function(iframe) {
                        try {
                            // Try to access iframe content (may fail due to same-origin policy)
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow?.document;
                            if (iframeDoc) {
                                // Get all links from iframe
                                var iframeLinks = iframeDoc.querySelectorAll('a[href]');
                                iframeLinks.forEach(function(a) {
                                    var href = a.href || a.getAttribute('href') || '';
                                    if (shouldIncludeUrl(href)) {
                                        adUrls.push(href.trim());
                                    }
                                });
                            }
                        } catch(e) {
                            // Same-origin policy - can't access iframe content
                        }
                        
                        // Get data attributes from iframe
                        var dataAttrs = ['data-ad-url', 'data-adurl', 'data-dest-url', 'data-redirect', 'data-click-url', 'data-url'];
                        dataAttrs.forEach(function(attr) {
                            var value = iframe.getAttribute(attr);
                            if (value && shouldIncludeUrl(value)) {
                                adUrls.push(value);
                            }
                        });
                        
                        // Extract from onclick handler
                        var onclick = iframe.getAttribute('onclick');
                        if (onclick) {
                            var urls = extractUrlFromString(onclick);
                            if (urls) {
                                urls.forEach(function(url) {
                                    if (shouldIncludeUrl(url)) {
                                        adUrls.push(url);
                                    }
                                });
                            }
                        }
                        
                        // Check parent elements for ad URLs
                        var parent = iframe.parentElement;
                        var depth = 0;
                        while (parent && depth < 3) {
                            // Check parent data attributes
                            dataAttrs.forEach(function(attr) {
                                var value = parent.getAttribute(attr);
                                if (value && shouldIncludeUrl(value)) {
                                    adUrls.push(value);
                                }
                            });
                            
                            // Check parent onclick
                            var parentOnclick = parent.getAttribute('onclick');
                            if (parentOnclick) {
                                var urls = extractUrlFromString(parentOnclick);
                                if (urls) {
                                    urls.forEach(function(url) {
                                        if (shouldIncludeUrl(url)) {
                                            adUrls.push(url);
                                        }
                                    });
                                }
                            }
                            
                            // Check for links adjacent to or wrapping the iframe
                            var adjacentLinks = parent.querySelectorAll('a[href]');
                            adjacentLinks.forEach(function(a) {
                                var href = a.href || a.getAttribute('href') || '';
                                if (shouldIncludeUrl(href)) {
                                    adUrls.push(href);
                                }
                            });
                            
                            parent = parent.parentElement;
                            depth++;
                        }
                    });
                    
                    // 2. Extract from Google AdSense containers
                    var adContainers = document.querySelectorAll('ins.adsbygoogle, .adsbygoogle, [data-ad-slot], [data-ad-client], [class*="ad-"], [id*="ad-"], [class*="ads-"], [id*="ads-"]');
                    adContainers.forEach(function(container) {
                        // Check data attributes
                        var attrs = ['data-ad-url', 'data-adurl', 'data-dest-url', 'data-redirect', 'data-click-url', 'data-url', 'data-href'];
                        attrs.forEach(function(attr) {
                            var value = container.getAttribute(attr);
                            if (value && shouldIncludeUrl(value)) {
                                adUrls.push(value);
                            }
                        });
                        
                        // Check onclick handler
                        var onclick = container.getAttribute('onclick');
                        if (onclick) {
                            var urls = extractUrlFromString(onclick);
                            if (urls) {
                                urls.forEach(function(url) {
                                    if (shouldIncludeUrl(url)) {
                                        adUrls.push(url);
                                    }
                                });
                            }
                        }
                        
                        // Check for links inside container
                        var links = container.querySelectorAll('a[href]');
                        links.forEach(function(a) {
                            var href = a.href || a.getAttribute('href') || '';
                            if (shouldIncludeUrl(href)) {
                                adUrls.push(href);
                            }
                        });
                        
                        // Check for links adjacent to container
                        var nextSibling = container.nextElementSibling;
                        if (nextSibling && nextSibling.tagName === 'A') {
                            var href = nextSibling.href || nextSibling.getAttribute('href') || '';
                            if (shouldIncludeUrl(href)) {
                                adUrls.push(href);
                            }
                        }
                    });
                    
                    // 3. Extract from elements with ad-related click handlers
                    var clickableAds = document.querySelectorAll('[onclick*="http"], [onclick*="adurl"], [onclick*="redirect"], [data-onclick]');
                    clickableAds.forEach(function(element) {
                        var onclick = element.getAttribute('onclick') || element.getAttribute('data-onclick') || '';
                        if (onclick) {
                            var urls = extractUrlFromString(onclick);
                            if (urls) {
                                urls.forEach(function(url) {
                                    if (shouldIncludeUrl(url)) {
                                        adUrls.push(url);
                                    }
                                });
                            }
                        }
                    });
                    
                    // 4. Try to extract from window object (if ad scripts expose URLs)
                    try {
                        if (window.google_ads_iframe_onload) {
                            // Google AdSense might expose some data
                            for (var key in window) {
                                try {
                                    var value = window[key];
                                    if (typeof value === 'string' && shouldIncludeUrl(value)) {
                                        adUrls.push(value);
                                    } else if (typeof value === 'object' && value !== null) {
                                        var jsonStr = JSON.stringify(value);
                                        var urls = extractUrlFromString(jsonStr);
                                        if (urls) {
                                            urls.forEach(function(url) {
                                                if (shouldIncludeUrl(url)) {
                                                    adUrls.push(url);
                                                }
                                            });
                                        }
                                    }
                                } catch(e) {}
                            }
                        }
                    } catch(e) {}
                    
                    // Remove duplicates and filter
                    var uniqueUrls = [...new Set(adUrls)];
                    var filtered = uniqueUrls.filter(shouldIncludeUrl);
                    
                    return JSON.stringify(filtered);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })();
        """.trimIndent()
        
        val result = browser.evaluateJavascript(script) ?: "[]"
        
        return try {
            val jsonArray = org.json.JSONArray(result)
            val urls = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val url = jsonArray.getString(i)
                if (url.isNotEmpty()) {
                    urls.add(url)
                }
            }
            Log.d(TAG, "Extracted ${urls.size} URLs from iframes and ad containers")
            urls
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse iframe extraction result: ${e.message}")
            emptyList()
        }
    }

    /**
     * Send task result to backend
     */
    private suspend fun sendResultToBackend(taskId: String, result: TaskResult) {
        try {
            // Log what we're about to send
            val adUrls = result.data?.get("ad_urls")
            val adLinks = result.data?.get("ad_links")
            val adDomains = result.data?.get("ad_domains")
            
            Log.i(TAG, "=== PREPARING TO SEND TASK RESULT ===")
            Log.i(TAG, "TaskId: $taskId, DeviceId: $deviceId, Success: ${result.success}")
            Log.i(TAG, "Data keys: ${result.data?.keys?.joinToString(", ")}")
            Log.i(TAG, "ad_urls type: ${adUrls?.javaClass?.simpleName}, value: $adUrls")
            Log.i(TAG, "ad_links type: ${adLinks?.javaClass?.simpleName}, count: ${if (adLinks is List<*>) adLinks.size else "N/A"}")
            Log.i(TAG, "ad_domains type: ${adDomains?.javaClass?.simpleName}, count: ${if (adDomains is List<*>) adDomains.size else "N/A"}")
            
            if (adUrls is List<*>) {
                Log.i(TAG, "ad_urls count: ${adUrls.size}, sample: ${adUrls.take(3)}")
            } else if (adUrls != null) {
                Log.i(TAG, "ad_urls (not a list): $adUrls")
            } else {
                Log.w(TAG, "⚠️ ad_urls is NULL or missing!")
            }
            
            val request = ApiClient.TaskResultRequest(
                success = result.success,
                data = result.data,
                error = result.error,
                executionTime = result.executionTime,
                screenshots = result.data?.get("screenshots") as? List<String>
            )
            
            Log.d(TAG, "Sending task result to backend: taskId=$taskId, deviceId=$deviceId, success=${result.success}")
            val sent = apiClient.sendTaskResult(taskId, request, deviceId)
            Log.i(TAG, "Task result sent: $sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send result to backend: ${e.message}", e)
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
