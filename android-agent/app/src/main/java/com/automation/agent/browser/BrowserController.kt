package com.automation.agent.browser

import android.graphics.Bitmap

/**
 * BrowserController - Common interface for browser controllers
 * 
 * Supports:
 * - Navigation (navigate, back, forward, refresh)
 * - Interaction (click, scroll, input, submit)
 * - Data extraction (getPageSource, evaluateJavascript)
 * - Screenshots
 * - Proxy configuration
 */
interface BrowserController {
    
    // ==================== Navigation ====================
    
    /**
     * Navigate to URL
     */
    suspend fun navigate(url: String)
    
    /**
     * Go back in history
     */
    suspend fun goBack()
    
    /**
     * Go forward in history
     */
    suspend fun goForward()
    
    /**
     * Refresh current page
     */
    suspend fun refresh()
    
    /**
     * Wait for specified duration
     */
    suspend fun wait(duration: Long)
    
    // ==================== Interaction ====================
    
    /**
     * Click element by CSS selector
     */
    suspend fun click(selector: String)
    
    /**
     * Scroll page
     * @param direction "up" or "down"
     * @param pixels amount to scroll
     */
    suspend fun scroll(direction: String, pixels: Int)
    
    /**
     * Input text into element
     */
    suspend fun input(selector: String, text: String)
    
    /**
     * Submit form
     */
    suspend fun submit(selector: String? = null)
    
    /**
     * Focus on element
     */
    suspend fun focus(selector: String)
    
    /**
     * Clear input field
     */
    suspend fun clear(selector: String)
    
    // ==================== Data Extraction ====================
    
    /**
     * Get current URL
     */
    suspend fun getCurrentUrl(): String
    
    /**
     * Get page title
     */
    suspend fun getTitle(): String
    
    /**
     * Get page HTML source
     */
    suspend fun getPageSource(): String
    
    /**
     * Execute JavaScript and return result
     */
    suspend fun evaluateJavascript(script: String): String?
    
    /**
     * Check if element exists
     */
    suspend fun elementExists(selector: String): Boolean
    
    /**
     * Get element text
     */
    suspend fun getElementText(selector: String): String?
    
    /**
     * Get element attribute
     */
    suspend fun getElementAttribute(selector: String, attribute: String): String?
    
    /**
     * Get multiple elements text
     */
    suspend fun getElementsText(selector: String): List<String>
    
    // ==================== Screenshots ====================
    
    /**
     * Take screenshot of visible area
     */
    suspend fun takeScreenshot(): Bitmap?
    
    /**
     * Take screenshot of full page
     */
    suspend fun takeFullPageScreenshot(): Bitmap?
    
    // ==================== State ====================
    
    /**
     * Check if page is fully loaded
     */
    suspend fun isPageLoaded(): Boolean
    
    /**
     * Wait for page to load
     */
    suspend fun waitForPageLoad(timeout: Long = 30000): Boolean
    
    /**
     * Wait for element to appear
     */
    suspend fun waitForElement(selector: String, timeout: Long = 10000): Boolean
    
    // ==================== Lifecycle ====================
    
    /**
     * Initialize browser
     */
    suspend fun initialize()
    
    /**
     * Close browser and release resources
     */
    suspend fun close()
    
    /**
     * Check if browser is initialized
     */
    fun isInitialized(): Boolean
}

