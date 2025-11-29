package com.automation.agent.network

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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

    private val client: OkHttpClient
    private val gson = Gson()

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Add proxy if available
        proxyManager?.getCurrentProxy()?.let { proxy ->
            builder.proxy(proxyManager.createJavaProxy(proxy))
        }

        client = builder.build()
    }

    /**
     * Register device
     */
    suspend fun registerDevice(deviceInfo: DeviceRegistrationRequest): DeviceRegistrationResponse? {
        val json = gson.toJson(deviceInfo)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/register")
            .post(body)
            .build()

        return executeRequest(request) { response ->
            gson.fromJson(response.body?.string(), DeviceRegistrationResponse::class.java)
        }
    }

    /**
     * Send heartbeat
     */
    suspend fun sendHeartbeat(deviceId: String): Boolean {
        val json = gson.toJson(HeartbeatRequest(deviceId))
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/heartbeat")
            .post(body)
            .build()

        return executeRequest(request) { response ->
            response.isSuccessful
        } ?: false
    }

    /**
     * Get tasks
     */
    suspend fun getTasks(deviceId: String): List<TaskResponse>? {
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks?deviceId=$deviceId")
            .get()
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: return@executeRequest null
            gson.fromJson(json, Array<TaskResponse>::class.java).toList()
        }
    }

    /**
     * Send task result
     */
    suspend fun sendTaskResult(taskId: String, result: TaskResultRequest): Boolean {
        val json = gson.toJson(result)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/api/agent/tasks/$taskId/result")
            .post(body)
            .build()

        return executeRequest(request) { response ->
            response.isSuccessful
        } ?: false
    }

    /**
     * Upload screenshot
     */
    suspend fun uploadScreenshot(
        deviceId: String,
        taskId: String,
        screenshotBytes: ByteArray
    ): Boolean {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("deviceId", deviceId)
            .addFormDataPart("taskId", taskId)
            .addFormDataPart(
                "screenshot",
                "screenshot.png",
                screenshotBytes.toRequestBody("image/png".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api/agent/screenshot")
            .post(requestBody)
            .build()

        return executeRequest(request) { response ->
            response.isSuccessful
        } ?: false
    }

    private suspend fun <T> executeRequest(
        request: Request,
        block: suspend (Response) -> T?
    ): T? {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                block(response)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Data classes
    data class DeviceRegistrationRequest(
        val androidId: String,
        val aaid: String,
        val model: String,
        val manufacturer: String,
        val version: String,
        val userAgent: String
    )

    data class DeviceRegistrationResponse(
        val deviceId: String,
        val status: String
    )

    data class HeartbeatRequest(
        val deviceId: String
    )

    data class TaskResponse(
        val id: String,
        val name: String,
        val type: String,
        val config: Map<String, Any>
    )

    data class TaskResultRequest(
        val success: Boolean,
        val data: Map<String, Any>? = null,
        val error: String? = null
    )
}

