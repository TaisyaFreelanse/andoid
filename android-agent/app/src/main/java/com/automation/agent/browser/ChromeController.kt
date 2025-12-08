package com.automation.agent.browser

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.browser.customtabs.CustomTabsIntent
import com.automation.agent.network.ProxyManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ChromeController - Controls Chrome browser via Intent and Accessibility API
 * 
 * This controller launches Chrome externally and interacts with it via:
 * - Intent for navigation
 * - Accessibility API for UI automation (requires AccessibilityService)
 * - Custom Tabs for controlled browsing
 * 
 * Limitations:
 * - Requires Accessibility Service to be enabled for full automation
 * - Limited DOM access compared to WebView
 * - Proxy configuration requires system-level settings or VPN
 * 
 * Use cases:
 * - When real Chrome browser fingerprint is needed
 * - When WebView detection is a concern
 * - For testing with actual Chrome behavior
 */
class ChromeController(
    private val context: Context,
    private val proxyManager: ProxyManager? = null
) : BrowserController {

    companion object {
        private const val TAG = "ChromeController"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val CHROME_ACTIVITY = "com.google.android.apps.chrome.Main"
        private const val PAGE_LOAD_TIMEOUT = 30_000L
        private const val ELEMENT_WAIT_POLL_INTERVAL = 200L
    }

    private val isInitialized = AtomicBoolean(false)
    private var currentUrl: String = ""
    private var accessibilityService: AccessibilityService? = null
    private var useCustomTabs: Boolean = false

    // ==================== Initialization ====================

    override suspend fun initialize() {
        if (isInitialized.get()) return
        
        // Check if Chrome is installed
        if (!isChromeInstalled()) {
            throw IllegalStateException("Chrome is not installed on this device")
        }
        
        isInitialized.set(true)
        Log.i(TAG, "ChromeController initialized")
    }

    /**
     * Check if Chrome is installed
     */
    private fun isChromeInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Set Accessibility Service for advanced automation
     */
    fun setAccessibilityService(service: AccessibilityService) {
        this.accessibilityService = service
    }

    /**
     * Enable Custom Tabs mode
     */
    fun setUseCustomTabs(use: Boolean) {
        this.useCustomTabs = use
    }

    // ==================== Navigation ====================

    override suspend fun navigate(url: String) {
        ensureInitialized()
        currentUrl = url
        
        withContext(Dispatchers.Main) {
            if (useCustomTabs) {
                openWithCustomTabs(url)
            } else {
                openWithIntent(url)
            }
        }
        
        // Wait for Chrome to open and page to start loading
        delay(2000)
    }

    /**
     * Open URL with Chrome Intent
     */
    private fun openWithIntent(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(CHROME_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        try {
            context.startActivity(intent)
            Log.d(TAG, "Opened Chrome with URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Chrome: ${e.message}")
            // Fallback to default browser
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    /**
     * Open URL with Custom Tabs
     */
    private fun openWithCustomTabs(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        
        customTabsIntent.intent.setPackage(CHROME_PACKAGE)
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        try {
            customTabsIntent.launchUrl(context, Uri.parse(url))
            Log.d(TAG, "Opened Custom Tab with URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Custom Tab: ${e.message}")
            openWithIntent(url)
        }
    }

    override suspend fun goBack() {
        // Use Accessibility API to press back
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override suspend fun goForward() {
        // Chrome doesn't have a global forward action
        // Would need to find and click the forward button via Accessibility
        Log.w(TAG, "goForward() requires Accessibility Service with UI automation")
    }

    override suspend fun refresh() {
        // Reload current URL
        if (currentUrl.isNotEmpty()) {
            navigate(currentUrl)
        }
    }

    override suspend fun wait(duration: Long) {
        delay(duration)
    }

    // ==================== Interaction ====================

    override suspend fun click(selector: String): Boolean {
        // Use Accessibility API to find and click element
        val node = findNodeBySelector(selector)
        return if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked element: $selector, result: $result")
            result
        } else {
            Log.w(TAG, "Element not found: $selector")
            false
        }
    }

    override suspend fun scroll(direction: String, pixels: Int): Boolean {
        val action = when (direction.lowercase()) {
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }
        
        // Find scrollable container and scroll
        val rootNode = getRootNode()
        val scrollable = findScrollableNode(rootNode)
        return scrollable?.performAction(action) ?: false
    }

    override suspend fun input(selector: String, text: String): Boolean {
        val node = findNodeBySelector(selector)
        return if (node != null) {
            // Focus the node
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(100)
            
            // Set text
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Input text to: $selector, result: $result")
                result
            } else {
                Log.w(TAG, "Input requires API 21+")
                false
            }
        } else {
            Log.w(TAG, "Input element not found: $selector")
            false
        }
    }

    override suspend fun submit(selector: String?): Boolean {
        return if (selector != null) {
            click(selector)
        } else {
            // Press Enter key via IME
            // This requires InputMethodService or Accessibility
            Log.w(TAG, "submit() without selector requires IME integration")
            false
        }
    }

    override suspend fun focus(selector: String): Boolean {
        val node = findNodeBySelector(selector)
        return node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
    }

    override suspend fun clear(selector: String): Boolean {
        val node = findNodeBySelector(selector)
        return if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(100)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } else {
                false
            }
        } else {
            false
        }
    }

    // ==================== Data Extraction ====================

    override suspend fun getCurrentUrl(): String {
        // Try to get URL from Chrome's address bar via Accessibility
        val rootNode = getRootNode()
        val urlBar = findNodeByResourceId(rootNode, "com.android.chrome:id/url_bar")
        return urlBar?.text?.toString() ?: currentUrl
    }

    override suspend fun getTitle(): String {
        // Try to get title from Chrome via Accessibility
        val rootNode = getRootNode()
        val titleNode = findNodeByResourceId(rootNode, "com.android.chrome:id/title")
        return titleNode?.text?.toString() ?: ""
    }

    override suspend fun getPageSource(): String {
        // Cannot get page source from external Chrome
        // Would need Chrome DevTools Protocol via USB debugging
        Log.w(TAG, "getPageSource() not available for external Chrome")
        return ""
    }

    override suspend fun evaluateJavascript(script: String): String? {
        // Cannot execute JavaScript in external Chrome
        Log.w(TAG, "evaluateJavascript() not available for external Chrome")
        return null
    }

    override suspend fun elementExists(selector: String): Boolean {
        return findNodeBySelector(selector) != null
    }

    override suspend fun getElementText(selector: String): String? {
        val node = findNodeBySelector(selector)
        return node?.text?.toString()
    }

    override suspend fun getElementAttribute(selector: String, attribute: String): String? {
        // Limited attribute access via Accessibility API
        val node = findNodeBySelector(selector)
        return when (attribute.lowercase()) {
            "text", "value" -> node?.text?.toString()
            "contentdescription", "contentdesc" -> node?.contentDescription?.toString()
            "classname", "class" -> node?.className?.toString()
            else -> null
        }
    }

    override suspend fun getElementsText(selector: String): List<String> {
        val nodes = findNodesBySelector(selector)
        return nodes.mapNotNull { it.text?.toString() }
    }

    // ==================== Screenshots ====================

    override suspend fun takeScreenshot(): Bitmap? {
        // Cannot take screenshot of external Chrome directly
        // Would need MediaProjection API or root access
        Log.w(TAG, "takeScreenshot() requires MediaProjection or root for external Chrome")
        return null
    }

    override suspend fun takeFullPageScreenshot(): Bitmap? {
        return takeScreenshot()
    }

    // ==================== State ====================

    override suspend fun isPageLoaded(): Boolean {
        // Check if Chrome shows loading indicator
        val rootNode = getRootNode()
        val progressBar = findNodeByResourceId(rootNode, "com.android.chrome:id/progress")
        return progressBar == null || !progressBar.isVisibleToUser
    }

    override suspend fun waitForPageLoad(timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (isPageLoaded()) {
                return true
            }
            delay(ELEMENT_WAIT_POLL_INTERVAL)
        }
        
        return false
    }

    override suspend fun waitForElement(selector: String, timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (elementExists(selector)) {
                return true
            }
            delay(ELEMENT_WAIT_POLL_INTERVAL)
        }
        
        return false
    }

    // ==================== Lifecycle ====================

    override suspend fun close() {
        // Close Chrome via Intent or kill process
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close Chrome: ${e.message}")
        }
        
        isInitialized.set(false)
        currentUrl = ""
        Log.i(TAG, "ChromeController closed")
    }

    override fun isInitialized(): Boolean = isInitialized.get()

    // ==================== Accessibility Helpers ====================

    private suspend fun ensureInitialized() {
        if (!isInitialized.get()) {
            initialize()
        }
    }

    private fun getRootNode(): AccessibilityNodeInfo? {
        return accessibilityService?.rootInActiveWindow
    }

    private fun performGlobalAction(action: Int): Boolean {
        return accessibilityService?.performGlobalAction(action) ?: false
    }

    /**
     * Find node by CSS-like selector
     * Limited support: id, class, text content
     */
    private fun findNodeBySelector(selector: String): AccessibilityNodeInfo? {
        val rootNode = getRootNode() ?: return null
        
        return when {
            selector.startsWith("#") -> {
                // ID selector
                val id = selector.substring(1)
                findNodeByResourceId(rootNode, id)
            }
            selector.startsWith(".") -> {
                // Class selector (limited)
                val className = selector.substring(1)
                findNodeByClassName(rootNode, className)
            }
            selector.contains("[") -> {
                // Attribute selector
                findNodeByAttribute(rootNode, selector)
            }
            else -> {
                // Text content selector
                findNodeByText(rootNode, selector)
            }
        }
    }

    private fun findNodesBySelector(selector: String): List<AccessibilityNodeInfo> {
        val rootNode = getRootNode() ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, selector, results)
        return results
    }

    private fun findAllNodes(
        node: AccessibilityNodeInfo?,
        selector: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        
        // Check if this node matches
        if (nodeMatchesSelector(node, selector)) {
            results.add(node)
        }
        
        // Recursively check children
        for (i in 0 until node.childCount) {
            findAllNodes(node.getChild(i), selector, results)
        }
    }

    private fun nodeMatchesSelector(node: AccessibilityNodeInfo, selector: String): Boolean {
        return when {
            selector.startsWith("#") -> {
                node.viewIdResourceName?.contains(selector.substring(1)) == true
            }
            selector.startsWith(".") -> {
                node.className?.contains(selector.substring(1)) == true
            }
            else -> {
                node.text?.contains(selector) == true ||
                node.contentDescription?.contains(selector) == true
            }
        }
    }

    private fun findNodeByResourceId(
        root: AccessibilityNodeInfo?,
        resourceId: String
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val fullId = if (resourceId.contains(":id/")) resourceId else "com.android.chrome:id/$resourceId"
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        return nodes.firstOrNull()
    }

    private fun findNodeByClassName(
        root: AccessibilityNodeInfo?,
        className: String
    ): AccessibilityNodeInfo? {
        return findNodeRecursive(root) { node ->
            node.className?.contains(className) == true
        }
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo?,
        text: String
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    private fun findNodeByAttribute(
        root: AccessibilityNodeInfo?,
        selector: String
    ): AccessibilityNodeInfo? {
        // Parse attribute selector like [href*="adurl"]
        val regex = Regex("""\[(\w+)([*^$]?)=["']([^"']+)["']\]""")
        val match = regex.find(selector) ?: return null
        
        val (attr, op, value) = match.destructured
        
        return findNodeRecursive(root) { node ->
            val nodeValue = when (attr.lowercase()) {
                "text" -> node.text?.toString()
                "contentdescription" -> node.contentDescription?.toString()
                else -> null
            }
            
            nodeValue != null && when (op) {
                "*" -> nodeValue.contains(value)
                "^" -> nodeValue.startsWith(value)
                "$" -> nodeValue.endsWith(value)
                else -> nodeValue == value
            }
        }
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        
        if (predicate(node)) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val result = findNodeRecursive(node.getChild(i), predicate)
            if (result != null) {
                return result
            }
        }
        
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findNodeRecursive(root) { node ->
            node.isScrollable
        }
    }
    
    // ==================== Network Interception ====================
    
    /**
     * Get intercepted ad URLs from network requests
     * ChromeController doesn't support network interception (external browser)
     */
    override suspend fun getInterceptedAdUrls(): Set<String> {
        // ChromeController uses external browser, can't intercept network requests
        return emptySet()
    }
    
    /**
     * Clear intercepted URLs
     */
    override suspend fun clearInterceptedUrls() {
        // No-op for ChromeController
    }
}
