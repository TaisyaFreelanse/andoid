package com.automation.agent.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream

/**
 * Browser automation using WebView for Google search and page browsing
 */
class BrowserAutomation(
    private val context: Context,
    private val proxyManager: ProxyManager? = null
) {
    companion object {
        private const val TAG = "BrowserAutomation"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
    
    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String?
    )
    
    data class BrowseResult(
        val success: Boolean,
        val url: String,
        val title: String?,
        val error: String? = null,
        val screenshotPath: String? = null
    )
    
    private var webView: WebView? = null
    private var currentUserAgent: String = DEFAULT_USER_AGENT
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Initialize WebView with custom settings
     */
    suspend fun initWebView(userAgent: String? = null): Boolean = withContext(Dispatchers.Main) {
        try {
            currentUserAgent = userAgent ?: DEFAULT_USER_AGENT
            
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(true)
                    userAgentString = currentUserAgent
                    
                    // Enable mixed content for HTTP resources on HTTPS pages
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    
                    // Cache settings
                    cacheMode = WebSettings.LOAD_DEFAULT
                    
                    // Enable geolocation
                    setGeolocationEnabled(true)
                }
                
                // Set up proxy if available
                proxyManager?.getCurrentProxy()?.let { proxy ->
                    Log.i(TAG, "Setting proxy for WebView: ${proxy.host}:${proxy.port}")
                    // WebView uses system proxy settings
                }
                
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "Page started loading: $url")
                    }
                    
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "Page finished loading: $url")
                    }
                    
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Log.e(TAG, "WebView error: ${error?.description}")
                        }
                    }
                    
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        // For development/testing only - accept SSL errors
                        handler?.proceed()
                    }
                    
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false // Let WebView handle all URLs
                    }
                }
                
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        Log.d(TAG, "Loading progress: $newProgress%")
                    }
                    
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, true, false)
                    }
                }
            }
            
            // Force layout for programmatic WebView (not attached to window)
            // Use standard mobile screen size 1080x2400 (Full HD+)
            val targetWidth = 1080
            val targetHeight = 2400
            
            webView?.measure(
                View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
            )
            webView?.layout(0, 0, targetWidth, targetHeight)
            
            Log.i(TAG, "WebView initialized with UA: $currentUserAgent, size: ${targetWidth}x${targetHeight}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebView: ${e.message}", e)
            false
        }
    }
    
    /**
     * Open a URL in WebView
     */
    suspend fun openUrl(url: String, waitMs: Long = 3000): BrowseResult = withContext(Dispatchers.Main) {
        Log.i(TAG, "Opening URL: $url")
        
        val resultChannel = Channel<BrowseResult>(1)
        
        try {
            if (webView == null) {
                initWebView()
            }
            
            webView?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    Log.i(TAG, "Page loaded: $loadedUrl")
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(waitMs)
                        resultChannel.send(BrowseResult(
                            success = true,
                            url = loadedUrl ?: url,
                            title = view?.title
                        ))
                    }
                }
                
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val errorMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString() ?: "Unknown error"
                    } else {
                        "Unknown error"
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        resultChannel.send(BrowseResult(
                            success = false,
                            url = url,
                            title = null,
                            error = errorMsg
                        ))
                    }
                }
            }
            
            webView?.loadUrl(url)
            
            // Wait for result with timeout
            withTimeout(30000) {
                resultChannel.receive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL: ${e.message}", e)
            BrowseResult(
                success = false,
                url = url,
                title = null,
                error = e.message
            )
        }
    }
    
    /**
     * Perform Google search
     */
    suspend fun googleSearch(query: String, waitMs: Long = 5000): List<SearchResult> = withContext(Dispatchers.Main) {
        Log.i(TAG, "Performing Google search: $query")
        
        try {
            if (webView == null) {
                initWebView()
            }
            
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.google.com/search?q=$encodedQuery"
            
            val resultChannel = Channel<List<SearchResult>>(1)
            
            webView?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "Search page loaded: $url")
                    
                    // Wait a bit for dynamic content to load
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(waitMs)
                        
                        // Extract search results using JavaScript (mobile + desktop selectors)
                        val jsCode = """
                            (function() {
                                var results = [];
                                
                                // Try multiple selectors for mobile and desktop Google
                                var selectors = [
                                    'div.g',           // Desktop
                                    'div[data-hveid]', // Mobile results
                                    'div.xpd',         // Mobile expanded
                                    'div.MjjYud',      // New mobile layout
                                    'a[data-ved]'      // Any link with tracking
                                ];
                                
                                // First try to find all links that look like search results
                                var allLinks = document.querySelectorAll('a[href*="http"]');
                                allLinks.forEach(function(link, index) {
                                    if (index < 15 && results.length < 10) {
                                        var href = link.href || '';
                                        // Skip Google internal links
                                        if (href && 
                                            !href.includes('google.com') && 
                                            !href.includes('webcache') &&
                                            !href.includes('translate.google') &&
                                            href.startsWith('http')) {
                                            
                                            var title = link.innerText || link.textContent || '';
                                            // Get parent for snippet
                                            var parent = link.closest('div');
                                            var snippet = '';
                                            if (parent && parent.parentNode) {
                                                var siblingText = parent.parentNode.innerText || '';
                                                if (siblingText.length > title.length) {
                                                    snippet = siblingText.substring(0, 200);
                                                }
                                            }
                                            
                                            if (title.length > 5 && title.length < 200) {
                                                // Check for duplicates
                                                var isDuplicate = results.some(function(r) { return r.url === href; });
                                                if (!isDuplicate) {
                                                    results.push({
                                                        title: title.trim(),
                                                        url: href,
                                                        snippet: snippet.trim()
                                                    });
                                                }
                                            }
                                        }
                                    }
                                });
                                
                                console.log('Found ' + results.length + ' results');
                                return JSON.stringify(results);
                            })();
                        """.trimIndent()
                        
                        view?.evaluateJavascript(jsCode) { result ->
                            try {
                                val cleanResult = result?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "[]"
                                Log.i(TAG, "Search results JSON: $cleanResult")
                                
                                val searchResults = parseSearchResults(cleanResult)
                                CoroutineScope(Dispatchers.Main).launch {
                                    resultChannel.send(searchResults)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing search results: ${e.message}", e)
                                CoroutineScope(Dispatchers.Main).launch {
                                    resultChannel.send(emptyList())
                                }
                            }
                        }
                    }
                }
            }
            
            webView?.loadUrl(searchUrl)
            
            withTimeout(30000) {
                resultChannel.receive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing Google search: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun parseSearchResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            // Simple JSON parsing
            val pattern = """\{"title":"([^"]*?)","url":"([^"]*?)","snippet":"([^"]*?)"\}""".toRegex()
            pattern.findAll(json).forEach { match ->
                results.add(SearchResult(
                    title = match.groupValues[1].replace("\\n", " "),
                    url = match.groupValues[2],
                    snippet = match.groupValues[3].replace("\\n", " ")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search results: ${e.message}", e)
        }
        return results
    }
    
    /**
     * Click on a search result by index
     */
    suspend fun clickSearchResult(index: Int = -1, results: List<SearchResult>? = null): BrowseResult = withContext(Dispatchers.Main) {
        try {
            val searchResults = results ?: emptyList()
            
            if (searchResults.isEmpty()) {
                return@withContext BrowseResult(
                    success = false,
                    url = "",
                    title = null,
                    error = "No search results available"
                )
            }
            
            val targetIndex = if (index < 0 || index >= searchResults.size) {
                (0 until searchResults.size).random()
            } else {
                index
            }
            
            val targetResult = searchResults[targetIndex]
            Log.i(TAG, "Clicking on result #$targetIndex: ${targetResult.title} -> ${targetResult.url}")
            
            openUrl(targetResult.url, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking search result: ${e.message}", e)
            BrowseResult(
                success = false,
                url = "",
                title = null,
                error = e.message
            )
        }
    }
    
    /**
     * Browse page with scroll behavior
     */
    suspend fun browsePage(scrollCount: Int = 3, stayTimeMs: Long = 10000): BrowseResult = withContext(Dispatchers.Main) {
        try {
            val currentUrl = webView?.url ?: ""
            val currentTitle = webView?.title
            
            Log.i(TAG, "Browsing page: $currentUrl, scrollCount=$scrollCount, stayTime=$stayTimeMs")
            
            // Simulate human-like scrolling
            repeat(scrollCount) { i ->
                delay((500..1500).random().toLong())
                
                val scrollJs = """
                    window.scrollBy({
                        top: ${(300..600).random()},
                        behavior: 'smooth'
                    });
                """.trimIndent()
                
                webView?.evaluateJavascript(scrollJs, null)
                Log.d(TAG, "Scroll ${i + 1}/$scrollCount")
            }
            
            // Stay on page
            delay(stayTimeMs)
            
            // Scroll back up sometimes
            if ((0..10).random() > 7) {
                webView?.evaluateJavascript("window.scrollTo({top: 0, behavior: 'smooth'});", null)
                delay(1000)
            }
            
            BrowseResult(
                success = true,
                url = currentUrl,
                title = currentTitle
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error browsing page: ${e.message}", e)
            BrowseResult(
                success = false,
                url = webView?.url ?: "",
                title = null,
                error = e.message
            )
        }
    }
    
    /**
     * Take screenshot of current page
     */
    suspend fun takeScreenshot(): String? = withContext(Dispatchers.Main) {
        try {
            if (webView == null) {
                Log.w(TAG, "WebView is null, cannot take screenshot")
                return@withContext null
            }
            
            webView?.let { wv ->
                // Use standard mobile screen size 1080x2400 (Full HD+)
                val targetWidth = 1080
                val targetHeight = 2400
                
                // If WebView has no size, measure and layout it
                if (wv.width <= 0 || wv.height <= 0) {
                    Log.i(TAG, "WebView has no size, forcing layout: ${targetWidth}x${targetHeight}")
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, targetWidth, targetHeight)
                }
                
                val width = wv.width.coerceAtLeast(targetWidth)
                val height = wv.height.coerceAtLeast(targetHeight)
                
                Log.i(TAG, "Taking screenshot with dimensions: ${width}x${height}")
                
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                
                // Draw white background first
                canvas.drawColor(android.graphics.Color.WHITE)
                
                // Draw WebView content
                wv.draw(canvas)
                
                val screenshotDir = File(context.cacheDir, "screenshots")
                screenshotDir.mkdirs()
                
                val screenshotFile = File(screenshotDir, "screenshot_${System.currentTimeMillis()}.png")
                FileOutputStream(screenshotFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                
                val fileSize = screenshotFile.length()
                Log.i(TAG, "Screenshot saved: ${screenshotFile.absolutePath}, size: $fileSize bytes")
                
                // Validate screenshot is not empty (less than 1KB is likely empty)
                if (fileSize < 1000) {
                    Log.w(TAG, "Screenshot seems empty (size: $fileSize bytes)")
                }
                
                screenshotFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}", e)
            null
        }
    }
    
    /**
     * Clear WebView data
     */
    suspend fun clearData(): Boolean = withContext(Dispatchers.Main) {
        try {
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            
            WebStorage.getInstance().deleteAllData()
            
            Log.i(TAG, "WebView data cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing WebView data: ${e.message}", e)
            false
        }
    }
    
    /**
     * Set custom User-Agent
     */
    suspend fun setUserAgent(userAgent: String): Boolean = withContext(Dispatchers.Main) {
        try {
            currentUserAgent = userAgent
            webView?.settings?.userAgentString = userAgent
            Log.i(TAG, "User-Agent set: $userAgent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting User-Agent: ${e.message}", e)
            false
        }
    }
    
    /**
     * Execute custom JavaScript
     */
    suspend fun executeJavaScript(script: String): String? = withContext(Dispatchers.Main) {
        try {
            val resultChannel = Channel<String?>(1)
            
            webView?.evaluateJavascript(script) { result ->
                CoroutineScope(Dispatchers.Main).launch {
                    resultChannel.send(result)
                }
            }
            
            withTimeout(10000) {
                resultChannel.receive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing JavaScript: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get current page URL
     */
    fun getCurrentUrl(): String? = webView?.url
    
    /**
     * Get current page title
     */
    fun getCurrentTitle(): String? = webView?.title
    
    /**
     * Destroy WebView and free resources
     */
    fun destroy() {
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
        Log.i(TAG, "WebView destroyed")
    }
}

