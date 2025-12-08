package com.automation.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.*
import com.automation.agent.network.ProxyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * WebViewController - Controls embedded WebView for parsing
 * 
 * This is the primary browser implementation for automation.
 * WebView provides full control over navigation and DOM access.
 * 
 * Features:
 * - Full JavaScript support
 * - DOM manipulation
 * - Screenshot capture
 * - Proxy support via system settings
 * - Cookie management
 */
class WebViewController(
    private val context: Context,
    private val proxyManager: ProxyManager? = null
) : BrowserController {

    companion object {
        private const val TAG = "WebViewController"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val PAGE_LOAD_TIMEOUT = 30_000L
        private const val ELEMENT_WAIT_POLL_INTERVAL = 100L
    }

    private var webView: WebView? = null
    private val isInitialized = AtomicBoolean(false)
    private val isPageLoading = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var currentUrl: String = ""
    private var pageTitle: String = ""
    private var customUserAgent: String? = null
    private var windowManager: WindowManager? = null
    private var isAddedToWindow = false
    
    // Network request interception for ad URL extraction
    private val interceptedUrls = mutableSetOf<String>()
    private val adRedirectUrls = mutableSetOf<String>()

    // ==================== Initialization ====================

    override suspend fun initialize() {
        if (isInitialized.get()) return
        
        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                setupWebView(this)
            }
            
            // Add WebView to WindowManager so it can render properly
            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    // Removed FLAG_NOT_FOCUSABLE to make it fully interactive and visible
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.OPAQUE // Changed from TRANSLUCENT to OPAQUE for better visibility
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    // Make it fully visible and interactive - full screen
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    alpha = 1.0f // Fully visible
                    format = PixelFormat.OPAQUE // Ensure solid background
                }
                
                windowManager?.addView(webView, params)
                isAddedToWindow = true
                Log.i(TAG, "WebView added to WindowManager for proper rendering")
            } catch (e: Exception) {
                Log.w(TAG, "Could not add WebView to WindowManager: ${e.message}. " +
                    "WebView may not render properly. Grant SYSTEM_ALERT_WINDOW permission for better compatibility.")
                // Continue without window - some operations may still work
            }
            
            isInitialized.set(true)
            Log.i(TAG, "WebView initialized")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            // Enable JavaScript
            javaScriptEnabled = true
            
            // Enable DOM storage
            domStorageEnabled = true
            
            // Enable database
            databaseEnabled = true
            
            // Set cache mode
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // Allow file access
            allowFileAccess = true
            allowContentAccess = true
            
            // Enable zoom
            builtInZoomControls = true
            displayZoomControls = false
            
            // Set viewport
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Set user agent
            userAgentString = customUserAgent ?: DEFAULT_USER_AGENT
            
            // Mixed content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            // Media playback
            mediaPlaybackRequiresUserGesture = false
        }
        
        // Set WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageLoading.set(true)
                currentUrl = url ?: ""
                Log.d(TAG, "Page started: $url")
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoading.set(false)
                currentUrl = url ?: ""
                Log.d(TAG, "Page finished: $url")
            }
            
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }
            
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Intercept all URL navigations to catch ad redirects
                if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val url = request.url.toString()
                    
                    // Check if this navigation is from an ad-related source
                    val referrer = request.requestHeaders?.get("Referer") ?: ""
                    val isFromAd = isAdRelatedUrl(referrer) || isAdRelatedUrl(url)
                    
                    if (isFromAd) {
                        Log.d(TAG, "Intercepted navigation from ad: $url (referrer: $referrer)")
                        
                        // Check if URL is not the current page domain
                        val currentDomain = try {
                            if (currentUrl.isNotEmpty()) {
                                java.net.URL(currentUrl).host
                            } else {
                                ""
                            }
                        } catch (e: Exception) {
                            ""
                        }
                        
                        val urlDomain = try {
                            java.net.URL(url).host
                        } catch (e: Exception) {
                            ""
                        }
                        
                        // If URL is different from current domain and not an ad network URL, it's likely a final ad URL
                        if (urlDomain.isNotEmpty() && 
                            currentDomain.isNotEmpty() && 
                            urlDomain != currentDomain && 
                            !urlDomain.contains("googleads") && 
                            !urlDomain.contains("doubleclick") && 
                            !urlDomain.contains("googlesyndication") &&
                            !urlDomain.contains("pagead") &&
                            !urlDomain.contains("adservice")) {
                            Log.d(TAG, "Found potential ad URL from navigation: $url")
                            adRedirectUrls.add(url)
                        }
                        
                        // Also extract from query parameters
                        extractAdUrlFromRequest(url)
                    }
                }
                
                // Allow all URLs to load in WebView
                return false
            }
            
            // Intercept network requests to extract ad URLs from redirects
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val url = request.url.toString()
                    
                    // Track all intercepted URLs
                    interceptedUrls.add(url)
                    
                    // Check if this is an ad-related request
                    if (isAdRelatedUrl(url)) {
                        Log.d(TAG, "Intercepted ad-related request: $url")
                        
                        // Check for redirect URLs in query parameters
                        extractAdUrlFromRequest(url)
                    }
                    
                    // Also check ALL requests for adurl parameters (not just ad-related URLs)
                    // This catches cases where ad URLs are passed as parameters in any request
                    extractAdUrlFromRequest(url)
                }
                
                // Return null to use default handling (don't block the request)
                return null
            }
            
            // Handle HTTP redirects (3xx status codes)
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                
                if (request != null && errorResponse != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val statusCode = errorResponse.statusCode
                    val url = request.url.toString()
                    
                    // Check for redirect status codes (3xx)
                    if (statusCode in 300..399) {
                        val location = errorResponse.responseHeaders?.get("Location")
                        if (location != null) {
                            Log.d(TAG, "Intercepted HTTP redirect: $url -> $location")
                            
                            // Check if redirect is from ad-related source or goes to non-ad-network domain
                            val isFromAd = isAdRelatedUrl(url)
                            val currentDomain = try {
                                if (currentUrl.isNotEmpty()) {
                                    java.net.URL(currentUrl).host
                                } else {
                                    ""
                                }
                            } catch (e: Exception) {
                                ""
                            }
                            
                            val locationDomain = try {
                                java.net.URL(location).host
                            } catch (e: Exception) {
                                ""
                            }
                            
                            // If redirect is from ad network and goes to a different domain, it's likely a final ad URL
                            if (isFromAd && locationDomain.isNotEmpty() && 
                                locationDomain != currentDomain &&
                                !locationDomain.contains("googleads") && 
                                !locationDomain.contains("doubleclick") && 
                                !locationDomain.contains("googlesyndication") &&
                                !locationDomain.contains("pagead") &&
                                !locationDomain.contains("adservice")) {
                                Log.d(TAG, "Found ad URL from HTTP redirect: $location")
                                adRedirectUrls.add(location)
                            }
                        }
                    }
                }
            }
        }
        
        // Set WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                pageTitle = title ?: ""
            }
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    isPageLoading.set(false)
                }
            }
        }
        
        // Enable debugging in development
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Set custom user agent
     */
    fun setUserAgent(userAgent: String) {
        customUserAgent = userAgent
        webView?.settings?.userAgentString = userAgent
    }
    
    /**
     * Check if URL is ad-related
     */
    private fun isAdRelatedUrl(url: String): Boolean {
        return url.contains("googleads", ignoreCase = true) ||
               url.contains("doubleclick", ignoreCase = true) ||
               url.contains("googlesyndication", ignoreCase = true) ||
               url.contains("adservice", ignoreCase = true) ||
               url.contains("pagead", ignoreCase = true) ||
               url.contains("adurl", ignoreCase = true) ||
               url.contains("ad_url", ignoreCase = true) ||
               url.contains("adclick", ignoreCase = true)
    }
    
    /**
     * Extract ad URL from request URL (check query parameters)
     */
    private fun extractAdUrlFromRequest(url: String) {
        try {
            val uri = android.net.Uri.parse(url)
            
            // Check for common ad URL parameters
            val adUrlParams = listOf("adurl", "ad_url", "dest_url", "redirect", "goto", "target", "url")
            for (param in adUrlParams) {
                val value = uri.getQueryParameter(param)
                if (value != null && value.isNotEmpty()) {
                    val decodedUrl = java.net.URLDecoder.decode(value, "UTF-8")
                    if (decodedUrl.startsWith("http")) {
                        // Filter out URLs matching current domain
                        val currentDomain = try {
                            if (currentUrl.isNotEmpty()) {
                                java.net.URL(currentUrl).host
                            } else {
                                ""
                            }
                        } catch (e: Exception) {
                            ""
                        }
                        
                        val decodedDomain = try {
                            java.net.URL(decodedUrl).host
                        } catch (e: Exception) {
                            ""
                        }
                        
                        // Only add if it's a different domain and not an ad network domain
                        if (decodedDomain.isNotEmpty() && 
                            (currentDomain.isEmpty() || decodedDomain != currentDomain) &&
                            !decodedDomain.contains("pickuplineinsider.com") &&
                            !decodedDomain.contains("googleads") && 
                            !decodedDomain.contains("doubleclick") && 
                            !decodedDomain.contains("googlesyndication") &&
                            !decodedDomain.contains("pagead") &&
                            !decodedDomain.contains("adservice")) {
                            Log.d(TAG, "Extracted ad URL from request: $decodedUrl (param: $param)")
                            adRedirectUrls.add(decodedUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract ad URL from request: ${e.message}")
        }
    }
    
    /**
     * Get intercepted ad redirect URLs
     */
    override suspend fun getInterceptedAdUrls(): Set<String> {
        Log.d(TAG, "Returning ${adRedirectUrls.size} intercepted ad URLs: ${adRedirectUrls.take(5)}")
        return adRedirectUrls.toSet()
    }
    
    /**
     * Clear intercepted URLs (call before new page load)
     */
    override suspend fun clearInterceptedUrls() {
        interceptedUrls.clear()
        adRedirectUrls.clear()
    }

    // ==================== Navigation ====================

    override suspend fun navigate(url: String) {
        ensureInitialized()
        
        // Clear intercepted URLs before navigation
        clearInterceptedUrls()
        
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Navigating to: $url")
            isPageLoading.set(true)
            webView?.loadUrl(url)
        }
        
        // Wait for page to start loading
        delay(500)
    }

    override suspend fun goBack() {
        ensureInitialized()
        withContext(Dispatchers.Main) {
            if (webView?.canGoBack() == true) {
                webView?.goBack()
            }
        }
    }

    override suspend fun goForward() {
        ensureInitialized()
        withContext(Dispatchers.Main) {
            if (webView?.canGoForward() == true) {
                webView?.goForward()
            }
        }
    }

    override suspend fun refresh() {
        ensureInitialized()
        withContext(Dispatchers.Main) {
            webView?.reload()
        }
    }

    override suspend fun wait(duration: Long) {
        delay(duration)
    }

    // ==================== Interaction ====================

    override suspend fun click(selector: String): Boolean {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.click();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        Log.d(TAG, "Click result for '$selector': $result")
        return result?.contains("true") == true
    }

    override suspend fun scroll(direction: String, pixels: Int): Boolean {
        ensureInitialized()
        
        val scrollY = when (direction.lowercase()) {
            "down" -> pixels
            "up" -> -pixels
            else -> 0
        }
        
        val script = "window.scrollBy(0, $scrollY); true;"
        val result = evaluateJavascript(script)
        return result?.contains("true") == true
    }

    override suspend fun input(selector: String, text: String): Boolean {
        ensureInitialized()
        
        Log.d(TAG, "Input attempt - selector: $selector, text: $text")
        
        val escapedText = text.replace("'", "\\'").replace("\n", "\\n")
        val escapedSelector = selector.replace("'", "\\'")
        
        // First, check if element exists and get info about it
        val checkScript = """
            (function() {
                var selectors = '$escapedSelector'.split(',').map(s => s.trim());
                for (var i = 0; i < selectors.length; i++) {
                    var element = document.querySelector(selectors[i]);
                    if (element) {
                        return JSON.stringify({
                            found: true,
                            selector: selectors[i],
                            tagName: element.tagName,
                            type: element.type || 'none',
                            id: element.id || 'none',
                            name: element.name || 'none'
                        });
                    }
                }
                return JSON.stringify({found: false, tried: selectors.length});
            })();
        """.trimIndent()
        
        val checkResult = evaluateJavascript(checkScript)
        Log.d(TAG, "Element check result: $checkResult")
        
        // Try multiple input methods
        val script = """
            (function() {
                var selectors = '$escapedSelector'.split(',').map(s => s.trim());
                var element = null;
                
                // Try each selector
                for (var i = 0; i < selectors.length; i++) {
                    element = document.querySelector(selectors[i]);
                    if (element) break;
                }
                
                if (!element) {
                    // Try finding any search input
                    element = document.querySelector('input[type="search"]') ||
                              document.querySelector('input[name="q"]') ||
                              document.querySelector('textarea[name="q"]') ||
                              document.querySelector('[role="combobox"]') ||
                              document.querySelector('input[aria-label*="Search"]') ||
                              document.querySelector('input[aria-label*="Поиск"]');
                }
                
                if (element) {
                    // Focus the element first
                    element.focus();
                    
                    // Clear existing value
                    element.value = '';
                    
                    // Set the new value
                    element.value = '$escapedText';
                    
                    // Trigger various events to ensure the page reacts
                    element.dispatchEvent(new Event('focus', { bubbles: true }));
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                    element.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));
                    element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                    
                    return JSON.stringify({success: true, value: element.value});
                }
                return JSON.stringify({success: false, error: 'Element not found'});
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        Log.d(TAG, "Input result: $result")
        
        return result?.contains("\"success\":true") == true || result?.contains("success: true") == true
    }

    override suspend fun submit(selector: String?): Boolean {
        ensureInitialized()
        
        val script = if (selector != null) {
            """
                (function() {
                    var element = document.querySelector('$selector');
                    if (element) {
                        if (element.tagName === 'FORM') {
                            element.submit();
                        } else {
                            element.click();
                        }
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    var form = document.querySelector('form');
                    if (form) {
                        form.submit();
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
        }
        
        val result = evaluateJavascript(script)
        return result?.contains("true") == true
    }

    override suspend fun focus(selector: String): Boolean {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.focus();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        return result?.contains("true") == true
    }

    override suspend fun clear(selector: String): Boolean {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.value = '';
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        return result?.contains("true") == true
    }

    // ==================== Data Extraction ====================

    override suspend fun getCurrentUrl(): String {
        return currentUrl
    }

    override suspend fun getTitle(): String {
        return pageTitle
    }

    override suspend fun getPageSource(): String {
        ensureInitialized()
        
        val script = "document.documentElement.outerHTML;"
        return evaluateJavascript(script) ?: ""
    }

    override suspend fun evaluateJavascript(script: String): String? {
        ensureInitialized()
        
        return suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                webView?.evaluateJavascript(script) { result ->
                    // Remove quotes from string result
                    val cleanResult = result?.trim()?.let {
                        if (it.startsWith("\"") && it.endsWith("\"")) {
                            it.substring(1, it.length - 1)
                        } else {
                            it
                        }
                    }
                    continuation.resume(cleanResult)
                } ?: continuation.resume(null)
            }
        }
    }

    override suspend fun elementExists(selector: String): Boolean {
        val script = "document.querySelector('$selector') !== null;"
        val result = evaluateJavascript(script)
        return result == "true"
    }

    override suspend fun getElementText(selector: String): String? {
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                return element ? element.textContent : null;
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        return if (result == "null") null else result
    }

    override suspend fun getElementAttribute(selector: String, attribute: String): String? {
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                return element ? element.getAttribute('$attribute') : null;
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        return if (result == "null") null else result
    }

    override suspend fun getElementsText(selector: String): List<String> {
        val script = """
            (function() {
                var elements = document.querySelectorAll('$selector');
                var texts = [];
                elements.forEach(function(el) {
                    texts.push(el.textContent);
                });
                return JSON.stringify(texts);
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script) ?: "[]"
        
        // Parse JSON array
        return try {
            result.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get all links from page
     */
    suspend fun getAllLinks(): List<String> {
        val script = """
            (function() {
                var links = document.querySelectorAll('a[href]');
                var hrefs = [];
                links.forEach(function(a) {
                    hrefs.push(a.href);
                });
                return JSON.stringify(hrefs);
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script) ?: "[]"
        
        return try {
            result.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() && it.startsWith("http") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== Screenshots ====================

    override suspend fun takeScreenshot(): Bitmap? {
        ensureInitialized()
        
        return withContext(Dispatchers.Main) {
            webView?.let { view ->
                try {
                    val bitmap = Bitmap.createBitmap(
                        view.width,
                        view.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    view.draw(canvas)
                    bitmap
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot failed: ${e.message}")
                    null
                }
            }
        }
    }

    override suspend fun takeFullPageScreenshot(): Bitmap? {
        ensureInitialized()
        
        return withContext(Dispatchers.Main) {
            webView?.let { view ->
                try {
                    // Get full page dimensions
                    val contentHeight = view.contentHeight
                    val scale = view.scale
                    val fullHeight = (contentHeight * scale).toInt()
                    
                    if (fullHeight <= 0 || view.width <= 0) {
                        return@withContext takeScreenshot()
                    }
                    
                    val bitmap = Bitmap.createBitmap(
                        view.width,
                        fullHeight.coerceAtMost(10000), // Limit max height
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    view.draw(canvas)
                    bitmap
                } catch (e: Exception) {
                    Log.e(TAG, "Full page screenshot failed: ${e.message}")
                    takeScreenshot()
                }
            }
        }
    }

    // ==================== State ====================

    override suspend fun isPageLoaded(): Boolean {
        return !isPageLoading.get()
    }

    override suspend fun waitForPageLoad(timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!isPageLoading.get()) {
                // Additional check via JavaScript
                val readyState = evaluateJavascript("document.readyState;")
                if (readyState == "complete") {
                    return true
                }
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
        withContext(Dispatchers.Main) {
            // Remove from WindowManager first
            if (isAddedToWindow && webView != null) {
                try {
                    windowManager?.removeView(webView)
                    isAddedToWindow = false
                    Log.d(TAG, "WebView removed from WindowManager")
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing WebView from WindowManager: ${e.message}")
                }
            }
            
            webView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
            windowManager = null
            isInitialized.set(false)
            Log.i(TAG, "WebView closed")
        }
    }

    override fun isInitialized(): Boolean = isInitialized.get()

    /**
     * Ensure WebView is initialized
     */
    private suspend fun ensureInitialized() {
        if (!isInitialized.get()) {
            initialize()
        }
    }

    /**
     * Get WebView instance (for advanced usage)
     */
    fun getWebView(): WebView? = webView

    /**
     * Clear cookies
     */
    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /**
     * Clear cache
     */
    suspend fun clearCache() {
        withContext(Dispatchers.Main) {
            webView?.clearCache(true)
        }
    }

    /**
     * Clear history
     */
    suspend fun clearHistory() {
        withContext(Dispatchers.Main) {
            webView?.clearHistory()
        }
    }
}
