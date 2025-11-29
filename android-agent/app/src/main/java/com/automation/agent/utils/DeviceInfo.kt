package com.automation.agent.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - IP Address
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
     * Requires Google Play Services
     */
    suspend fun getAaid(): String = withContext(Dispatchers.IO) {
        try {
            val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
            if (adInfo.isLimitAdTrackingEnabled) {
                // User opted out of ad tracking
                ""
            } else {
                adInfo.id ?: ""
            }
        } catch (e: Exception) {
            // Google Play Services not available or error
            ""
        }
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
     * Get device brand
     */
    fun getBrand(): String {
        return Build.BRAND
    }

    /**
     * Get device product name
     */
    fun getProduct(): String {
        return Build.PRODUCT
    }

    /**
     * Get device hardware
     */
    fun getHardware(): String {
        return Build.HARDWARE
    }

    /**
     * Get device fingerprint
     */
    fun getFingerprint(): String {
        return Build.FINGERPRINT
    }

    /**
     * Get User-Agent
     */
    fun getUserAgent(): String {
        return System.getProperty("http.agent") ?: buildUserAgent()
    }

    private fun buildUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android ${getVersion()}; ${getModel()}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${getChromeVersion()} Mobile Safari/537.36"
    }

    /**
     * Get Chrome version installed on device
     */
    fun getChromeVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("com.android.chrome", 0)
            packageInfo.versionName ?: "120.0.0.0"
        } catch (e: Exception) {
            "120.0.0.0" // Default fallback
        }
    }

    /**
     * Get timezone
     */
    fun getTimezone(): String {
        return TimeZone.getDefault().id
    }

    /**
     * Get timezone offset in hours
     */
    fun getTimezoneOffset(): Int {
        return TimeZone.getDefault().rawOffset / (1000 * 60 * 60)
    }

    /**
     * Get screen resolution
     */
    fun getScreenResolution(): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    /**
     * Get screen density
     */
    fun getScreenDensity(): Float {
        return context.resources.displayMetrics.density
    }

    /**
     * Get device language
     */
    fun getLanguage(): String {
        return Locale.getDefault().language
    }

    /**
     * Get device country
     */
    fun getCountry(): String {
        return Locale.getDefault().country
    }

    /**
     * Get locale string
     */
    fun getLocale(): String {
        return Locale.getDefault().toString()
    }

    /**
     * Check if device is rooted (basic check)
     */
    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        
        for (path in paths) {
            if (java.io.File(path).exists()) {
                return true
            }
        }
        
        return false
    }

    /**
     * Get all device info as map
     */
    fun getAllInfo(): Map<String, Any> {
        val resolution = getScreenResolution()
        
        return mapOf(
            "androidId" to getAndroidId(),
            "model" to getModel(),
            "manufacturer" to getManufacturer(),
            "brand" to getBrand(),
            "product" to getProduct(),
            "hardware" to getHardware(),
            "version" to getVersion(),
            "sdkVersion" to getSdkVersion(),
            "userAgent" to getUserAgent(),
            "chromeVersion" to getChromeVersion(),
            "timezone" to getTimezone(),
            "timezoneOffset" to getTimezoneOffset(),
            "screenWidth" to resolution.first,
            "screenHeight" to resolution.second,
            "screenDensity" to getScreenDensity(),
            "language" to getLanguage(),
            "country" to getCountry(),
            "locale" to getLocale(),
            "isRooted" to isRooted()
        )
    }

    /**
     * Get registration info for backend
     */
    suspend fun getRegistrationInfo(): Map<String, Any> {
        val resolution = getScreenResolution()
        
        return mapOf(
            "androidId" to getAndroidId(),
            "aaid" to getAaid(),
            "model" to getModel(),
            "manufacturer" to getManufacturer(),
            "brand" to getBrand(),
            "version" to getVersion(),
            "sdkVersion" to getSdkVersion(),
            "userAgent" to getUserAgent(),
            "timezone" to getTimezone(),
            "screenWidth" to resolution.first,
            "screenHeight" to resolution.second,
            "language" to getLanguage(),
            "country" to getCountry(),
            "isRooted" to isRooted()
        )
    }
}
