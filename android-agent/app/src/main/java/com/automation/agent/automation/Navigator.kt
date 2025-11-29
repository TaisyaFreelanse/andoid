package com.automation.agent.automation

import android.util.Log
import com.automation.agent.browser.BrowserController
import kotlinx.coroutines.delay

/**
 * Navigator - Handles navigation and interaction operations
 * 
 * Operations:
 * - navigate(url) - Navigate to URL
 * - wait(duration) - Wait for specified duration
 * - click(selector) - Click element by selector
 * - scroll(direction, pixels) - Scroll page
 * - waitForElement(selector, timeout) - Wait for element to appear
 * - waitForNavigation(timeout) - Wait for navigation to complete
 * - input(selector, text) - Enter text into input field
 * - submit(selector) - Submit form
 */
class Navigator(private val browser: BrowserController) {

    companion object {
        private const val TAG = "Navigator"
        private const val DEFAULT_WAIT_TIMEOUT = 30_000L // 30 seconds
        private const val POLL_INTERVAL = 100L // 100ms
        private const val PAGE_LOAD_CHECK_INTERVAL = 500L // 500ms
    }

    // ==================== Navigation ====================

    /**
     * Navigate to URL
     */
    suspend fun navigate(url: String) {
        Log.d(TAG, "Navigating to: $url")
        browser.navigate(url)
    }

    /**
     * Navigate and wait for page load
     */
    suspend fun navigateAndWait(url: String, timeout: Long = DEFAULT_WAIT_TIMEOUT): Boolean {
        navigate(url)
        return waitForPageLoad(timeout)
    }

    /**
     * Go back
     */
    suspend fun goBack() {
        Log.d(TAG, "Going back")
        browser.goBack()
    }

    /**
     * Go forward
     */
    suspend fun goForward() {
        Log.d(TAG, "Going forward")
        browser.goForward()
    }

    /**
     * Refresh page
     */
    suspend fun refresh() {
        Log.d(TAG, "Refreshing page")
        browser.refresh()
    }

    /**
     * Get current URL
     */
    suspend fun getCurrentUrl(): String {
        return browser.getCurrentUrl()
    }

    // ==================== Waiting ====================

    /**
     * Wait for specified duration
     */
    suspend fun wait(duration: Long) {
        Log.d(TAG, "Waiting for ${duration}ms")
        delay(duration)
    }

    /**
     * Wait for random duration within range
     */
    suspend fun waitRandom(minMs: Long, maxMs: Long) {
        val duration = (minMs..maxMs).random()
        Log.d(TAG, "Waiting for random ${duration}ms")
        delay(duration)
    }

    /**
     * Wait for element to appear
     */
    suspend fun waitForElement(
        selector: String,
        timeout: Long = DEFAULT_WAIT_TIMEOUT
    ): Boolean {
        Log.d(TAG, "Waiting for element: $selector (timeout: ${timeout}ms)")
        
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (checkElementExists(selector)) {
                Log.d(TAG, "Element found: $selector")
                return true
            }
            delay(POLL_INTERVAL)
        }
        
        Log.w(TAG, "Element not found within timeout: $selector")
        return false
    }

    /**
     * Wait for element to disappear
     */
    suspend fun waitForElementToDisappear(
        selector: String,
        timeout: Long = DEFAULT_WAIT_TIMEOUT
    ): Boolean {
        Log.d(TAG, "Waiting for element to disappear: $selector")
        
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!checkElementExists(selector)) {
                Log.d(TAG, "Element disappeared: $selector")
                return true
            }
            delay(POLL_INTERVAL)
        }
        
        Log.w(TAG, "Element still present after timeout: $selector")
        return false
    }

    /**
     * Wait for navigation to complete
     */
    suspend fun waitForNavigation(timeout: Long = DEFAULT_WAIT_TIMEOUT): Boolean {
        Log.d(TAG, "Waiting for navigation (timeout: ${timeout}ms)")
        
        val startUrl = browser.getCurrentUrl()
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            val currentUrl = browser.getCurrentUrl()
            if (currentUrl != startUrl && currentUrl.isNotEmpty()) {
                Log.d(TAG, "Navigation completed: $currentUrl")
                return true
            }
            delay(POLL_INTERVAL)
        }
        
        Log.w(TAG, "Navigation timeout")
        return false
    }

    /**
     * Wait for page to load completely
     */
    suspend fun waitForPageLoad(timeout: Long = DEFAULT_WAIT_TIMEOUT): Boolean {
        Log.d(TAG, "Waiting for page load (timeout: ${timeout}ms)")
        
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            val readyState = browser.evaluateJavascript("document.readyState")
            
            if (readyState?.contains("complete") == true) {
                Log.d(TAG, "Page loaded completely")
                return true
            }
            
            delay(PAGE_LOAD_CHECK_INTERVAL)
        }
        
        Log.w(TAG, "Page load timeout")
        return false
    }

    /**
     * Wait for text to appear on page
     */
    suspend fun waitForText(
        text: String,
        timeout: Long = DEFAULT_WAIT_TIMEOUT
    ): Boolean {
        Log.d(TAG, "Waiting for text: $text")
        
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            val pageSource = browser.getPageSource()
            if (pageSource.contains(text, ignoreCase = true)) {
                Log.d(TAG, "Text found: $text")
                return true
            }
            delay(POLL_INTERVAL)
        }
        
        Log.w(TAG, "Text not found within timeout: $text")
        return false
    }

    // ==================== Interaction ====================

    /**
     * Click element by CSS selector
     */
    suspend fun click(selector: String): Boolean {
        Log.d(TAG, "Clicking: $selector")
        return browser.click(selector)
    }

    /**
     * Click element and wait for navigation
     */
    suspend fun clickAndWait(selector: String, timeout: Long = DEFAULT_WAIT_TIMEOUT): Boolean {
        val startUrl = browser.getCurrentUrl()
        
        if (!click(selector)) {
            return false
        }
        
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val currentUrl = browser.getCurrentUrl()
            if (currentUrl != startUrl) {
                return waitForPageLoad(timeout - (System.currentTimeMillis() - startTime))
            }
            delay(POLL_INTERVAL)
        }
        
        return false
    }

    /**
     * Double click element
     */
    suspend fun doubleClick(selector: String): Boolean {
        Log.d(TAG, "Double clicking: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    var event = new MouseEvent('dblclick', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    element.dispatchEvent(event);
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Right click element
     */
    suspend fun rightClick(selector: String): Boolean {
        Log.d(TAG, "Right clicking: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    var event = new MouseEvent('contextmenu', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    element.dispatchEvent(event);
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Hover over element
     */
    suspend fun hover(selector: String): Boolean {
        Log.d(TAG, "Hovering: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    var event = new MouseEvent('mouseenter', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    element.dispatchEvent(event);
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Enter text into input field
     */
    suspend fun input(selector: String, text: String): Boolean {
        Log.d(TAG, "Inputting text into: $selector")
        return browser.input(selector, text)
    }

    /**
     * Clear input field
     */
    suspend fun clear(selector: String): Boolean {
        Log.d(TAG, "Clearing: $selector")
        return browser.clear(selector)
    }

    /**
     * Clear and input text
     */
    suspend fun clearAndInput(selector: String, text: String): Boolean {
        clear(selector)
        delay(50)
        return input(selector, text)
    }

    /**
     * Submit form
     */
    suspend fun submit(selector: String): Boolean {
        Log.d(TAG, "Submitting form: $selector")
        return browser.submit(selector)
    }

    /**
     * Focus element
     */
    suspend fun focus(selector: String): Boolean {
        Log.d(TAG, "Focusing: $selector")
        return browser.focus(selector)
    }

    /**
     * Select option from dropdown
     */
    suspend fun select(selector: String, value: String): Boolean {
        Log.d(TAG, "Selecting '$value' from: $selector")
        val script = """
            (function() {
                var select = document.querySelector('$selector');
                if (select && select.tagName === 'SELECT') {
                    select.value = '$value';
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Check checkbox or radio button
     */
    suspend fun check(selector: String): Boolean {
        Log.d(TAG, "Checking: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element && (element.type === 'checkbox' || element.type === 'radio')) {
                    element.checked = true;
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Uncheck checkbox
     */
    suspend fun uncheck(selector: String): Boolean {
        Log.d(TAG, "Unchecking: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element && element.type === 'checkbox') {
                    element.checked = false;
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    // ==================== Scrolling ====================

    /**
     * Scroll page
     */
    suspend fun scroll(direction: String, pixels: Int): Boolean {
        Log.d(TAG, "Scrolling $direction by $pixels px")
        return browser.scroll(direction, pixels)
    }

    /**
     * Scroll to element
     */
    suspend fun scrollToElement(selector: String): Boolean {
        Log.d(TAG, "Scrolling to element: $selector")
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Scroll to top of page
     */
    suspend fun scrollToTop(): Boolean {
        Log.d(TAG, "Scrolling to top")
        val script = "window.scrollTo({ top: 0, behavior: 'smooth' }); true;"
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Scroll to bottom of page
     */
    suspend fun scrollToBottom(): Boolean {
        Log.d(TAG, "Scrolling to bottom")
        val script = "window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); true;"
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Scroll by percentage of viewport
     */
    suspend fun scrollByPercent(percent: Int): Boolean {
        val script = """
            (function() {
                var scrollAmount = window.innerHeight * ${percent / 100.0};
                window.scrollBy({ top: scrollAmount, behavior: 'smooth' });
                return true;
            })();
        """.trimIndent()
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Get scroll position
     */
    suspend fun getScrollPosition(): Pair<Int, Int>? {
        val script = "JSON.stringify({ x: window.scrollX, y: window.scrollY });"
        val result = browser.evaluateJavascript(script) ?: return null
        
        return try {
            val x = Regex("\"x\":(\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: 0
            val y = Regex("\"y\":(\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: 0
            Pair(x, y)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== JavaScript Execution ====================

    /**
     * Execute custom JavaScript
     */
    suspend fun executeScript(script: String): String? {
        Log.d(TAG, "Executing script: ${script.take(100)}...")
        return browser.evaluateJavascript(script)
    }

    /**
     * Execute script and parse as boolean
     */
    suspend fun executeScriptBoolean(script: String): Boolean {
        val result = browser.evaluateJavascript(script)
        return result?.contains("true") == true
    }

    // ==================== Element Queries ====================

    /**
     * Check if element exists
     */
    suspend fun checkElementExists(selector: String): Boolean {
        val script = "document.querySelector('$selector') !== null"
        return browser.evaluateJavascript(script)?.contains("true") == true
    }

    /**
     * Get element text
     */
    suspend fun getElementText(selector: String): String? {
        return browser.getElementText(selector)
    }

    /**
     * Get element attribute
     */
    suspend fun getElementAttribute(selector: String, attribute: String): String? {
        return browser.getElementAttribute(selector, attribute)
    }

    /**
     * Count elements matching selector
     */
    suspend fun countElements(selector: String): Int {
        val script = "document.querySelectorAll('$selector').length"
        val result = browser.evaluateJavascript(script) ?: return 0
        return result.trim().toIntOrNull() ?: 0
    }

    /**
     * Get element bounding rect
     */
    suspend fun getElementRect(selector: String): ElementRect? {
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    var rect = element.getBoundingClientRect();
                    return JSON.stringify({
                        x: rect.x,
                        y: rect.y,
                        width: rect.width,
                        height: rect.height
                    });
                }
                return null;
            })();
        """.trimIndent()
        
        val result = browser.evaluateJavascript(script) ?: return null
        
        return try {
            val x = Regex("\"x\":(\\d+\\.?\\d*)").find(result)?.groupValues?.get(1)?.toFloat() ?: return null
            val y = Regex("\"y\":(\\d+\\.?\\d*)").find(result)?.groupValues?.get(1)?.toFloat() ?: return null
            val width = Regex("\"width\":(\\d+\\.?\\d*)").find(result)?.groupValues?.get(1)?.toFloat() ?: return null
            val height = Regex("\"height\":(\\d+\\.?\\d*)").find(result)?.groupValues?.get(1)?.toFloat() ?: return null
            ElementRect(x, y, width, height)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== Data Classes ====================

    data class ElementRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
}
