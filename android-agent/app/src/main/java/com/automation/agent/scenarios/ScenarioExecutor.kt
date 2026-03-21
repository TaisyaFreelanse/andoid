package com.automation.agent.scenarios

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import com.automation.agent.automation.DataExtractor
import com.automation.agent.automation.Navigator
import com.automation.agent.automation.Parser
import com.automation.agent.automation.Screenshot
import com.automation.agent.browser.BrowserController
import com.automation.agent.browser.BrowserSelector
import com.automation.agent.browser.WebViewController
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
            val hasBrowserSteps = resolvedScenario.steps.isNotEmpty()
            val hasUniquenessActions = resolvedScenario.actions.isNotEmpty()

            when (resolvedScenario.type) {
                "parsing", "surfing", "automation" -> {
                    if (hasUniquenessActions) initializeUniqueness()
                    if (hasBrowserSteps) initializeBrowser(resolvedScenario)
                }
                "uniqueness" -> {
                    initializeUniqueness()
                }
            }

            val success = when {
                // Combined mode: actions (setup) → steps (work), actions re-run between loop iterations
                resolvedScenario.type in listOf("parsing", "surfing", "automation") && hasUniquenessActions && hasBrowserSteps -> {
                    Log.i(TAG, "Combined mode: running ${resolvedScenario.actions.size} setup actions, then ${resolvedScenario.steps.size} steps")
                    val actionsOk = executeActions(resolvedScenario.actions, resolvedScenario.config, stepResults)
                    if (!actionsOk) {
                        Log.e(TAG, "Setup actions failed, aborting scenario")
                        false
                    } else {
                        executeStepsWithUniquenessLoop(resolvedScenario, stepResults)
                    }
                }
                resolvedScenario.type in listOf("parsing", "surfing", "automation") -> {
                    executeSteps(resolvedScenario.steps, resolvedScenario.config, stepResults)
                }
                resolvedScenario.type == "uniqueness" -> {
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
                "click_text" -> executeClickByText(step)
                "javascript" -> executeJavascript(step)
                "set_cookie" -> executeSetCookie(step)
                "native_swipe" -> executeNativeSwipe(step)
                "native_tap" -> executeNativeTap(step)
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

    /**
     * Click a button/link by its visible text content.
     * Searches all buttons, links, and [role=button] elements for text that contains step.value.
     * Falls back to CSS selector in step.selector if text match fails.
     */
    private suspend fun executeClickByText(step: Step): Boolean {
        val textToFind = step.value ?: return false
        val escapedText = textToFind.replace("\\", "\\\\").replace("'", "\\'")

        // Phase 1: Find the button and scroll it into view (within its own container, NOT the page)
        val findScript = """
            (function() {
                var searchTexts = '$escapedText'.split('|');

                function findBtn(doc) {
                    var candidates = doc.querySelectorAll('button, a, [role="button"], input[type="submit"], div[role="button"]');
                    for (var t = 0; t < searchTexts.length; t++) {
                        var target = searchTexts[t].trim().toLowerCase();
                        for (var i = 0; i < candidates.length; i++) {
                            var el = candidates[i];
                            var text = (el.textContent || el.innerText || el.value || '').trim().toLowerCase();
                            if (text.indexOf(target) !== -1) return el;
                        }
                    }
                    return null;
                }

                // search main doc
                var btn = findBtn(document);

                // search same-origin iframes
                if (!btn) {
                    var iframes = document.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length; f++) {
                        try {
                            var iDoc = iframes[f].contentDocument || iframes[f].contentWindow.document;
                            if (iDoc) { btn = findBtn(iDoc); if (btn) break; }
                        } catch(e) {}
                    }
                }

                if (!btn) return 'not_found';

                // scroll the button's scrollable parent (the consent dialog container), NOT the page
                var parent = btn.parentElement;
                while (parent && parent !== document.body) {
                    var style = window.getComputedStyle(parent);
                    var overflowY = style.overflowY;
                    if ((overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') && parent.scrollHeight > parent.clientHeight) {
                        parent.scrollTop = parent.scrollHeight;
                        break;
                    }
                    parent = parent.parentElement;
                }

                // scrollIntoView within its container (instant, no animation)
                btn.scrollIntoView({behavior:'instant', block:'center'});

                // store reference for phase 2
                window.__clickTextTarget = btn;
                var rect = btn.getBoundingClientRect();
                return 'found:' + rect.x + ',' + rect.y + ',' + rect.width + ',' + rect.height;
            })();
        """.trimIndent()

        val findResult = currentBrowser?.evaluateJavascript(findScript)
        Log.d(TAG, "click_text find result: $findResult")

        if (findResult == null || findResult.contains("not_found")) {
            // Fallback to CSS selector
            if (!step.selector.isNullOrBlank()) {
                return currentBrowser?.click(step.selector) ?: false
            }
            return false
        }

        // Phase 2: Wait for scroll to complete, then click with full event simulation
        delay(500)

        val clickScript = """
            (function() {
                var btn = window.__clickTextTarget;
                if (!btn) return 'no_target';

                var rect = btn.getBoundingClientRect();
                var x = rect.left + rect.width / 2;
                var y = rect.top + rect.height / 2;

                // simulate full pointer event sequence at button coordinates
                var events = ['pointerdown','mousedown','pointerup','mouseup','click'];
                for (var i = 0; i < events.length; i++) {
                    var evt = new PointerEvent(events[i], {
                        bubbles: true, cancelable: true, view: window,
                        clientX: x, clientY: y, screenX: x, screenY: y,
                        pointerId: 1, pointerType: 'touch', isPrimary: true
                    });
                    btn.dispatchEvent(evt);
                }

                // also try direct click and form submit
                btn.click();
                if (btn.form) try { btn.form.submit(); } catch(e) {}

                // try clicking parent link/button
                var p = btn.closest('a, button, [role="button"]');
                if (p && p !== btn) p.click();

                delete window.__clickTextTarget;
                return 'clicked:' + (btn.textContent || '').trim().substring(0, 50);
            })();
        """.trimIndent()

        val clickResult = currentBrowser?.evaluateJavascript(clickScript)
        Log.d(TAG, "click_text click result: $clickResult")

        return clickResult != null && clickResult.contains("clicked:")
    }

    /**
     * Execute arbitrary JavaScript. The script goes in step.value.
     * Return value is stored in extractedData under step.saveAs (if provided).
     */
    private suspend fun executeJavascript(step: Step): Boolean {
        val script = step.value ?: return false
        val result = currentBrowser?.evaluateJavascript(script)
        Log.d(TAG, "javascript result: $result")

        step.saveAs?.let { key ->
            extractedData[key] = result ?: ""
        }

        return result != null && !result.contains("false") && !result.contains("error")
    }

    /**
     * Set cookie via Android CookieManager. Works BEFORE page navigation.
     * step.url = domain (e.g. "https://www.google.com")
     * step.value = cookie string (e.g. "SOCS=CAESEwgD...; path=/; domain=.google.com")
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun executeSetCookie(step: Step): Boolean = withContext(Dispatchers.Main) {
        val url = step.url ?: return@withContext false
        val cookieValue = step.value ?: return@withContext false

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            for (cookie in cookieValue.split(";;")) {
                val trimmed = cookie.trim()
                if (trimmed.isNotEmpty()) {
                    cookieManager.setCookie(url, trimmed)
                    Log.d(TAG, "Cookie set for $url: ${trimmed.take(60)}...")
                }
            }
            cookieManager.flush()
            Log.i(TAG, "Cookies set successfully for $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set cookie: ${e.message}")
            false
        }
    }

    /**
     * Simulate a real finger swipe on the WebView using Android MotionEvent.
     * Scrolls whatever is visually on screen (overlays, modals, consent dialogs).
     * Uses step.direction for preset swipes, or step.value for custom "startX,startY,endX,endY" (0.0-1.0).
     */
    private suspend fun executeNativeSwipe(step: Step): Boolean {
        val wvc = currentBrowser as? WebViewController ?: run {
            Log.e(TAG, "native_swipe requires WebViewController")
            return false
        }

        val direction = step.direction ?: "up"
        val repeat = step.maxIterations ?: 1

        for (i in 0 until repeat) {
            when (direction) {
                "up" -> wvc.nativeSwipe(0.5f, 0.75f, 0.5f, 0.25f, 400)
                "down" -> wvc.nativeSwipe(0.5f, 0.25f, 0.5f, 0.75f, 400)
                "left" -> wvc.nativeSwipe(0.75f, 0.5f, 0.25f, 0.5f, 400)
                "right" -> wvc.nativeSwipe(0.25f, 0.5f, 0.75f, 0.5f, 400)
                "custom" -> {
                    val coords = step.value?.split(",")?.map { it.trim().toFloatOrNull() ?: 0.5f }
                    if (coords != null && coords.size >= 4) {
                        wvc.nativeSwipe(coords[0], coords[1], coords[2], coords[3], 400)
                    }
                }
            }
            if (i < repeat - 1) delay(200)
        }
        return true
    }

    /**
     * Simulate a real finger tap on the WebView using Android MotionEvent.
     * step.value = "x,y" as percentages (0.0-1.0), e.g. "0.5,0.85" = center-X, 85% down.
     */
    private suspend fun executeNativeTap(step: Step): Boolean {
        val wvc = currentBrowser as? WebViewController ?: run {
            Log.e(TAG, "native_tap requires WebViewController")
            return false
        }

        val coords = step.value?.split(",")?.map { it.trim().toFloatOrNull() }
        val x = coords?.getOrNull(0) ?: 0.5f
        val y = coords?.getOrNull(1) ?: 0.5f

        return wvc.nativeTap(x, y)
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

    /**
     * Combined mode: runs steps, but re-executes uniqueness actions between loop iterations.
     * For top-level loops, uniqueness actions are inserted between each iteration
     * so the device gets a fresh fingerprint before each browsing cycle.
     */
    private suspend fun executeStepsWithUniquenessLoop(
        scenario: Scenario,
        results: MutableList<StepResult>
    ): Boolean {
        for (step in scenario.steps) {
            val result = if (step.type == "loop") {
                executeLoopWithUniqueness(step, scenario, results)
            } else {
                executeStep(step, scenario.config)
            }

            if (step.type == "loop") {
                // result handled inside executeLoopWithUniqueness
                continue
            }

            results.add(result)
            if (!result.success && !step.optional) {
                Log.e(TAG, "Step ${step.id} failed: ${result.error}")
                return false
            }

            if (scenario.config.humanLike && scenario.config.randomDelays) {
                delay(Random.nextLong(scenario.config.minDelay, scenario.config.maxDelay))
            }
        }
        return true
    }

    /**
     * Loop that re-runs uniqueness actions (fingerprint reset) between iterations.
     */
    private suspend fun executeLoopWithUniqueness(
        step: Step,
        scenario: Scenario,
        allResults: MutableList<StepResult>
    ): StepResult {
        val startTime = System.currentTimeMillis()
        val maxIterations = step.maxIterations ?: 10
        val nestedSteps = step.steps ?: return StepResult(
            stepId = step.id, type = step.type, success = false,
            error = "Loop has no nested steps", duration = 0
        )

        try {
            for (i in 0 until maxIterations) {
                loopIndex = i
                Log.i(TAG, "=== Loop iteration ${i + 1}/$maxIterations ===")

                // Re-run uniqueness actions before each iteration (except the first — already ran in setup)
                if (i > 0) {
                    Log.i(TAG, "Re-running uniqueness actions for fresh fingerprint (iteration ${i + 1})")
                    val actionsOk = executeActions(scenario.actions, scenario.config, allResults)
                    if (!actionsOk) {
                        Log.e(TAG, "Uniqueness actions failed at iteration ${i + 1}")
                        break
                    }
                }

                val iterResults = mutableListOf<StepResult>()
                val success = executeSteps(nestedSteps, scenario.config, iterResults)
                allResults.addAll(iterResults)

                if (!success) {
                    Log.w(TAG, "Loop iteration ${i + 1} failed, stopping loop")
                    break
                }

                Log.i(TAG, "=== Loop iteration ${i + 1} completed ===")
            }
        } catch (e: LoopBreakException) {
            Log.i(TAG, "Loop broken by condition")
        }

        return StepResult(
            stepId = step.id, type = "loop", success = true,
            duration = System.currentTimeMillis() - startTime
        )
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

