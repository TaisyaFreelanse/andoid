package com.automation.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import com.automation.agent.network.ProxyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    // ==================== Initialization ====================

    override suspend fun initialize() {
        if (isInitialized.get()) return
        
        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                setupWebView(this)
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
                // Allow all URLs to load in WebView
                return false
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

    // ==================== Navigation ====================

    override suspend fun navigate(url: String) {
        ensureInitialized()
        
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

    override suspend fun click(selector: String) {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.click();
                    return 'clicked';
                }
                return 'not_found';
            })();
        """.trimIndent()
        
        val result = evaluateJavascript(script)
        Log.d(TAG, "Click result for '$selector': $result")
    }

    override suspend fun scroll(direction: String, pixels: Int) {
        ensureInitialized()
        
        val scrollY = when (direction.lowercase()) {
            "down" -> pixels
            "up" -> -pixels
            else -> 0
        }
        
        val script = "window.scrollBy(0, $scrollY);"
        evaluateJavascript(script)
    }

    override suspend fun input(selector: String, text: String) {
        ensureInitialized()
        
        val escapedText = text.replace("'", "\\'").replace("\n", "\\n")
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.value = '$escapedText';
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                    return 'success';
                }
                return 'not_found';
            })();
        """.trimIndent()
        
        evaluateJavascript(script)
    }

    override suspend fun submit(selector: String?) {
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
                        return 'submitted';
                    }
                    return 'not_found';
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    var form = document.querySelector('form');
                    if (form) {
                        form.submit();
                        return 'submitted';
                    }
                    return 'no_form';
                })();
            """.trimIndent()
        }
        
        evaluateJavascript(script)
    }

    override suspend fun focus(selector: String) {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.focus();
                    return 'focused';
                }
                return 'not_found';
            })();
        """.trimIndent()
        
        evaluateJavascript(script)
    }

    override suspend fun clear(selector: String) {
        ensureInitialized()
        
        val script = """
            (function() {
                var element = document.querySelector('$selector');
                if (element) {
                    element.value = '';
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    return 'cleared';
                }
                return 'not_found';
            })();
        """.trimIndent()
        
        evaluateJavascript(script)
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
            webView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
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
