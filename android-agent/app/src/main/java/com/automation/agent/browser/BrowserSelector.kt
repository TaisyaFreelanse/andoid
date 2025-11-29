package com.automation.agent.browser

import android.content.Context
import com.automation.agent.network.ProxyManager

/**
 * BrowserSelector - Selects and manages browser instances
 * 
 * Supports:
 * - Chrome (via ChromeController)
 * - WebView (via WebViewController)
 */
class BrowserSelector(
    private val context: Context,
    private val proxyManager: ProxyManager? = null
) {

    /**
     * Select browser based on configuration
     */
    fun selectBrowser(type: String): BrowserController {
        return when (type.lowercase()) {
            "chrome" -> ChromeController(context, proxyManager)
            "webview" -> WebViewController(context, proxyManager)
            else -> {
                // Fallback to WebView if Chrome unavailable
                try {
                    ChromeController(context, proxyManager)
                } catch (e: Exception) {
                    WebViewController(context, proxyManager)
                }
            }
        }
    }
}

/**
 * Common interface for browser controllers
 */
interface BrowserController {
    suspend fun navigate(url: String)
    suspend fun wait(duration: Long)
    suspend fun click(selector: String)
    suspend fun scroll(direction: String, pixels: Int)
    suspend fun getCurrentUrl(): String
    suspend fun getPageSource(): String
    suspend fun close()
}

