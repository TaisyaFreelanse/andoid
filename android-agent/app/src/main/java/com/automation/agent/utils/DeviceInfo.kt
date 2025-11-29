package com.automation.agent.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.*

/**
 * DeviceInfo - Collects device information
 * 
 * Information collected:
 * - Android ID
 * - AAID (Advertising ID)
 * - Model, Manufacturer
 * - Android Version
 * - User-Agent
 * - Timezone
 * - Screen resolution
 */
class DeviceInfo(private val context: Context) {

    /**
     * Get Android ID
     */
    fun getAndroidId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""
    }

    /**
     * Get AAID (Advertising ID)
     * Note: Requires Google Play Services
     */
    suspend fun getAaid(): String {
        // TODO: Get Advertising ID via Google Play Services
        // This requires additional setup
        return ""
    }

    /**
     * Get device model
     */
    fun getModel(): String {
        return Build.MODEL
    }

    /**
     * Get manufacturer
     */
    fun getManufacturer(): String {
        return Build.MANUFACTURER
    }

    /**
     * Get Android version
     */
    fun getVersion(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * Get SDK version
     */
    fun getSdkVersion(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * Get User-Agent
     */
    fun getUserAgent(): String {
        return System.getProperty("http.agent") ?: buildUserAgent()
    }

    private fun buildUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android ${getVersion()}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${getChromeVersion()} Mobile Safari/537.36"
    }

    private fun getChromeVersion(): String {
        // TODO: Get actual Chrome version
        return "120.0.0.0"
    }

    /**
     * Get timezone
     */
    fun getTimezone(): String {
        return TimeZone.getDefault().id
    }

    /**
     * Get screen resolution
     */
    fun getScreenResolution(): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    /**
     * Get all device info as map
     */
    fun getAllInfo(): Map<String, Any> {
        return mapOf(
            "androidId" to getAndroidId(),
            "model" to getModel(),
            "manufacturer" to getManufacturer(),
            "version" to getVersion(),
            "sdkVersion" to getSdkVersion(),
            "userAgent" to getUserAgent(),
            "timezone" to getTimezone(),
            "screenResolution" to getScreenResolution()
        )
    }
}

