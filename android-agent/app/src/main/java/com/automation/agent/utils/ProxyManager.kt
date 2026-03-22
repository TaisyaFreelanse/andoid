package com.automation.agent.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.*
import java.io.*
import javax.net.ssl.HttpsURLConnection

/**
 * Manages SOCKS5 proxy configuration and geolocation detection
 * Now supports system-wide proxy via local HTTP proxy server
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
        val longitude: Double,
        val ip: String? = null
    )
    
    private var currentProxy: ProxyConfig? = null
    private var proxyLocation: ProxyLocation? = null
    private var localHttpProxyPort: Int? = null
    private var localProxyJob: Job? = null
    
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
     * Setup SOCKS5 proxy with system-wide support via local HTTP proxy
     * 
     * Creates a local HTTP proxy server that forwards traffic through SOCKS5,
     * then sets system-wide HTTP proxy via root commands.
     * This ensures WebView and all apps use the proxy correctly.
     */
    suspend fun setupProxy(config: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Setting up SOCKS5 proxy: ${config.host}:${config.port}")
        
        try {
            currentProxy = config
            
            // Step 1: Start local HTTP proxy server that forwards through SOCKS5
            val localPort = startLocalHttpProxy(config)
            if (localPort == null) {
                Log.e(TAG, "Failed to start local HTTP proxy")
                return@withContext false
            }
            localHttpProxyPort = localPort
            Log.i(TAG, "Local HTTP proxy started on port $localPort")
            
            // Step 2: Set system-wide HTTP proxy via root (for WebView and all apps)
            val proxyHost = "127.0.0.1"
            val proxyPort = localPort
            val proxySetting = "$proxyHost:$proxyPort"
            
            // Set global HTTP proxy via root (multiple settings for compatibility)
            val success1 = rootUtils.setGlobalSetting("http_proxy", proxySetting)
            val success2 = rootUtils.setGlobalSetting("global_http_proxy", proxySetting)
            val success3 = rootUtils.setGlobalSetting("http_proxy_host", proxyHost)
            val success4 = rootUtils.setGlobalSetting("http_proxy_port", proxyPort.toString())
            
            if (success1 || success2) {
                Log.i(TAG, "System-wide HTTP proxy set: $proxySetting (http_proxy: $success1, global_http_proxy: $success2, host: $success3, port: $success4)")
            } else {
                Log.w(TAG, "Failed to set system-wide HTTP proxy via root")
            }
            
            // Verify proxy setting
            delay(500)
            val verifyProxy = rootUtils.getGlobalSetting("http_proxy")
            if (verifyProxy == proxySetting) {
                Log.i(TAG, "✓ Proxy setting verified: $verifyProxy")
            } else {
                Log.w(TAG, "⚠ Proxy setting verification failed. Expected: $proxySetting, Got: $verifyProxy")
            }
            
            // Store SOCKS5 proxy for Java networking (fallback)
            System.setProperty("socksProxyHost", config.host)
            System.setProperty("socksProxyPort", config.port.toString())
            
            // Set SOCKS authentication
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    return if (requestingHost == config.host) {
                        java.net.PasswordAuthentication(config.username, config.password.toCharArray())
                    } else null
                }
            })
            
            Log.i(TAG, "SOCKS5 proxy configured with system-wide HTTP proxy support")
            
            // Detect location based on state
            if (config.state != null) {
                val timezone = STATE_TIMEZONE[config.state] ?: "America/New_York"
                val coords = STATE_COORDINATES[config.state] ?: Pair(40.7128, -74.0060)
                // Get IP address
                val ip = try {
                    getCurrentIp(config)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get IP during setup: ${e.message}")
                    null
                }
                
                proxyLocation = ProxyLocation(
                    country = config.country,
                    state = config.state,
                    city = config.state,
                    timezone = timezone,
                    latitude = coords.first,
                    longitude = coords.second,
                    ip = ip
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
     * Start local HTTP proxy server that forwards through SOCKS5
     */
    private suspend fun startLocalHttpProxy(socksConfig: ProxyConfig): Int? = withContext(Dispatchers.IO) {
        try {
            // Stop existing proxy if any
            stopLocalHttpProxy()
            
            // Find available port
            val serverSocket = ServerSocket(0)
            val port = serverSocket.localPort
            serverSocket.close()
            
            Log.i(TAG, "Starting local HTTP proxy on port $port")
            
            // Start proxy server in background
            localProxyJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val server = ServerSocket(port)
                    Log.i(TAG, "Local HTTP proxy listening on port $port")
                    
                    while (isActive && !server.isClosed) {
                        try {
                            val clientSocket = server.accept()
                            
                            // Handle each client in separate coroutine
                            launch {
                                handleProxyConnection(clientSocket, socksConfig)
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                Log.e(TAG, "Error accepting connection: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Local HTTP proxy error: ${e.message}", e)
                }
            }
            
            // Wait a bit to ensure server started
            delay(500)
            
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local HTTP proxy: ${e.message}", e)
            null
        }
    }
    
    /**
     * Handle proxy connection - forward HTTP request through SOCKS5
     */
    private suspend fun handleProxyConnection(clientSocket: Socket, socksConfig: ProxyConfig) = withContext(Dispatchers.IO) {
        var clientInput: BufferedReader? = null
        var clientOutput: OutputStream? = null
        
        try {
            clientInput = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            clientOutput = clientSocket.getOutputStream()
            
            // Read HTTP request
            val requestLine = clientInput.readLine() ?: return@withContext
            Log.d(TAG, "Proxy request: $requestLine")
            
            // Parse CONNECT or GET request
            if (requestLine.startsWith("CONNECT")) {
                // HTTPS tunnel - use URLConnection through SOCKS5
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext
                val target = parts[1]
                val (host, port) = parseHostPort(target)
                
                // Use URLConnection with SOCKS5 proxy
                val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksConfig.host, socksConfig.port))
                
                // Set authentication
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                        return java.net.PasswordAuthentication(socksConfig.username, socksConfig.password.toCharArray())
                    }
                })
                
                // For CONNECT (HTTPS), tunnel through SOCKS5
                try {
                    // Set authenticator for SOCKS5
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                            return java.net.PasswordAuthentication(socksConfig.username, socksConfig.password.toCharArray())
                        }
                    })
                    
                    // Create socket through SOCKS5 proxy
                    val targetSocket = Socket(socksProxy)
                    targetSocket.connect(InetSocketAddress(host, port), 15000)
                    targetSocket.soTimeout = 30000
                    
                    Log.d(TAG, "CONNECT tunnel established: $host:$port")
                    
                    // Send 200 Connection established to client
                    clientOutput.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    clientOutput.flush()
                    
                    // Tunnel data bidirectionally in separate coroutines
                    val job1 = launch { tunnelData(clientSocket, targetSocket) }
                    val job2 = launch { tunnelData(targetSocket, clientSocket) }
                    
                    // Wait for either to complete (connection closed)
                    try {
                        job1.join()
                    } catch (e: Exception) {
                        job2.cancel()
                    }
                    try {
                        job2.join()
                    } catch (e: Exception) {
                        job1.cancel()
                    }
                    
                    targetSocket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "CONNECT failed: ${e.message}", e)
                    try {
                        clientOutput.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        clientOutput.flush()
                    } catch (ignored: Exception) {}
                }
            } else {
                // HTTP GET/POST request - use URLConnection
                val url = extractUrlFromRequest(requestLine)
                if (url != null) {
                    val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "http://$url"
                    
                    try {
                        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksConfig.host, socksConfig.port))
                        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                            override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                                return java.net.PasswordAuthentication(socksConfig.username, socksConfig.password.toCharArray())
                            }
                        })
                        
                        val connection = URL(fullUrl).openConnection(socksProxy) as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 30000
                        connection.requestMethod = "GET"
                        
                        // Forward headers
                        var line: String?
                        while (clientInput.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                            val headerParts = line!!.split(":", limit = 2)
                            if (headerParts.size == 2) {
                                val headerName = headerParts[0].trim()
                                val headerValue = headerParts[1].trim()
                                if (headerName.lowercase() !in listOf("host", "connection", "proxy-connection")) {
                                    connection.setRequestProperty(headerName, headerValue)
                                }
                            }
                        }
                        
                        connection.connect()
                        
                        // Forward response
                        val responseCode = connection.responseCode
                        val responseMessage = connection.responseMessage ?: "OK"
                        clientOutput.write("HTTP/1.1 $responseCode $responseMessage\r\n".toByteArray())
                        
                        // Forward response headers
                        connection.headerFields.forEach { (key, values) ->
                            if (key != null && key.lowercase() != "transfer-encoding") {
                                values.forEach { value ->
                                    clientOutput.write("$key: $value\r\n".toByteArray())
                                }
                            }
                        }
                        clientOutput.write("\r\n".toByteArray())
                        
                        // Forward response body
                        val inputStream = if (responseCode >= 200 && responseCode < 300) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                        
                        inputStream?.use { stream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                clientOutput.write(buffer, 0, bytesRead)
                            }
                        }
                        clientOutput.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "HTTP request failed: ${e.message}")
                        clientOutput.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy connection error: ${e.message}")
            try {
                clientOutput?.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            } catch (ignored: Exception) {}
        } finally {
            clientSocket.close()
        }
    }
    
    private fun parseHostPort(target: String): Pair<String, Int> {
        val parts = target.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 80 else 80
        return Pair(host, port)
    }
    
    private fun extractUrlFromRequest(requestLine: String): String? {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val url = parts[1]
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            URL(url).host
        } else {
            url.split("/")[0]
        }
    }
    
    private suspend fun tunnelData(source: Socket, dest: Socket) = withContext(Dispatchers.IO) {
        try {
            val sourceInput = source.getInputStream()
            val destOutput = dest.getOutputStream()
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (sourceInput.read(buffer).also { bytesRead = it } != -1) {
                destOutput.write(buffer, 0, bytesRead)
                destOutput.flush()
            }
        } catch (e: Exception) {
            // Connection closed or error
        }
    }
    
    /**
     * Stop local HTTP proxy server
     */
    private suspend fun stopLocalHttpProxy() = withContext(Dispatchers.IO) {
        try {
            localProxyJob?.cancel()
            localProxyJob = null
            localHttpProxyPort = null
            Log.i(TAG, "Local HTTP proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping local proxy: ${e.message}")
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
            
            // First get IP address
            val ip = currentProxy?.let { proxyConfig ->
                try {
                    getCurrentIp(proxyConfig)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get IP: ${e.message}")
                    null
                }
            }
            
            // Use ip-api.com for geolocation (free, no key required)
            val url = URL("http://ip-api.com/json/?fields=status,country,countryCode,region,regionName,city,lat,lon,timezone,query")
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
            val detectedIp = extractJsonValue(response, "query") ?: ip
            
            proxyLocation = ProxyLocation(
                country = countryCode,
                state = state,
                city = city,
                timezone = timezone,
                latitude = lat,
                longitude = lon,
                ip = detectedIp
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
     * Get local HTTP proxy port (tunnels through SOCKS5 with auth).
     * Use 127.0.0.1:port as HTTP proxy when non-null — works for OkHttp.
     */
    fun getLocalHttpProxyPort(): Int? = localHttpProxyPort
    
    /**
     * Clear proxy settings (app-level and system-wide)
     */
    suspend fun clearProxy(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Clearing proxy settings...")
            
            // Stop local HTTP proxy
            stopLocalHttpProxy()
            
            // Clear system-wide HTTP proxy via root
            rootUtils.setGlobalSetting("http_proxy", "")
            rootUtils.setGlobalSetting("global_http_proxy", "")
            
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
    
    /**
     * Get current IP address through proxy
     */
    suspend fun getCurrentIp(proxy: ProxyConfig): String? = withContext(Dispatchers.IO) {
        try {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))
            
            // Set authentication
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                    return java.net.PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                }
            })
            
            // Use ipify.org to get IP
            val url = URL("https://api.ipify.org")
            val connection = url.openConnection(socksProxy)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val ip = connection.getInputStream().bufferedReader().readText().trim()
            Log.i(TAG, "Current IP through proxy: $ip")
            ip
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP: ${e.message}", e)
            null
        }
    }
}

