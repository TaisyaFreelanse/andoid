package com.automation.agent

import android.app.Application
import android.util.Log
import com.automation.agent.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application class with global exception handling
 */
class App : Application() {
    
    companion object {
        private const val TAG = "App"
        private var instance: App? = null
        
        fun getInstance(): App? = instance
    }
    
    private var applicationScope: CoroutineScope? = null
    private var apiClient: ApiClient? = null
    private var deviceId: String? = null
    
    override fun onCreate() {
        try {
            super.onCreate()
            Log.i(TAG, "Application.onCreate() started")
            
            instance = this
            Log.i(TAG, "Application instance set")
            
            // Initialize coroutine scope after super.onCreate() to ensure system is ready
            try {
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                Log.i(TAG, "Coroutine scope initialized")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize coroutine scope: ${e.message}, using IO dispatcher", e)
                // Fallback to IO dispatcher if Main is not available
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            
            // Set up global uncaught exception handler
            try {
                Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
                    Log.e(TAG, "Uncaught exception caught by handler", exception)
                    handleUncaughtException(thread, exception)
                }
                Log.i(TAG, "Global uncaught exception handler set")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set uncaught exception handler: ${e.message}", e)
            }
            
            Log.i(TAG, "Application initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Exception in Application.onCreate(): ${e.message}", e)
            e.printStackTrace()
            // Don't re-throw - let app try to start anyway
            // Re-throwing will prevent app from starting at all
        }
    }
    
    private fun handleUncaughtException(thread: Thread, exception: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception in thread: ${thread.name}", exception)
            
            // Get stack trace
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            exception.printStackTrace(pw)
            val stackTrace = sw.toString()
            
            // Log to Android logcat
            Log.e(TAG, "Exception: ${exception.message}")
            Log.e(TAG, "StackTrace: $stackTrace")
            
            // Try to send to backend if available (use IO dispatcher to be safe)
            try {
                apiClient?.let { client ->
                    deviceId?.let { id ->
                        applicationScope?.launch(Dispatchers.IO) {
                            try {
                                client.sendLog(
                                    "error",
                                    TAG,
                                    "Uncaught exception: ${exception.message}\n${stackTrace.take(1000)}"
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send exception log: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exception to backend: ${e.message}")
            }
            
            // Log that exception was handled
            Log.w(TAG, "Exception handled, app will continue running")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in exception handler: ${e.message}", e)
        }
    }
    
    fun setApiClient(client: ApiClient) {
        this.apiClient = client
    }
    
    fun setDeviceId(id: String) {
        this.deviceId = id
    }
}

