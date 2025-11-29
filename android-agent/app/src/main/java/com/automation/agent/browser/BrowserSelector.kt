package com.automation.agent.browser

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.automation.agent.network.ProxyManager

/**
 * BrowserSelector - Selects and manages browser instances
 * 
 * Supports:
 * - WebView (embedded, full control) - recommended for automation
 * - Chrome (external, via Accessibility) - for real browser fingerprint
 * 
 * Selection criteria:
 * - Task requirements (DOM access, JavaScript execution)
 * - Fingerprint requirements (real browser vs embedded)
 * - Proxy support
 */
class BrowserSelector(
    private val context: Context,
    private val proxyManager: ProxyManager? = null
) {

    companion object {
        private const val TAG = "BrowserSelector"
        
        const val BROWSER_WEBVIEW = "webview"
        const val BROWSER_CHROME = "chrome"
        const val BROWSER_AUTO = "auto"
    }

    private var currentBrowser: BrowserController? = null
    private var currentBrowserType: String = ""

    /**
     * Select browser based on configuration
     * 
     * @param type Browser type: "webview", "chrome", or "auto"
     * @return BrowserController instance
     */
    fun selectBrowser(type: String): BrowserController {
        val browserType = type.lowercase()
        
        Log.i(TAG, "Selecting browser: $browserType")
        
        return when (browserType) {
            BROWSER_CHROME -> {
                if (isChromeAvailable()) {
                    createChromeController()
                } else {
                    Log.w(TAG, "Chrome not available, falling back to WebView")
                    createWebViewController()
                }
            }
            BROWSER_WEBVIEW -> {
                createWebViewController()
            }
            BROWSER_AUTO -> {
                // Auto-select based on availability and requirements
                selectBestBrowser()
            }
            else -> {
                Log.w(TAG, "Unknown browser type: $browserType, using WebView")
                createWebViewController()
            }
        }
    }

    /**
     * Select best available browser
     */
    private fun selectBestBrowser(): BrowserController {
        // WebView is preferred for automation (full control)
        // Chrome is used when real browser fingerprint is needed
        
        return createWebViewController()
    }

    /**
     * Create WebView controller
     */
    private fun createWebViewController(): BrowserController {
        Log.d(TAG, "Creating WebViewController")
        currentBrowserType = BROWSER_WEBVIEW
        return WebViewController(context, proxyManager).also {
            currentBrowser = it
        }
    }

    /**
     * Create Chrome controller
     */
    private fun createChromeController(): BrowserController {
        Log.d(TAG, "Creating ChromeController")
        currentBrowserType = BROWSER_CHROME
        return ChromeController(context, proxyManager).also {
            currentBrowser = it
        }
    }

    /**
     * Check if Chrome is installed
     */
    fun isChromeAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.android.chrome", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Get Chrome version if installed
     */
    fun getChromeVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("com.android.chrome", 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Get current browser instance
     */
    fun getCurrentBrowser(): BrowserController? = currentBrowser

    /**
     * Get current browser type
     */
    fun getCurrentBrowserType(): String = currentBrowserType

    /**
     * Close current browser
     */
    suspend fun closeCurrentBrowser() {
        currentBrowser?.close()
        currentBrowser = null
        currentBrowserType = ""
    }

    /**
     * Switch to different browser
     */
    suspend fun switchBrowser(type: String): BrowserController {
        closeCurrentBrowser()
        return selectBrowser(type)
    }

    /**
     * Get browser capabilities
     */
    fun getBrowserCapabilities(type: String): BrowserCapabilities {
        return when (type.lowercase()) {
            BROWSER_WEBVIEW -> BrowserCapabilities(
                canExecuteJavascript = true,
                canAccessDom = true,
                canTakeScreenshot = true,
                canSetProxy = false, // System-level only
                canSetUserAgent = true,
                canClearCookies = true,
                hasRealFingerprint = false
            )
            BROWSER_CHROME -> BrowserCapabilities(
                canExecuteJavascript = false, // Not directly
                canAccessDom = false, // Limited via Accessibility
                canTakeScreenshot = false, // Requires MediaProjection
                canSetProxy = false, // System-level only
                canSetUserAgent = false,
                canClearCookies = false, // Can clear via settings
                hasRealFingerprint = true
            )
            else -> BrowserCapabilities()
        }
    }

    /**
     * Browser capabilities descriptor
     */
    data class BrowserCapabilities(
        val canExecuteJavascript: Boolean = false,
        val canAccessDom: Boolean = false,
        val canTakeScreenshot: Boolean = false,
        val canSetProxy: Boolean = false,
        val canSetUserAgent: Boolean = false,
        val canClearCookies: Boolean = false,
        val hasRealFingerprint: Boolean = false
    )

    /**
     * Recommend browser for task type
     */
    fun recommendBrowser(taskType: String): String {
        return when (taskType.lowercase()) {
            "parsing", "extract", "scraping" -> BROWSER_WEBVIEW
            "surfing", "browsing" -> BROWSER_WEBVIEW
            "fingerprint", "detection_test" -> BROWSER_CHROME
            else -> BROWSER_WEBVIEW
        }
    }
}
