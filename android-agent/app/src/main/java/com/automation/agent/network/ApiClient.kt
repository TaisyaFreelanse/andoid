package com.automation.agent.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * ApiClient - HTTP client for backend API communication
 * 
 * Endpoints:
 * - POST /api/agent/register - Register device
 * - POST /api/agent/heartbeat - Send heartbeat
 * - GET /api/agent/tasks - Get tasks
 * - POST /api/agent/tasks/:id/result - Send task result
 * - POST /api/agent/screenshot - Upload screenshot
 */
class ApiClient(
    private val baseUrl: String,
    private val proxyManager: ProxyManager? = null
) {

    private var client: OkHttpClient
    private val gson = GsonBuilder()
        .serializeNulls()  // Serialize null values
        .setLenient()  // Allow lenient parsing
        .create()
    private var authToken: String? = null
    private var storedDeviceId: String? = null
    
    fun setDeviceId(id: String) {
        storedDeviceId = id
    }

    init {
        client = buildClient()
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Add proxy if available
        proxyManager?.getCurrentProxy()?.let { proxy ->
            builder.proxy(proxyManager.createJavaProxy(proxy))
            
            // Add proxy authentication if needed
            if (proxy.username != null && proxy.password != null) {
                builder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(proxy.username, proxy.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }

        return builder.build()
    }

    /**
     * Update proxy and rebuild client
     */
    fun updateProxy() {
        client = buildClient()
    }

    /**
     * Set authentication token
     */
    fun setAuthToken(token: String) {
        authToken = token
    }

    /**
     * Register device
     */
    suspend fun registerDevice(deviceInfo: DeviceRegistrationRequest): DeviceRegistrationResponse? {
        // Log all fields before serialization
        android.util.Log.i("ApiClient", "=== DeviceRegistrationRequest fields ===")
        android.util.Log.i("ApiClient", "androidId: ${deviceInfo.androidId}")
        android.util.Log.i("ApiClient", "aaid: ${deviceInfo.aaid}")
        android.util.Log.i("ApiClient", "model: ${deviceInfo.model}")
        android.util.Log.i("ApiClient", "manufacturer: ${deviceInfo.manufacturer}")
        android.util.Log.i("ApiClient", "version: ${deviceInfo.version}")
        android.util.Log.i("ApiClient", "userAgent: ${deviceInfo.userAgent}")
        android.util.Log.i("ApiClient", "isRooted: ${deviceInfo.isRooted}")
        android.util.Log.i("ApiClient", "rootCheckDetails: ${deviceInfo.rootCheckDetails}")
        android.util.Log.i("ApiClient", "rootCheckMethods: ${deviceInfo.rootCheckMethods}")
        android.util.Log.i("ApiClient", "existingDeviceId: ${deviceInfo.existingDeviceId}")
        
        val json = gson.toJson(deviceInfo)
        android.util.Log.i("ApiClient", "=== Serialized JSON ===")
        android.util.Log.i("ApiClient", "JSON: $json")
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/register")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request) { response ->
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                gson.fromJson(responseBody, DeviceRegistrationResponse::class.java)
            } else {
                null
            }
        }
    }

    /**
     * Send heartbeat
     */
    suspend fun sendHeartbeat(deviceId: String, status: HeartbeatStatus? = null): HeartbeatResponse? {
        val request = HeartbeatRequest(
            deviceId = deviceId,
            status = status?.name?.lowercase() ?: "online",
            timestamp = System.currentTimeMillis()
        )
        
        val json = gson.toJson(request)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/agent/heartbeat")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-device-id", deviceId)
            .apply {
                authToken?.let { 
                    addHeader("x-agent-token", it)
                    addHeader("Authorization", "Bearer $it") 
                }
            }
            .build()

        return executeRequest(httpRequest) { response ->
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                try {
                    gson.fromJson(responseBody, HeartbeatResponse::class.java)
                } catch (e: Exception) {
                    HeartbeatResponse(success = true)
                }
            } else {
                null
            }
        }
    }

    /**
     * Send heartbeat (simple version returning boolean)
     */
    suspend fun sendHeartbeat(deviceId: String): Boolean {
        val response = sendHeartbeat(deviceId, HeartbeatStatus.ONLINE)
        return response?.success == true
    }

    /**
     * Get tasks for device
     */
    suspend fun getTasks(deviceId: String): List<TaskResponse>? {
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks?deviceId=$deviceId")
            .get()
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        return executeRequest(request) { response ->
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@executeRequest null
                val type = object : TypeToken<List<TaskResponse>>() {}.type
                gson.fromJson(json, type)
            } else {
                null
            }
        }
    }

    /**
     * Get single task by ID
     */
    suspend fun getTask(taskId: String): TaskResponse? {
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks/$taskId")
            .get()
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        return executeRequest(request) { response ->
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@executeRequest null
                gson.fromJson(json, TaskResponse::class.java)
            } else {
                null
            }
        }
    }

    /**
     * Update task status
     */
    suspend fun updateTaskStatus(taskId: String, status: String): Boolean {
        val deviceId = storedDeviceId
        if (deviceId == null) {
            android.util.Log.e("ApiClient", "updateTaskStatus failed: storedDeviceId is null")
            return false
        }
        
        val json = gson.toJson(mapOf("status" to status))
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks/$taskId/status")
            .put(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Device-Id", deviceId)  // Required by backend
            .apply {
                authToken?.let { 
                    addHeader("Authorization", "Bearer $it")
                    addHeader("X-Agent-Token", it)  // Also add agent token header
                }
            }
            .build()
        
        android.util.Log.i("ApiClient", "updateTaskStatus: taskId=$taskId, status=$status, deviceId=$deviceId")

        return executeRequest(request) { response ->
            if (!response.isSuccessful) {
                android.util.Log.e("ApiClient", "updateTaskStatus failed: HTTP ${response.code}")
                try {
                    val errorBody = response.body?.string()
                    android.util.Log.e("ApiClient", "Error body: $errorBody")
                } catch (e: Exception) { }
            }
            response.isSuccessful
        } ?: false
    }

    /**
     * Send task result
     */
    suspend fun sendTaskResult(taskId: String, result: TaskResultRequest, deviceId: String): Boolean {
        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendTaskResult ENTRY: taskId=$taskId, deviceId=$deviceId, success=${result.success}")
            // Отправляем на backend через LogInterceptor
            try {
                com.automation.agent.utils.LogInterceptor.i("DEBUG_LOG", "sendTaskResult ENTRY: taskId=$taskId, deviceId=$deviceId, success=${result.success}")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion
        
        val json = gson.toJson(result)
        val body = json.toRequestBody("application/json".toMediaType())
        
        android.util.Log.i("ApiClient", "Sending task result for taskId: $taskId, deviceId: $deviceId, success: ${result.success}")
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks/$taskId/result")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Device-Id", deviceId)  // Required for backend to update task status
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendTaskResult BEFORE_REQUEST: url=$baseUrl/api/agent/tasks/$taskId/result")
            // Отправляем на backend через LogInterceptor
            try {
                com.automation.agent.utils.LogInterceptor.i("DEBUG_LOG", "sendTaskResult BEFORE_REQUEST: url=$baseUrl/api/agent/tasks/$taskId/result")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion

        val httpResult = executeRequest(request) { response ->
            // #region agent log
            try {
                android.util.Log.e("DEBUG_LOG", "sendTaskResult RESPONSE: code=${response.code}, isSuccessful=${response.isSuccessful}")
                // Отправляем на backend через LogInterceptor
                try {
                    com.automation.agent.utils.LogInterceptor.i("DEBUG_LOG", "sendTaskResult RESPONSE: code=${response.code}, isSuccessful=${response.isSuccessful}")
                } catch (e: Exception) { }
            } catch (e: Exception) { }
            // #endregion
            
            if (response.isSuccessful) {
                android.util.Log.i("ApiClient", "Task result sent successfully: taskId=$taskId, HTTP ${response.code}")
            } else {
                android.util.Log.e("ApiClient", "Task result send failed: taskId=$taskId, HTTP ${response.code}")
                try {
                    val errorBody = response.body?.string()
                    android.util.Log.e("ApiClient", "Task result error body: $errorBody")
                } catch (e: Exception) {
                    android.util.Log.e("ApiClient", "Failed to read error body: ${e.message}")
                }
            }
            response.isSuccessful
        }
        
        val finalResult = httpResult ?: run {
            android.util.Log.e("ApiClient", "Task result send failed: network error for taskId=$taskId")
            false
        }
        
        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendTaskResult EXIT: result=$finalResult")
            // Отправляем на backend через LogInterceptor
            try {
                com.automation.agent.utils.LogInterceptor.i("DEBUG_LOG", "sendTaskResult EXIT: result=$finalResult")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion
        
        return finalResult
    }

    /**
     * Upload screenshot
     */
    suspend fun uploadScreenshot(
        deviceId: String,
        taskId: String?,
        screenshotBytes: ByteArray,
        filename: String = "screenshot.png"
    ): UploadResponse? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "screenshot",
                filename,
                screenshotBytes.toRequestBody("image/png".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api/agent/screenshot")
            .post(requestBody)
            .addHeader("x-device-id", deviceId)
            .apply {
                taskId?.let { addHeader("x-task-id", it) }
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        return executeRequest(request) { response ->
            if (response.isSuccessful) {
                val json = response.body?.string()
                try {
                    gson.fromJson(json, UploadResponse::class.java)
                } catch (e: Exception) {
                    UploadResponse(success = true, path = null)
                }
            } else {
                null
            }
        }
    }

    /**
     * Send parsed data
     */
    suspend fun sendParsedData(data: ParsedDataRequest): Boolean {
        val json = gson.toJson(data)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/parsed-data")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        return executeRequest(request) { response ->
            response.isSuccessful
        } ?: false
    }

    /**
     * Get proxy configuration
     */
    suspend fun getProxyConfig(deviceId: String): ProxyConfigResponse? {
        val request = Request.Builder()
            .url("$baseUrl/api/agent/proxy?deviceId=$deviceId")
            .get()
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        return executeRequest(request) { response ->
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@executeRequest null
                gson.fromJson(json, ProxyConfigResponse::class.java)
            } else {
                null
            }
        }
    }

    /**
     * Send log to backend
     */
    suspend fun sendLog(
        level: String,
        tag: String,
        message: String,
        taskId: String? = null
    ): Boolean {
        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendLog ENTRY: level=$level, tag=$tag, deviceId=$storedDeviceId, message=${message.take(50)}")
            // Отправляем на backend через LogInterceptor (избегаем рекурсии)
            try {
                com.automation.agent.utils.LogInterceptor.d("DEBUG_LOG", "sendLog ENTRY: level=$level, tag=$tag, deviceId=$storedDeviceId, message=${message.take(50)}")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion
        
        val deviceId = storedDeviceId
        if (deviceId == null) {
            android.util.Log.w("ApiClient", "sendLog failed: storedDeviceId is null")
            // #region agent log
            try {
                android.util.Log.e("DEBUG_LOG", "sendLog DEVICE_ID_NULL: storedDeviceId is null, returning false")
                // Отправляем на backend через LogInterceptor
                try {
                    com.automation.agent.utils.LogInterceptor.e("DEBUG_LOG", "sendLog DEVICE_ID_NULL: storedDeviceId is null, returning false")
                } catch (e: Exception) { }
            } catch (e: Exception) { }
            // #endregion
            return false
        }
        
        val logData = mapOf(
            "level" to level,
            "tag" to tag,
            "message" to message,
            "taskId" to (taskId ?: ""),
            "timestamp" to System.currentTimeMillis()
        )
        
        val json = gson.toJson(logData)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/logs")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Device-Id", deviceId)
            .apply {
                authToken?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()

        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendLog BEFORE_REQUEST: url=$baseUrl/api/agent/logs, deviceId=$deviceId")
            // Отправляем на backend через LogInterceptor
            try {
                com.automation.agent.utils.LogInterceptor.d("DEBUG_LOG", "sendLog BEFORE_REQUEST: url=$baseUrl/api/agent/logs, deviceId=$deviceId")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion

        val result = executeRequest(request) { response ->
            // #region agent log
            try {
                android.util.Log.e("DEBUG_LOG", "sendLog RESPONSE: code=${response.code}, isSuccessful=${response.isSuccessful}")
                // Отправляем на backend через LogInterceptor
                try {
                    com.automation.agent.utils.LogInterceptor.d("DEBUG_LOG", "sendLog RESPONSE: code=${response.code}, isSuccessful=${response.isSuccessful}")
                } catch (e: Exception) { }
            } catch (e: Exception) { }
            // #endregion
            
            if (!response.isSuccessful) {
                android.util.Log.w("ApiClient", "sendLog failed: HTTP ${response.code} for tag=$tag, message=${message.take(50)}")
                try {
                    val errorBody = response.body?.string()
                    android.util.Log.w("ApiClient", "sendLog error body: $errorBody")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            response.isSuccessful
        }
        
        // #region agent log
        try {
            android.util.Log.e("DEBUG_LOG", "sendLog EXIT: result=${result ?: false}")
            // Отправляем на backend через LogInterceptor
            try {
                com.automation.agent.utils.LogInterceptor.d("DEBUG_LOG", "sendLog EXIT: result=${result ?: false}")
            } catch (e: Exception) { }
        } catch (e: Exception) { }
        // #endregion
        
        return result ?: false
    }

    /**
     * Execute HTTP request with error handling
     */
    private suspend fun <T> executeRequest(
        request: Request,
        block: suspend (Response) -> T?
    ): T? {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                response.use { block(it) }
            } catch (e: IOException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    // ==================== Data Classes ====================

    data class DeviceRegistrationRequest(
        val androidId: String,
        val aaid: String,
        val model: String,
        val manufacturer: String,
        val version: String,
        val userAgent: String,
        val brand: String? = null,
        val sdkVersion: Int? = null,
        val timezone: String? = null,
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val language: String? = null,
        val country: String? = null,
        val isRooted: Boolean? = null,
        val existingDeviceId: String? = null,
        val rootCheckDetails: String? = null,
        val rootCheckMethods: String? = null
    )

    data class DeviceRegistrationResponse(
        val deviceId: String,
        val status: String? = null,
        val agentToken: String? = null,
        val token: String? = null,
        val message: String? = null
    )

    data class HeartbeatRequest(
        val deviceId: String,
        val status: String = "online",
        val timestamp: Long = System.currentTimeMillis()
    )

    data class HeartbeatResponse(
        val success: Boolean = true,
        val message: String? = null,
        val commands: List<String>? = null,
        val tasks: List<TaskResponse>? = null
    )

    enum class HeartbeatStatus {
        ONLINE,
        BUSY,
        IDLE,
        ERROR
    }

    data class TaskResponse(
        val id: String,
        val name: String,
        val type: String,
        val status: String,
        val priority: Int = 0,
        val config: Map<String, Any>? = null,
        val steps: List<TaskStep>? = null,
        val createdAt: String? = null
    )

    data class TaskStep(
        val type: String,
        val config: Map<String, Any>
    )

    data class TaskResultRequest(
        val success: Boolean,
        val data: Map<String, Any>? = null,
        val error: String? = null,
        val executionTime: Long? = null,
        val screenshots: List<String>? = null
    )

    data class UploadResponse(
        val success: Boolean,
        val path: String?,
        val url: String? = null
    )

    data class ParsedDataRequest(
        val deviceId: String,
        val taskId: String?,
        val url: String,
        val adUrl: String?,
        val adDomain: String?,
        val screenshotPath: String?,
        val extractedData: Map<String, Any>? = null
    )

    data class ProxyConfigResponse(
        val type: String, // http, https, socks5
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val country: String?,
        val timezone: String?
    )
}
