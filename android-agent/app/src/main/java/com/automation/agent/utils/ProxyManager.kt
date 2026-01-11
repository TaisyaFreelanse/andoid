package com.automation.agent.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Manages SOCKS5 proxy configuration and geolocation detection
 */
class ProxyManager(
    private val context: Context,
    private val rootUtils: RootUtils
) {
    companion object {
        private const val TAG = "ProxyManager"
        
        // US State to timezone mapping
        val STATE_TIMEZONE = mapOf(
            "California" to "America/Los_Angeles",
            "Texas" to "America/Chicago",
            "New York" to "America/New_York",
            "Florida" to "America/New_York",
            "Illinois" to "America/Chicago",
            "Pennsylvania" to "America/New_York",
            "Ohio" to "America/New_York",
            "Georgia" to "America/New_York",
            "North Carolina" to "America/New_York",
            "Michigan" to "America/Detroit",
            "Arizona" to "America/Phoenix",
            "Washington" to "America/Los_Angeles",
            "Colorado" to "America/Denver",
            "Nevada" to "America/Los_Angeles",
            "Oregon" to "America/Los_Angeles"
        )
        
        // US State to approximate coordinates (city centers)
        val STATE_COORDINATES = mapOf(
            "California" to Pair(34.0522, -118.2437), // Los Angeles
            "Texas" to Pair(29.7604, -95.3698), // Houston
            "New York" to Pair(40.7128, -74.0060), // New York City
            "Florida" to Pair(25.7617, -80.1918), // Miami
            "Illinois" to Pair(41.8781, -87.6298), // Chicago
            "Pennsylvania" to Pair(39.9526, -75.1652), // Philadelphia
            "Ohio" to Pair(39.9612, -82.9988), // Columbus
            "Georgia" to Pair(33.7490, -84.3880), // Atlanta
            "North Carolina" to Pair(35.2271, -80.8431), // Charlotte
            "Michigan" to Pair(42.3314, -83.0458), // Detroit
            "Arizona" to Pair(33.4484, -112.0740), // Phoenix
            "Washington" to Pair(47.6062, -122.3321), // Seattle
            "Colorado" to Pair(39.7392, -104.9903), // Denver
            "Nevada" to Pair(36.1699, -115.1398), // Las Vegas
            "Oregon" to Pair(45.5152, -122.6784) // Portland
        )
    }
    
    data class ProxyConfig(
        val id: String,
        val type: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val country: String,
        val state: String?,
        val rotationMinutes: Int = 10
    )
    
    data class ProxyLocation(
        val country: String,
        val state: String?,
        val city: String?,
        val timezone: String,
        val latitude: Double,
        val longitude: Double
    )
    
    private var currentProxy: ProxyConfig? = null
    private var proxyLocation: ProxyLocation? = null
    
    /**
     * Parse proxy from string format: socks5://host:port:username:password
     */
    fun parseProxy(proxyString: String): ProxyConfig? {
        try {
            val cleaned = proxyString.trim()
            val parts = cleaned.removePrefix("socks5://").split(":")
            if (parts.size >= 4) {
                return ProxyConfig(
                    id = "parsed_${System.currentTimeMillis()}",
                    type = "socks5",
                    host = parts[0],
                    port = parts[1].toInt(),
                    username = parts[2],
                    password = parts[3],
                    country = "US",
                    state = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse proxy: $proxyString", e)
        }
        return null
    }
    
    /**
     * Setup SOCKS5 proxy for app use (NOT global system proxy)
     * 
     * IMPORTANT: We DON'T set global http_proxy because:
     * 1. SOCKS5 != HTTP proxy (incompatible)
     * 2. Global proxy would break agent's connection to backend
     * 
     * Instead, we configure proxy only for:
     * - WebView (via System.setProperty)
     * - BrowserAutomation (via stored config)
     */
    suspend fun setupProxy(config: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Setting up SOCKS5 proxy: ${config.host}:${config.port}")
        
        try {
            currentProxy = config
            
            // Store SOCKS5 proxy for Java networking (WebView, HttpURLConnection)
            // This only affects apps that check these properties
            System.setProperty("socksProxyHost", config.host)
            System.setProperty("socksProxyPort", config.port.toString())
            
            // Also set SOCKS authentication
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    return if (requestingHost == config.host) {
                        java.net.PasswordAuthentication(config.username, config.password.toCharArray())
                    } else null
                }
            })
            
            Log.i(TAG, "SOCKS5 proxy configured for app use (NOT global)")
            
            // Detect location based on state
            if (config.state != null) {
                val timezone = STATE_TIMEZONE[config.state] ?: "America/New_York"
                val coords = STATE_COORDINATES[config.state] ?: Pair(40.7128, -74.0060)
                proxyLocation = ProxyLocation(
                    country = config.country,
                    state = config.state,
                    city = config.state,
                    timezone = timezone,
                    latitude = coords.first,
                    longitude = coords.second
                )
                Log.i(TAG, "Proxy location set: $proxyLocation")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup proxy: ${e.message}", e)
            false
        }
    }
    
    /**
     * Detect proxy location using IP geolocation API
     */
    suspend fun detectProxyLocation(): ProxyLocation? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Detecting proxy location...")
        
        try {
            // If we already have location from state, use it
            if (proxyLocation != null) {
                Log.i(TAG, "Using cached proxy location: $proxyLocation")
                return@withContext proxyLocation
            }
            
            // Try to detect via IP geolocation API
            val proxy = currentProxy?.let {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(it.host, it.port))
            }
            
            // Use ip-api.com for geolocation (free, no key required)
            val url = URL("http://ip-api.com/json/?fields=status,country,countryCode,region,regionName,city,lat,lon,timezone")
            val connection = if (proxy != null) {
                url.openConnection(proxy)
            } else {
                url.openConnection()
            }
            
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val response = connection.getInputStream().bufferedReader().readText()
            Log.i(TAG, "Geolocation response: $response")
            
            // Parse JSON manually (simple parsing)
            val country = extractJsonValue(response, "country") ?: "United States"
            val countryCode = extractJsonValue(response, "countryCode") ?: "US"
            val state = extractJsonValue(response, "regionName")
            val city = extractJsonValue(response, "city")
            val lat = extractJsonValue(response, "lat")?.toDoubleOrNull() ?: 40.7128
            val lon = extractJsonValue(response, "lon")?.toDoubleOrNull() ?: -74.0060
            val timezone = extractJsonValue(response, "timezone") ?: "America/New_York"
            
            proxyLocation = ProxyLocation(
                country = countryCode,
                state = state,
                city = city,
                timezone = timezone,
                latitude = lat,
                longitude = lon
            )
            
            Log.i(TAG, "Detected proxy location: $proxyLocation")
            proxyLocation
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect proxy location: ${e.message}", e)
            // Return default US location
            ProxyLocation(
                country = "US",
                state = "New York",
                city = "New York",
                timezone = "America/New_York",
                latitude = 40.7128,
                longitude = -74.0060
            )
        }
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"?([^",}]+)"?""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.trim()
    }
    
    /**
     * Get current proxy location
     */
    fun getProxyLocation(): ProxyLocation? = proxyLocation
    
    /**
     * Get current proxy config
     */
    fun getCurrentProxy(): ProxyConfig? = currentProxy
    
    /**
     * Clear proxy settings (app-level only, no global settings)
     */
    suspend fun clearProxy(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Clearing proxy settings...")
            
            currentProxy = null
            proxyLocation = null
            
            // Clear Java SOCKS proxy properties
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
            
            // Reset authenticator
            java.net.Authenticator.setDefault(null)
            
            Log.i(TAG, "Proxy settings cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear proxy: ${e.message}", e)
            false
        }
    }
    
    /**
     * Test proxy connection
     */
    suspend fun testProxy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val proxy = currentProxy ?: return@withContext false
            
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))
            val url = URL("https://www.google.com")
            val connection = url.openConnection(socksProxy) as HttpsURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "HEAD"
            
            // Set authentication
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                    return java.net.PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                }
            })
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            Log.i(TAG, "Proxy test result: $responseCode")
            responseCode == 200
            
        } catch (e: Exception) {
            Log.e(TAG, "Proxy test failed: ${e.message}", e)
            false
        }
    }
}

