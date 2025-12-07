package com.automation.agent.services

import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.automation.agent.utils.RootUtils
import kotlinx.coroutines.delay
import java.util.*

/**
 * UniquenessService - Service for device fingerprinting and uniqueness
 * 
 * Responsibilities (requires root):
 * - Regenerate AAID/AndroidID
 * - Clear Chrome/WebView data
 * - Change User-Agent
 * - Change timezone (auto-detect from proxy geolocation)
 * - Modify build.prop parameters
 * - Change GPS location (mock location)
 * 
 * Important: Does NOT change IMEI/serial/baseband (hardware level)
 */
class UniquenessService(
    private val context: Context,
    private val rootUtils: RootUtils
) {

    companion object {
        private const val TAG = "UniquenessService"
        
        // Package names
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val WEBVIEW_PACKAGE = "com.google.android.webview"
        private const val GMS_PACKAGE = "com.google.android.gms"
        
        // Data paths
        private const val CHROME_DATA_PATH = "/data/data/com.android.chrome"
        private const val WEBVIEW_DATA_PATH = "/data/data/com.google.android.webview"
        private const val APP_DATA_PATH = "/data/data"
        
        // User agent templates
        private val USER_AGENT_TEMPLATES = listOf(
            "Mozilla/5.0 (Linux; Android %d; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android %d; %s Build/%s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android %d; %s) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/%s Mobile Safari/537.36"
        )
        
        // Chrome versions for user agent
        private val CHROME_VERSIONS = listOf(
            "120.0.6099.144", "119.0.6045.193", "118.0.5993.111",
            "117.0.5938.140", "116.0.5845.163", "115.0.5790.166"
        )
        
        // Device models for spoofing
        private val DEVICE_MODELS = listOf(
            "SM-G973F" to "Samsung Galaxy S10",
            "SM-G975F" to "Samsung Galaxy S10+",
            "SM-G970F" to "Samsung Galaxy S10e",
            "SM-N975F" to "Samsung Galaxy Note 10+",
            "SM-A515F" to "Samsung Galaxy A51",
            "SM-A715F" to "Samsung Galaxy A71",
            "Pixel 6" to "Google Pixel 6",
            "Pixel 7" to "Google Pixel 7",
            "Pixel 7 Pro" to "Google Pixel 7 Pro"
        )
        
        // Timezone mappings by country code
        private val TIMEZONE_BY_COUNTRY = mapOf(
            "US" to listOf("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"),
            "GB" to listOf("Europe/London"),
            "DE" to listOf("Europe/Berlin"),
            "FR" to listOf("Europe/Paris"),
            "RU" to listOf("Europe/Moscow", "Asia/Yekaterinburg", "Asia/Novosibirsk"),
            "JP" to listOf("Asia/Tokyo"),
            "CN" to listOf("Asia/Shanghai"),
            "IN" to listOf("Asia/Kolkata"),
            "BR" to listOf("America/Sao_Paulo"),
            "AU" to listOf("Australia/Sydney", "Australia/Melbourne"),
            "CA" to listOf("America/Toronto", "America/Vancouver"),
            "MX" to listOf("America/Mexico_City"),
            "ES" to listOf("Europe/Madrid"),
            "IT" to listOf("Europe/Rome"),
            "NL" to listOf("Europe/Amsterdam"),
            "PL" to listOf("Europe/Warsaw"),
            "UA" to listOf("Europe/Kiev"),
            "TR" to listOf("Europe/Istanbul"),
            "KR" to listOf("Asia/Seoul"),
            "SG" to listOf("Asia/Singapore"),
            "HK" to listOf("Asia/Hong_Kong"),
            "TW" to listOf("Asia/Taipei"),
            "TH" to listOf("Asia/Bangkok"),
            "VN" to listOf("Asia/Ho_Chi_Minh"),
            "ID" to listOf("Asia/Jakarta"),
            "MY" to listOf("Asia/Kuala_Lumpur"),
            "PH" to listOf("Asia/Manila"),
            "AE" to listOf("Asia/Dubai"),
            "SA" to listOf("Asia/Riyadh"),
            "IL" to listOf("Asia/Jerusalem"),
            "ZA" to listOf("Africa/Johannesburg"),
            "EG" to listOf("Africa/Cairo"),
            "NG" to listOf("Africa/Lagos"),
            "AR" to listOf("America/Argentina/Buenos_Aires"),
            "CL" to listOf("America/Santiago"),
            "CO" to listOf("America/Bogota"),
            "PE" to listOf("America/Lima"),
            "NZ" to listOf("Pacific/Auckland")
        )
        
        // GPS coordinates by country (major cities)
        private val COORDINATES_BY_COUNTRY = mapOf(
            "US" to listOf(
                40.7128 to -74.0060,    // New York
                34.0522 to -118.2437,   // Los Angeles
                41.8781 to -87.6298,    // Chicago
                29.7604 to -95.3698     // Houston
            ),
            "GB" to listOf(51.5074 to -0.1278),  // London
            "DE" to listOf(52.5200 to 13.4050),  // Berlin
            "FR" to listOf(48.8566 to 2.3522),   // Paris
            "RU" to listOf(55.7558 to 37.6173),  // Moscow
            "JP" to listOf(35.6762 to 139.6503), // Tokyo
            "CN" to listOf(31.2304 to 121.4737), // Shanghai
            "IN" to listOf(28.6139 to 77.2090),  // New Delhi
            "BR" to listOf(-23.5505 to -46.6333),// Sao Paulo
            "AU" to listOf(-33.8688 to 151.2093),// Sydney
            "CA" to listOf(43.6532 to -79.3832), // Toronto
            "KR" to listOf(37.5665 to 126.9780), // Seoul
            "SG" to listOf(1.3521 to 103.8198),  // Singapore
            "NL" to listOf(52.3676 to 4.9041),   // Amsterdam
            "ES" to listOf(40.4168 to -3.7038),  // Madrid
            "IT" to listOf(41.9028 to 12.4964),  // Rome
            "MX" to listOf(19.4326 to -99.1332), // Mexico City
            "TR" to listOf(41.0082 to 28.9784),  // Istanbul
            "UA" to listOf(50.4501 to 30.5234),  // Kiev
            "PL" to listOf(52.2297 to 21.0122),  // Warsaw
            "AE" to listOf(25.2048 to 55.2708),  // Dubai
            "TH" to listOf(13.7563 to 100.5018), // Bangkok
            "VN" to listOf(10.8231 to 106.6297), // Ho Chi Minh
            "ID" to listOf(-6.2088 to 106.8456), // Jakarta
            "MY" to listOf(3.1390 to 101.6869),  // Kuala Lumpur
            "PH" to listOf(14.5995 to 120.9842), // Manila
            "HK" to listOf(22.3193 to 114.1694), // Hong Kong
            "TW" to listOf(25.0330 to 121.5654), // Taipei
            "IL" to listOf(32.0853 to 34.7818),  // Tel Aviv
            "SA" to listOf(24.7136 to 46.6753),  // Riyadh
            "ZA" to listOf(-33.9249 to 18.4241), // Cape Town
            "EG" to listOf(30.0444 to 31.2357),  // Cairo
            "NG" to listOf(6.5244 to 3.3792),    // Lagos
            "AR" to listOf(-34.6037 to -58.3816),// Buenos Aires
            "CL" to listOf(-33.4489 to -70.6693),// Santiago
            "CO" to listOf(4.7110 to -74.0721),  // Bogota
            "NZ" to listOf(-36.8485 to 174.7633) // Auckland
        )
    }

    private var lastUniquenessResult: UniquenessResult? = null

    // ==================== Android ID ====================

    /**
     * Regenerate Android ID
     */
    suspend fun regenerateAndroidId(): Boolean {
        Log.i(TAG, "Regenerating Android ID")
        
        val newId = generateRandomAndroidId()
        val result = rootUtils.setSecureSetting("android_id", newId)
        
        if (result) {
            Log.i(TAG, "Android ID regenerated: $newId")
        } else {
            Log.e(TAG, "Failed to regenerate Android ID")
        }
        
        return result
    }

    /**
     * Get current Android ID
     */
    suspend fun getAndroidId(): String? {
        return rootUtils.getSecureSetting("android_id")
    }

    /**
     * Generate random Android ID (16 hex characters)
     */
    private fun generateRandomAndroidId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    // ==================== AAID (Advertising ID) ====================

    /**
     * Regenerate AAID (Advertising ID)
     */
    suspend fun regenerateAaid(): Boolean {
        Log.i(TAG, "Regenerating AAID")
        
        // Method 1: Clear Google Play Services advertising data
        val clearGms = rootUtils.executeCommand(
            "rm -rf /data/data/com.google.android.gms/shared_prefs/adid_settings.xml"
        )
        
        // Method 2: Clear advertising ID from GMS
        val clearAdId = rootUtils.executeCommand(
            "rm -rf /data/data/com.google.android.gms/databases/adid_settings.db*"
        )
        
        // Method 3: Force stop GMS to regenerate
        rootUtils.forceStopApp(GMS_PACKAGE)
        
        // Method 4: Delete advertising ID cache
        rootUtils.executeCommand(
            "rm -rf /data/data/com.google.android.gms/files/ads_*"
        )
        
        Log.i(TAG, "AAID reset initiated (will regenerate on next GMS access)")
        return clearGms.success || clearAdId.success
    }

    // ==================== Chrome/WebView Data ====================

    /**
     * Clear Chrome data
     */
    suspend fun clearChromeData(): Boolean {
        Log.i(TAG, "Clearing Chrome data")
        
        // Force stop Chrome first
        rootUtils.forceStopApp(CHROME_PACKAGE)
        delay(500)
        
        // Clear app data via pm
        val pmClear = rootUtils.clearAppData(CHROME_PACKAGE)
        
        // Additionally delete specific folders
        val deleteFolders = listOf(
            "$CHROME_DATA_PATH/cache",
            "$CHROME_DATA_PATH/app_chrome/Default/Cookies",
            "$CHROME_DATA_PATH/app_chrome/Default/History",
            "$CHROME_DATA_PATH/app_chrome/Default/Login Data",
            "$CHROME_DATA_PATH/app_chrome/Default/Web Data",
            "$CHROME_DATA_PATH/shared_prefs"
        )
        
        for (folder in deleteFolders) {
            rootUtils.deleteDirectory(folder)
        }
        
        Log.i(TAG, "Chrome data cleared: $pmClear")
        return pmClear
    }

    /**
     * Clear WebView data
     */
    suspend fun clearWebViewData(): Boolean {
        Log.i(TAG, "Clearing WebView data")
        
        // Clear WebView app data
        val pmClear = rootUtils.clearAppData(WEBVIEW_PACKAGE)
        
        // Delete WebView cache for all apps
        val result = rootUtils.executeCommand(
            "find $APP_DATA_PATH -name 'webview*' -type d -exec rm -rf {} +"
        )
        
        // Clear WebView cache in app's own directory
        val appPackage = context.packageName
        rootUtils.deleteDirectory("$APP_DATA_PATH/$appPackage/cache/WebView")
        rootUtils.deleteDirectory("$APP_DATA_PATH/$appPackage/app_webview")
        
        Log.i(TAG, "WebView data cleared")
        return pmClear || result.success
    }

    /**
     * Clear all browser data (Chrome + WebView)
     */
    suspend fun clearAllBrowserData(): Boolean {
        val chrome = clearChromeData()
        val webview = clearWebViewData()
        return chrome && webview
    }

    // ==================== User-Agent ====================

    /**
     * Change User-Agent via build.prop
     */
    suspend fun changeUserAgent(userAgent: String? = null): Boolean {
        Log.i(TAG, "Changing User-Agent")
        
        val ua = userAgent ?: generateRandomUserAgent()
        
        // Set via system property (runtime)
        val runtimeResult = rootUtils.setProperty("persist.sys.webview.user_agent", ua)
        
        // Set via build.prop (persistent)
        val buildPropResult = rootUtils.modifyBuildProp(
            mapOf("ro.product.model.user_agent" to ua)
        )
        
        Log.i(TAG, "User-Agent changed to: $ua")
        return runtimeResult || buildPropResult
    }

    /**
     * Generate random user agent
     */
    fun generateRandomUserAgent(): String {
        val template = USER_AGENT_TEMPLATES.random()
        val chromeVersion = CHROME_VERSIONS.random()
        val (model, _) = DEVICE_MODELS.random()
        val androidVersion = (10..14).random()
        val buildId = generateRandomBuildId()
        
        return String.format(
            template,
            androidVersion,
            model,
            buildId,
            chromeVersion
        ).replace("%s", chromeVersion) // Handle templates with different arg counts
    }

    /**
     * Generate random build ID
     */
    private fun generateRandomBuildId(): String {
        val letters = ('A'..'Z').toList()
        val prefix = letters.random().toString() + letters.random()
        val number = (100000..999999).random()
        val suffix = letters.random().toString() + (10..99).random()
        return "$prefix$number$suffix"
    }

    // ==================== Timezone ====================

    /**
     * Change timezone
     */
    suspend fun changeTimezone(timezone: String): Boolean {
        Log.i(TAG, "Changing timezone to: $timezone")
        
        // Disable auto timezone
        rootUtils.setGlobalSetting("auto_time_zone", "0")
        
        // Set timezone
        val result = rootUtils.setSystemSetting("time_zone", timezone)
        
        // Also set via property
        rootUtils.setProperty("persist.sys.timezone", timezone)
        
        // Set via service
        rootUtils.executeCommand("service call alarm 3 s16 $timezone")
        
        Log.i(TAG, "Timezone changed to: $timezone, result: $result")
        return result
    }

    /**
     * Change timezone based on country code
     */
    suspend fun changeTimezoneByCountry(countryCode: String): Boolean {
        val timezones = TIMEZONE_BY_COUNTRY[countryCode.uppercase()]
        if (timezones.isNullOrEmpty()) {
            Log.w(TAG, "No timezone mapping for country: $countryCode")
            return false
        }
        
        val timezone = timezones.random()
        return changeTimezone(timezone)
    }

    /**
     * Get current timezone
     */
    suspend fun getCurrentTimezone(): String? {
        return rootUtils.getSystemSetting("time_zone") 
            ?: rootUtils.getProperty("persist.sys.timezone")
    }

    // ==================== Build.prop Modification ====================

    /**
     * Modify build.prop parameters
     */
    suspend fun modifyBuildProp(params: Map<String, String>): Boolean {
        Log.i(TAG, "Modifying build.prop: $params")
        return rootUtils.modifyBuildProp(params)
    }

    /**
     * Change device model
     */
    suspend fun changeDeviceModel(model: String, manufacturer: String = "samsung"): Boolean {
        val params = mapOf(
            "ro.product.model" to model,
            "ro.product.manufacturer" to manufacturer,
            "ro.product.brand" to manufacturer,
            "ro.product.device" to model.lowercase().replace(" ", "_"),
            "ro.product.name" to model.lowercase().replace(" ", "_"),
            "ro.build.product" to model.lowercase().replace(" ", "_")
        )
        
        return modifyBuildProp(params)
    }

    /**
     * Randomize device model
     */
    suspend fun randomizeDeviceModel(): Boolean {
        val (model, _) = DEVICE_MODELS.random()
        val manufacturer = if (model.startsWith("SM-")) "samsung" 
                          else if (model.startsWith("Pixel")) "Google" 
                          else "samsung"
        return changeDeviceModel(model, manufacturer)
    }

    /**
     * Change Android version info
     */
    suspend fun changeAndroidVersion(
        sdkVersion: Int = (29..34).random(),
        releaseVersion: String = "${(10..14).random()}"
    ): Boolean {
        val params = mapOf(
            "ro.build.version.sdk" to sdkVersion.toString(),
            "ro.build.version.release" to releaseVersion,
            "ro.build.version.preview_sdk" to "0",
            "ro.build.version.codename" to "REL"
        )
        
        return modifyBuildProp(params)
    }

    // ==================== GPS Location ====================

    /**
     * Change GPS location (mock location)
     */
    suspend fun changeLocation(latitude: Double, longitude: Double): Boolean {
        Log.i(TAG, "Changing location to: $latitude, $longitude")
        
        // Enable mock location in developer options
        rootUtils.setSecureSetting("mock_location", "1")
        
        // Allow mock locations for our app
        val appPackage = context.packageName
        rootUtils.setSecureSetting("mock_location_app", appPackage)
        
        // Set location via location manager (requires additional implementation)
        // This is a simplified version - full implementation needs LocationManager
        
        // Store coordinates for use by mock location provider
        val result = rootUtils.executeCommand(
            "settings put secure location_providers_allowed +gps,network"
        )
        
        Log.i(TAG, "Mock location enabled for: $latitude, $longitude")
        return result.success
    }

    /**
     * Change location by country code
     */
    suspend fun changeLocationByCountry(countryCode: String): Boolean {
        val coordinates = COORDINATES_BY_COUNTRY[countryCode.uppercase()]
        if (coordinates.isNullOrEmpty()) {
            Log.w(TAG, "No coordinates for country: $countryCode")
            return false
        }
        
        val (lat, lng) = coordinates.random()
        return changeLocation(lat, lng)
    }

    /**
     * Disable mock location
     */
    suspend fun disableMockLocation(): Boolean {
        rootUtils.setSecureSetting("mock_location", "0")
        return rootUtils.deleteSetting("secure", "mock_location_app")
    }

    // ==================== Language/Locale ====================

    /**
     * Change device language/locale
     */
    suspend fun changeLocale(locale: String): Boolean {
        Log.i(TAG, "Changing locale to: $locale")
        
        // Set locale via system settings
        val result1 = rootUtils.setGlobalSetting("locale", locale)
        
        // Also set via property (persistent)
        val result2 = rootUtils.setProperty("persist.sys.locale", locale)
        
        // Set language via settings
        val result3 = rootUtils.executeCommand("setprop persist.sys.language ${locale.split("_")[0]}")
        val result4 = rootUtils.executeCommand("setprop persist.sys.country ${locale.split("_").getOrNull(1) ?: ""}")
        
        Log.i(TAG, "Locale changed to: $locale, results: $result1, $result2, ${result3.success}, ${result4.success}")
        return result1 || result2 || result3.success || result4.success
    }

    /**
     * Change locale based on country code
     */
    suspend fun changeLocaleByCountry(countryCode: String): Boolean {
        val locales = mapOf(
            "US" to "en_US", "GB" to "en_GB", "CA" to "en_CA", "AU" to "en_AU",
            "RU" to "ru_RU", "DE" to "de_DE", "FR" to "fr_FR", "ES" to "es_ES",
            "IT" to "it_IT", "PT" to "pt_PT", "BR" to "pt_BR", "JP" to "ja_JP",
            "CN" to "zh_CN", "KR" to "ko_KR", "IN" to "en_IN", "MX" to "es_MX"
        )
        
        val locale = locales[countryCode.uppercase()] ?: "en_US"
        return changeLocale(locale)
    }

    // ==================== Full Uniqueness ====================

    /**
     * Apply full uniqueness transformation
     */
    suspend fun applyFullUniqueness(config: UniquenessConfig): UniquenessResult {
        Log.i(TAG, "Applying full uniqueness transformation")
        
        val results = mutableMapOf<String, Boolean>()
        val startTime = System.currentTimeMillis()
        
        // Check root access
        if (!rootUtils.isRootAvailable()) {
            Log.e(TAG, "Root access not available")
            return UniquenessResult(
                success = false,
                results = mapOf("root_check" to false),
                error = "Root access not available"
            )
        }
        
        // 1. Regenerate Android ID
        if (config.regenerateAndroidId) {
            results["android_id"] = regenerateAndroidId()
        }
        
        // 2. Regenerate AAID
        if (config.regenerateAaid) {
            results["aaid"] = regenerateAaid()
        }
        
        // 3. Clear Chrome data
        if (config.clearChromeData) {
            results["chrome_data"] = clearChromeData()
        }
        
        // 4. Clear WebView data
        if (config.clearWebViewData) {
            results["webview_data"] = clearWebViewData()
        }
        
        // 5. Change User-Agent
        if (config.changeUserAgent) {
            results["user_agent"] = changeUserAgent(config.userAgent)
        }
        
        // 6. Change timezone
        config.timezone?.let { tz ->
            results["timezone"] = changeTimezone(tz)
        }
        
        config.countryCode?.let { country ->
            if (config.timezone == null) {
                results["timezone"] = changeTimezoneByCountry(country)
            }
        }
        
        // 7. Change location
        config.location?.let { (lat, lng) ->
            results["location"] = changeLocation(lat, lng)
        }
        
        config.countryCode?.let { country ->
            if (config.location == null) {
                results["location"] = changeLocationByCountry(country)
            }
        }
        
        // 8. Modify build.prop
        if (config.buildPropParams.isNotEmpty()) {
            results["build_prop"] = modifyBuildProp(config.buildPropParams)
        }
        
        // 9. Randomize device model
        if (config.randomizeDevice) {
            results["device_model"] = randomizeDeviceModel()
        }
        
        val duration = System.currentTimeMillis() - startTime
        val success = results.values.all { it }
        
        val result = UniquenessResult(
            success = success,
            results = results,
            duration = duration
        )
        
        lastUniquenessResult = result
        
        Log.i(TAG, "Uniqueness transformation completed: success=$success, duration=${duration}ms")
        return result
    }

    /**
     * Quick uniqueness reset (most common operations)
     */
    suspend fun quickReset(): UniquenessResult {
        return applyFullUniqueness(
            UniquenessConfig(
                regenerateAndroidId = true,
                regenerateAaid = true,
                clearChromeData = true,
                clearWebViewData = true,
                changeUserAgent = true,
                randomizeDevice = false
            )
        )
    }

    /**
     * Full reset with device randomization
     */
    suspend fun fullReset(countryCode: String? = null): UniquenessResult {
        return applyFullUniqueness(
            UniquenessConfig(
                regenerateAndroidId = true,
                regenerateAaid = true,
                clearChromeData = true,
                clearWebViewData = true,
                changeUserAgent = true,
                randomizeDevice = true,
                countryCode = countryCode
            )
        )
    }

    /**
     * Get last uniqueness result
     */
    fun getLastResult(): UniquenessResult? = lastUniquenessResult

    // ==================== Data Classes ====================

    data class UniquenessConfig(
        val regenerateAndroidId: Boolean = true,
        val regenerateAaid: Boolean = true,
        val clearChromeData: Boolean = true,
        val clearWebViewData: Boolean = true,
        val changeUserAgent: Boolean = true,
        val userAgent: String? = null,
        val timezone: String? = null,
        val countryCode: String? = null,
        val location: Pair<Double, Double>? = null,
        val buildPropParams: Map<String, String> = emptyMap(),
        val randomizeDevice: Boolean = false
    )

    data class UniquenessResult(
        val success: Boolean,
        val results: Map<String, Boolean>,
        val duration: Long = 0,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
}
