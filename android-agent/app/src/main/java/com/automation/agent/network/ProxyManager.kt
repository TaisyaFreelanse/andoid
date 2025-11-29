package com.automation.agent.network

import java.net.Proxy
import java.net.InetSocketAddress

/**
 * ProxyManager - Manages proxy connections
 * 
 * Supports:
 * - HTTP/HTTPS proxies
 * - SOCKS5 proxies
 * - Proxy rotation
 * - Proxy health checking
 */
class ProxyManager {

    private val proxies = mutableListOf<ProxyConfig>()
    private var currentProxyIndex = 0

    /**
     * Add proxy configuration
     */
    fun addProxy(config: ProxyConfig) {
        proxies.add(config)
    }

    /**
     * Get next proxy (round-robin)
     */
    fun getNextProxy(): ProxyConfig? {
        if (proxies.isEmpty()) return null
        
        val proxy = proxies[currentProxyIndex]
        currentProxyIndex = (currentProxyIndex + 1) % proxies.size
        return proxy
    }

    /**
     * Get current proxy
     */
    fun getCurrentProxy(): ProxyConfig? {
        if (proxies.isEmpty()) return null
        return proxies[currentProxyIndex]
    }

    /**
     * Check proxy health
     */
    suspend fun checkProxyHealth(proxy: ProxyConfig): Boolean {
        // TODO: Test proxy connection
        return true
    }

    /**
     * Create Java Proxy object
     */
    fun createJavaProxy(config: ProxyConfig): Proxy {
        val address = InetSocketAddress(config.host, config.port)
        return when (config.type) {
            ProxyType.SOCKS5 -> Proxy(Proxy.Type.SOCKS, address)
            ProxyType.HTTP -> Proxy(Proxy.Type.HTTP, address)
            ProxyType.HTTPS -> Proxy(Proxy.Type.HTTP, address)
        }
    }

    data class ProxyConfig(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null
    )

    enum class ProxyType {
        HTTP,
        HTTPS,
        SOCKS5
    }

    companion object {
        /**
         * Parse SOCKS5 proxy string: socks5://host:port:username:password
         */
        fun parseSocks5(proxyString: String): ProxyConfig? {
            val regex = Regex("socks5://([^:]+):(\\d+):([^:]+):(.+)")
            val match = regex.find(proxyString) ?: return null
            
            return ProxyConfig(
                type = ProxyType.SOCKS5,
                host = match.groupValues[1],
                port = match.groupValues[2].toInt(),
                username = match.groupValues[3],
                password = match.groupValues[4]
            )
        }
    }
}

