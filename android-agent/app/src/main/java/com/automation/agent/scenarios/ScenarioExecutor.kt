package com.automation.agent.scenarios

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.automation.agent.automation.DataExtractor
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
import kotlin.random.Random

/**
 * ScenarioExecutor - Executes parsed scenarios
 * 
 * Supports:
 * - Parsing scenarios (extract data from pages)
 * - Surfing scenarios (simulate user browsing)
 * - Automation scenarios (form filling, clicks)
 * - Uniqueness scenarios (device fingerprint changes)
 */
class ScenarioExecutor(
    private val context: Context,
    private val apiClient: ApiClient? = null,
    private val proxyManager: ProxyManager? = null
) {

    companion object {
        private const val TAG = "ScenarioExecutor"
    }

    private val browserSelector = BrowserSelector(context, proxyManager)
    private val rootUtils = RootUtils()
    private val geoHelper = GeoLocationHelper()
    private val screenshotUtil = Screenshot(context)
    
    private var currentBrowser: BrowserController? = null
    private var navigator: Navigator? = null
    private var parser: Parser? = null
    private var dataExtractor: DataExtractor? = null
    private var uniquenessService: UniquenessService? = null

    // Execution state
    private val extractedData = mutableMapOf<String, Any>()
    private val screenshots = mutableMapOf<String, Bitmap>()
    private val visitedUrls = mutableListOf<String>()
    private var loopIndex = 0

    // ==================== Execution ====================

    /**
     * Execute scenario
     */
    suspend fun execute(scenario: Scenario, variables: Map<String, String> = emptyMap()): ExecutionResult {
        Log.i(TAG, "Executing scenario: ${scenario.name} (${scenario.id})")
        
        val startTime = System.currentTimeMillis()
        val stepResults = mutableListOf<StepResult>()
        
        // Apply variables
        val parser = ScenarioParser(context)
        val resolvedScenario = parser.applyVariables(scenario, variables)
        
        // Validate scenario
        val validation = parser.validateScenario(resolvedScenario)
        if (!validation.isValid) {
            return ExecutionResult(
                success = false,
                scenarioId = scenario.id,
                error = "Validation failed: ${validation.errors.joinToString()}",
                duration = System.currentTimeMillis() - startTime
            )
        }
        
        try {
            // Initialize based on scenario type
            when (resolvedScenario.type) {
                "parsing", "surfing", "automation" -> {
                    initializeBrowser(resolvedScenario)
                }
                "uniqueness" -> {
                    initializeUniqueness()
                }
            }
            
            // Execute steps or actions
            val success = when (resolvedScenario.type) {
                "parsing", "surfing", "automation" -> {
                    executeSteps(resolvedScenario.steps, resolvedScenario.config, stepResults)
                }
                "uniqueness" -> {
                    executeActions(resolvedScenario.actions, resolvedScenario.config, stepResults)
                }
                else -> {
                    Log.e(TAG, "Unknown scenario type: ${resolvedScenario.type}")
                    false
                }
            }
            
            // Post-processing
            if (success) {
                executePostProcess(resolvedScenario.postProcess)
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            return ExecutionResult(
                success = success,
                scenarioId = scenario.id,
                stepResults = stepResults,
                extractedData = extractedData.toMap(),
                screenshots = screenshots.mapValues { screenshotUtil.bitmapToBase64(it.value) },
                visitedUrls = visitedUrls.toList(),
                duration = duration
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Scenario execution failed: ${e.message}", e)
            
            return ExecutionResult(
                success = false,
                scenarioId = scenario.id,
                stepResults = stepResults,
                error = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        } finally {
            cleanup()
        }
    }

    // ==================== Initialization ====================

    private suspend fun initializeBrowser(scenario: Scenario) {
        currentBrowser = browserSelector.selectBrowser(scenario.browser)
        currentBrowser?.initialize()
        
        navigator = Navigator(currentBrowser!!)
        parser = Parser(currentBrowser!!)
        dataExtractor = DataExtractor(currentBrowser!!, apiClient)
    }

    private fun initializeUniqueness() {
        uniquenessService = UniquenessService(context, rootUtils)
    }

    // ==================== Step Execution ====================

    private suspend fun executeSteps(
        steps: List<Step>,
        config: ScenarioConfig,
        results: MutableList<StepResult>
    ): Boolean {
        for (step in steps) {
            val result = executeStep(step, config)
            results.add(result)
            
            if (!result.success && !step.optional) {
                Log.e(TAG, "Step ${step.id} failed: ${result.error}")
                return false
            }
            
            // Random delay for human-like behavior
            if (config.humanLike && config.randomDelays) {
                val delay = Random.nextLong(config.minDelay, config.maxDelay)
                delay(delay)
            }
        }
        
        return true
    }

    private suspend fun executeStep(step: Step, config: ScenarioConfig): StepResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.d(TAG, "Executing step: ${step.id} (${step.type})")
            
            val success = when (step.type) {
                "navigate" -> executeNavigate(step)
                "wait" -> executeWait(step)
                "click" -> executeClick(step)
                "input" -> executeInput(step, config)
                "scroll" -> executeScroll(step)
                "extract" -> executeExtract(step)
                "screenshot" -> executeScreenshot(step)
                "wait_for_element" -> executeWaitForElement(step)
                "loop" -> executeLoop(step, config)
                "condition" -> executeCondition(step)
                else -> {
                    Log.w(TAG, "Unknown step type: ${step.type}")
                    false
                }
            }
            
            StepResult(
                stepId = step.id,
                type = step.type,
                success = success,
                duration = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Step ${step.id} failed: ${e.message}")
            
            StepResult(
                stepId = step.id,
                type = step.type,
                success = false,
                error = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        }
    }

    private suspend fun executeNavigate(step: Step): Boolean {
        val url = step.url ?: return false
        
        navigator?.navigate(url)
        visitedUrls.add(url)
        
        if (step.waitForLoad) {
            val timeout = step.timeout ?: 30000
            return navigator?.waitForPageLoad(timeout) ?: false
        }
        
        return true
    }

    private suspend fun executeWait(step: Step): Boolean {
        var duration = step.duration ?: 1000
        
        // Add random offset if specified
        step.randomOffset?.let { offset ->
            duration += Random.nextLong(-offset, offset)
        }
        
        navigator?.wait(duration.coerceAtLeast(0))
        return true
    }

    private suspend fun executeClick(step: Step): Boolean {
        val selector = step.selector ?: return false
        return navigator?.click(selector) ?: false
    }

    private suspend fun executeInput(step: Step, config: ScenarioConfig): Boolean {
        val selector = step.selector ?: return false
        val value = step.value ?: ""
        
        if (step.clearBefore) {
            navigator?.clear(selector)
            delay(100)
        }
        
        // Human-like typing
        if (config.humanLikeTyping) {
            for (char in value) {
                navigator?.input(selector, char.toString())
                delay(config.typingDelayMs)
            }
            return true
        }
        
        return navigator?.input(selector, value) ?: false
    }

    private suspend fun executeScroll(step: Step): Boolean {
        return when {
            step.percent != null -> {
                navigator?.scrollByPercent(step.percent) ?: false
            }
            step.direction != null && step.pixels != null -> {
                navigator?.scroll(step.direction, step.pixels) ?: false
            }
            step.direction != null -> {
                val pixels = if (step.direction == "down") 500 else -500
                navigator?.scroll(step.direction, kotlin.math.abs(pixels)) ?: false
            }
            else -> false
        }
    }

    private suspend fun executeExtract(step: Step): Boolean {
        val selector = step.selector ?: return false
        val saveAs = step.saveAs ?: "extracted_data"
        
        val data = if (step.multiple) {
            if (step.attribute != null) {
                parser?.extractByCss(selector, step.attribute) ?: emptyList()
            } else {
                parser?.extractByCss(selector) ?: emptyList()
            }
        } else {
            val value = if (step.attribute != null) {
                parser?.extractFirstByCss(selector, step.attribute)
            } else {
                parser?.extractFirstByCss(selector)
            }
            listOfNotNull(value)
        }
        
        // Append or replace
        if (step.append && extractedData.containsKey(saveAs)) {
            val existing = extractedData[saveAs]
            if (existing is List<*>) {
                @Suppress("UNCHECKED_CAST")
                extractedData[saveAs] = (existing as List<String>) + data
            }
        } else {
            extractedData[saveAs] = data
        }
        
        Log.d(TAG, "Extracted ${data.size} items for '$saveAs'")
        return true
    }

    private suspend fun executeScreenshot(step: Step): Boolean {
        val saveAs = step.saveAs ?: "screenshot_${System.currentTimeMillis()}"
        
        val bitmap = if (step.fullPage) {
            currentBrowser?.takeFullPageScreenshot()
        } else {
            currentBrowser?.takeScreenshot()
        }
        
        bitmap?.let {
            screenshots[saveAs] = it
            Log.d(TAG, "Screenshot saved as '$saveAs'")
            return true
        }
        
        return false
    }

    private suspend fun executeWaitForElement(step: Step): Boolean {
        val selector = step.selector ?: return false
        val timeout = step.timeout ?: 10000
        
        return navigator?.waitForElement(selector, timeout) ?: false
    }

    private suspend fun executeLoop(step: Step, config: ScenarioConfig): Boolean {
        val maxIterations = step.maxIterations ?: 10
        val nestedSteps = step.steps ?: return false
        
        for (i in 0 until maxIterations) {
            loopIndex = i
            
            val results = mutableListOf<StepResult>()
            val success = executeSteps(nestedSteps, config, results)
            
            // Check for break condition
            if (!success) {
                break
            }
        }
        
        return true
    }

    private suspend fun executeCondition(step: Step): Boolean {
        val check = step.check ?: return false
        val selector = step.selector
        
        val result = when (check) {
            "element_exists" -> {
                selector?.let { navigator?.checkElementExists(it) } ?: false
            }
            "page_loaded" -> {
                currentBrowser?.isPageLoaded() ?: false
            }
            else -> false
        }
        
        if (!result && step.onFalse == "break") {
            throw LoopBreakException()
        }
        
        return result
    }

    // ==================== Action Execution ====================

    private suspend fun executeActions(
        actions: List<Action>,
        config: ScenarioConfig,
        results: MutableList<StepResult>
    ): Boolean {
        for (action in actions) {
            val result = executeAction(action, config)
            results.add(result)
            
            if (!result.success) {
                Log.e(TAG, "Action ${action.id} failed: ${result.error}")
                return false
            }
        }
        
        return true
    }

    private suspend fun executeAction(action: Action, config: ScenarioConfig): StepResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.d(TAG, "Executing action: ${action.id} (${action.type})")
            
            val success = when (action.type) {
                "regenerate_android_id" -> uniquenessService?.regenerateAndroidId() ?: false
                "regenerate_aaid" -> uniquenessService?.regenerateAaid() ?: false
                "clear_chrome_data" -> uniquenessService?.clearChromeData() ?: false
                "clear_webview_data" -> uniquenessService?.clearWebViewData() ?: false
                "change_user_agent" -> executeChangeUserAgent(action)
                "change_timezone" -> executeChangeTimezone(action, config)
                "change_location" -> executeChangeLocation(action, config)
                "change_locale" -> executeChangeLocale(action)
                "modify_build_prop" -> executeModifyBuildProp(action)
                "detect_proxy_location" -> executeDetectProxyLocation(action)
                else -> {
                    Log.w(TAG, "Unknown action type: ${action.type}")
                    false
                }
            }
            
            StepResult(
                stepId = action.id,
                type = action.type,
                success = success,
                duration = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Action ${action.id} failed: ${e.message}")
            
            StepResult(
                stepId = action.id,
                type = action.type,
                success = false,
                error = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        }
    }

    private suspend fun executeChangeUserAgent(action: Action): Boolean {
        val ua = when (action.ua) {
            "random", null -> null // Let UniquenessService generate random
            else -> action.ua
        }
        return uniquenessService?.changeUserAgent(ua) ?: false
    }

    private suspend fun executeChangeTimezone(action: Action, config: ScenarioConfig): Boolean {
        return when {
            action.timezone == "auto" && config.useProxyGeolocation -> {
                val location = geoHelper.getCurrentIpLocation()
                location?.timezone?.let { uniquenessService?.changeTimezone(it) } ?: false
            }
            action.countryCode != null -> {
                uniquenessService?.changeTimezoneByCountry(action.countryCode) ?: false
            }
            action.timezone != null && action.timezone != "auto" -> {
                uniquenessService?.changeTimezone(action.timezone) ?: false
            }
            else -> false
        }
    }

    private suspend fun executeChangeLocation(action: Action, config: ScenarioConfig): Boolean {
        return when {
            action.latitude == "auto" && config.useProxyGeolocation -> {
                val location = geoHelper.getCurrentIpLocation()
                if (location != null) {
                    uniquenessService?.changeLocation(location.latitude, location.longitude) ?: false
                } else false
            }
            action.countryCode != null -> {
                uniquenessService?.changeLocationByCountry(action.countryCode) ?: false
            }
            action.latitude != null && action.longitude != null -> {
                val lat = action.latitude.toDoubleOrNull()
                val lng = action.longitude.toDoubleOrNull()
                if (lat != null && lng != null) {
                    uniquenessService?.changeLocation(lat, lng) ?: false
                } else false
            }
            else -> false
        }
    }

    private suspend fun executeChangeLocale(action: Action): Boolean {
        // TODO: Implement locale change
        Log.w(TAG, "change_locale not fully implemented")
        return true
    }

    private suspend fun executeModifyBuildProp(action: Action): Boolean {
        val params = action.params ?: return false
        return uniquenessService?.modifyBuildProp(params) ?: false
    }

    private suspend fun executeDetectProxyLocation(action: Action): Boolean {
        val location = geoHelper.getCurrentIpLocation()
        
        if (location != null) {
            action.saveAs?.let { key ->
                extractedData[key] = mapOf(
                    "ip" to location.ip,
                    "country" to location.countryCode,
                    "city" to location.city,
                    "timezone" to location.timezone,
                    "latitude" to location.latitude,
                    "longitude" to location.longitude
                )
            }
            return true
        }
        
        return false
    }

    // ==================== Post-Processing ====================

    private suspend fun executePostProcess(postProcess: PostProcess) {
        if (postProcess.extractAdurl) {
            val adLinks = extractedData["ad_links"]
            if (adLinks is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val urls = adLinks as List<String>
                val adUrls = urls.mapNotNull { parser?.parseAdUrl(it) }
                extractedData["parsed_adurls"] = adUrls
            }
        }
        
        if (postProcess.deduplicateDomains) {
            val adUrls = extractedData["parsed_adurls"]
            if (adUrls is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val urls = adUrls as List<String>
                val domains = parser?.deduplicateDomains(urls) ?: emptyList()
                extractedData["unique_domains"] = domains
            }
        }
        
        if (postProcess.logVisitedUrls) {
            Log.i(TAG, "Visited URLs: $visitedUrls")
        }
        
        if (postProcess.saveToBackend || postProcess.sendToBackend) {
            // Send data to backend
            Log.i(TAG, "Sending data to backend...")
        }
    }

    // ==================== Cleanup ====================

    private suspend fun cleanup() {
        try {
            currentBrowser?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
        
        currentBrowser = null
        navigator = null
        parser = null
        dataExtractor = null
        
        extractedData.clear()
        screenshots.clear()
        visitedUrls.clear()
    }

    // ==================== Data Classes ====================

    data class ExecutionResult(
        val success: Boolean,
        val scenarioId: String,
        val stepResults: List<StepResult> = emptyList(),
        val extractedData: Map<String, Any> = emptyMap(),
        val screenshots: Map<String, String> = emptyMap(), // Base64 encoded
        val visitedUrls: List<String> = emptyList(),
        val error: String? = null,
        val duration: Long = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class StepResult(
        val stepId: String,
        val type: String,
        val success: Boolean,
        val error: String? = null,
        val duration: Long = 0
    )

    private class LoopBreakException : Exception("Loop break")
}

