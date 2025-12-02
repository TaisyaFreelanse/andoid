package com.automation.agent.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * ProxyManager - Manages proxy connections
 * 
 * Supports:
 * - HTTP/HTTPS proxies
 * - SOCKS5 proxies
 * - Proxy rotation (round-robin, random)
 * - Proxy health checking
 * - Automatic failover
 * - Geo-location based selection
 */
class ProxyManager {

    companion object {
        private const val TAG = "ProxyManager"
        private const val HEALTH_CHECK_TIMEOUT = 10_000L
        private const val HEALTH_CHECK_URL = "https://api.ipify.org"

        /**
         * Parse SOCKS5 proxy string: socks5://host:port:username:password
         */
        fun parseSocks5(proxyString: String): ProxyConfig? {
            // Format: socks5://host:port:username:password
            val regex = Regex("socks5://([^:]+):(\\d+):([^:]+):(.+)")
            val match = regex.find(proxyString)
            
            if (match != null) {
                return ProxyConfig(
                    type = ProxyType.SOCKS5,
                    host = match.groupValues[1],
                    port = match.groupValues[2].toInt(),
                    username = match.groupValues[3],
                    password = match.groupValues[4]
                )
            }
            
            // Alternative format: socks5://username:password@host:port
            val altRegex = Regex("socks5://([^:]+):([^@]+)@([^:]+):(\\d+)")
            val altMatch = altRegex.find(proxyString)
            
            if (altMatch != null) {
                return ProxyConfig(
                    type = ProxyType.SOCKS5,
                    host = altMatch.groupValues[3],
                    port = altMatch.groupValues[4].toInt(),
                    username = altMatch.groupValues[1],
                    password = altMatch.groupValues[2]
                )
            }
            
            return null
        }

        /**
         * Parse HTTP proxy string: http://host:port or http://username:password@host:port
         */
        fun parseHttp(proxyString: String): ProxyConfig? {
            // Format with auth: http://username:password@host:port
            val authRegex = Regex("https?://([^:]+):([^@]+)@([^:]+):(\\d+)")
            val authMatch = authRegex.find(proxyString)
            
            if (authMatch != null) {
                return ProxyConfig(
                    type = if (proxyString.startsWith("https")) ProxyType.HTTPS else ProxyType.HTTP,
                    host = authMatch.groupValues[3],
                    port = authMatch.groupValues[4].toInt(),
                    username = authMatch.groupValues[1],
                    password = authMatch.groupValues[2]
                )
            }
            
            // Format without auth: http://host:port
            val simpleRegex = Regex("https?://([^:]+):(\\d+)")
            val simpleMatch = simpleRegex.find(proxyString)
            
            if (simpleMatch != null) {
                return ProxyConfig(
                    type = if (proxyString.startsWith("https")) ProxyType.HTTPS else ProxyType.HTTP,
                    host = simpleMatch.groupValues[1],
                    port = simpleMatch.groupValues[2].toInt()
                )
            }
            
            return null
        }

        /**
         * Parse any proxy string (auto-detect type)
         */
        fun parse(proxyString: String): ProxyConfig? {
            return when {
                proxyString.startsWith("socks5://") -> parseSocks5(proxyString)
                proxyString.startsWith("http://") -> parseHttp(proxyString)
                proxyString.startsWith("https://") -> parseHttp(proxyString)
                else -> null
            }
        }
    }

    private val proxies = mutableListOf<ProxyConfig>()
    private val healthyProxies = mutableSetOf<String>()
    private val unhealthyProxies = mutableSetOf<String>()
    private var currentProxyIndex = 0
    private var rotationMode = RotationMode.ROUND_ROBIN

    // ==================== Proxy Management ====================

    /**
     * Add proxy configuration
     */
    fun addProxy(config: ProxyConfig) {
        proxies.add(config)
        Log.i(TAG, "Added proxy: ${config.host}:${config.port} (${config.type})")
    }

    /**
     * Add multiple proxies
     */
    fun addProxies(configs: List<ProxyConfig>) {
        configs.forEach { addProxy(it) }
    }

    /**
     * Remove proxy
     */
    fun removeProxy(config: ProxyConfig) {
        proxies.remove(config)
        healthyProxies.remove(config.id)
        unhealthyProxies.remove(config.id)
    }

    /**
     * Clear all proxies
     */
    fun clearProxies() {
        proxies.clear()
        healthyProxies.clear()
        unhealthyProxies.clear()
        currentProxyIndex = 0
    }

    /**
     * Get proxy count
     */
    fun getProxyCount(): Int = proxies.size

    /**
     * Get all proxies
     */
    fun getAllProxies(): List<ProxyConfig> = proxies.toList()

    // ==================== Proxy Selection ====================

    /**
     * Get next proxy based on rotation mode
     */
    fun getNextProxy(): ProxyConfig? {
        if (proxies.isEmpty()) return null
        
        return when (rotationMode) {
            RotationMode.ROUND_ROBIN -> getNextRoundRobin()
            RotationMode.RANDOM -> getRandomProxy()
            RotationMode.HEALTHY_FIRST -> getHealthyProxy()
        }
    }

    private fun getNextRoundRobin(): ProxyConfig {
        val proxy = proxies[currentProxyIndex]
        currentProxyIndex = (currentProxyIndex + 1) % proxies.size
        return proxy
    }

    private fun getRandomProxy(): ProxyConfig {
        return proxies.random()
    }

    private fun getHealthyProxy(): ProxyConfig? {
        // First try healthy proxies
        val healthy = proxies.filter { healthyProxies.contains(it.id) }
        if (healthy.isNotEmpty()) {
            return healthy.random()
        }
        
        // Fall back to unchecked proxies
        val unchecked = proxies.filter { 
            !healthyProxies.contains(it.id) && !unhealthyProxies.contains(it.id) 
        }
        if (unchecked.isNotEmpty()) {
            return unchecked.random()
        }
        
        // Last resort: any proxy
        return proxies.randomOrNull()
    }

    /**
     * Get current proxy without rotation
     */
    fun getCurrentProxy(): ProxyConfig? {
        if (proxies.isEmpty()) return null
        return proxies.getOrNull(currentProxyIndex)
    }

    /**
     * Get proxy by index
     */
    fun getProxy(index: Int): ProxyConfig? {
        return proxies.getOrNull(index)
    }

    /**
     * Set rotation mode
     */
    fun setRotationMode(mode: RotationMode) {
        rotationMode = mode
    }

    // ==================== Health Checking ====================

    /**
     * Check proxy health
     */
    suspend fun checkProxyHealth(proxy: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT) {
                testProxyConnection(proxy)
            } ?: false
            
            if (result) {
                healthyProxies.add(proxy.id)
                unhealthyProxies.remove(proxy.id)
                Log.d(TAG, "Proxy healthy: ${proxy.host}:${proxy.port}")
            } else {
                unhealthyProxies.add(proxy.id)
                healthyProxies.remove(proxy.id)
                Log.w(TAG, "Proxy unhealthy: ${proxy.host}:${proxy.port}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Proxy health check failed: ${e.message}")
            unhealthyProxies.add(proxy.id)
            healthyProxies.remove(proxy.id)
            false
        }
    }

    /**
     * Check all proxies health
     */
    suspend fun checkAllProxiesHealth(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        proxies.forEach { proxy ->
            results[proxy.id] = checkProxyHealth(proxy)
        }
        
        return results
    }

    /**
     * Test proxy connection
     */
    private suspend fun testProxyConnection(proxy: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val javaProxy = createJavaProxy(proxy)
            
            val clientBuilder = OkHttpClient.Builder()
                .proxy(javaProxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
            
            // Add proxy authentication if needed
            if (proxy.username != null && proxy.password != null) {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(proxy.username, proxy.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            
            val client = clientBuilder.build()
            val request = Request.Builder()
                .url(HEALTH_CHECK_URL)
                .build()
            
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Proxy test failed: ${e.message}")
            false
        }
    }

    /**
     * Get current IP via proxy
     */
    suspend fun getCurrentIp(proxy: ProxyConfig): String? = withContext(Dispatchers.IO) {
        try {
            val javaProxy = createJavaProxy(proxy)
            
            val clientBuilder = OkHttpClient.Builder()
                .proxy(javaProxy)
                .connectTimeout(10, TimeUnit.SECONDS)
            
            if (proxy.username != null && proxy.password != null) {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(proxy.username, proxy.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            
            val client = clientBuilder.build()
            val request = Request.Builder()
                .url(HEALTH_CHECK_URL)
                .build()
            
            val response = client.newCall(request).execute()
            response.body?.string()?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP: ${e.message}")
            null
        }
    }

    // ==================== Proxy Creation ====================

    /**
     * Create Java Proxy object
     */
    fun createJavaProxy(config: ProxyConfig): Proxy {
        val address = InetSocketAddress(config.host, config.port)
        
        // Set up authentication for SOCKS5
        if (config.type == ProxyType.SOCKS5 && config.username != null && config.password != null) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.username, config.password.toCharArray())
                }
            })
        }
        
        return when (config.type) {
            ProxyType.SOCKS5 -> Proxy(Proxy.Type.SOCKS, address)
            ProxyType.HTTP -> Proxy(Proxy.Type.HTTP, address)
            ProxyType.HTTPS -> Proxy(Proxy.Type.HTTP, address)
        }
    }

    /**
     * Get proxy for OkHttp with authentication
     */
    fun getOkHttpClientWithProxy(proxy: ProxyConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .proxy(createJavaProxy(proxy))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        
        if (proxy.username != null && proxy.password != null) {
            builder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(proxy.username, proxy.password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        
        return builder.build()
    }

    // ==================== Data Classes ====================

    data class ProxyConfig(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val country: String? = null,
        val timezone: String? = null,
        val name: String? = null
    ) {
        val id: String get() = "$host:$port"
    }

    enum class ProxyType {
        HTTP,
        HTTPS,
        SOCKS5
    }

    enum class RotationMode {
        ROUND_ROBIN,
        RANDOM,
        HEALTHY_FIRST
    }

    // ==================== Parsing ====================
    // Parsing functions moved to companion object above
}
