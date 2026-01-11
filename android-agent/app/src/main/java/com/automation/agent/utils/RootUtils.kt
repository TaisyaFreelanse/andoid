package com.automation.agent.utils

import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RootUtils - Utility for root operations
 * 
 * Uses libsu library for safe root access.
 * All operations require root privileges.
 * 
 * Features:
 * - Shell command execution
 * - File operations (read/write/delete)
 * - System property manipulation
 * - Settings modification
 * - Package management
 */
class RootUtils {

    companion object {
        private const val TAG = "RootUtils"
        
        // System paths
        const val BUILD_PROP_PATH = "/system/build.prop"
        const val HOSTS_PATH = "/system/etc/hosts"
        
        // Common packages
        const val CHROME_PACKAGE = "com.android.chrome"
        const val WEBVIEW_PACKAGE = "com.google.android.webview"
        const val GMS_PACKAGE = "com.google.android.gms"
        
        // Initialize shell on class load
        init {
            Shell.enableVerboseLogging = true
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(30)
            )
        }
    }

    // ==================== Root Check ====================

    /**
     * Root check result with details
     */
    data class RootCheckResult(
        val isAvailable: Boolean,
        val isGranted: Boolean,
        val details: String,
        val foundSuPath: String? = null,
        val checkMethods: List<String> = emptyList()
    )

    /**
     * Check root with detailed information for logging
     */
    fun checkRootDetailed(): RootCheckResult {
        val methods = mutableListOf<String>()
        var foundSuPath: String? = null
        
        Log.d(TAG, "=== Starting detailed root check ===")
        methods.add("Starting root check")
        
        // Method 1: Check if root was already granted to app via libsu
        val libsuGranted = try {
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking libsu granted: ${e.message}")
            methods.add("Error checking libsu: ${e.message}")
            false
        }
        methods.add("Method 1 - libsu granted: $libsuGranted")
        Log.d(TAG, "Method 1 - libsu granted: $libsuGranted")
        
        if (libsuGranted) {
            Log.d(TAG, "✓ Root already granted via libsu")
            return RootCheckResult(
                isAvailable = true,
                isGranted = true,
                details = "Root granted via libsu",
                checkMethods = methods
            )
        }
        
        // Method 2: Check if su binary exists (device is rooted)
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/system/xbin/daemonsu",
            "/system/etc/init.d/99SuperSUDaemon"
        )
        
        var suExists = false
        
        // Method 2a: Check file existence
        for (path in suPaths) {
            try {
                val exists = java.io.File(path).exists()
                if (exists) {
                    suExists = true
                    foundSuPath = path
                    methods.add("Found su at: $path")
                    Log.d(TAG, "✓ Found su binary at: $path")
                    break
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
        
        // Method 2b: Try to execute 'which su' command
        if (!suExists) {
            try {
                val process = Runtime.getRuntime().exec("which su")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val suPath = reader.readLine()
                reader.close()
                process.waitFor()
                
                if (suPath != null && suPath.isNotEmpty()) {
                    suExists = true
                    foundSuPath = suPath.trim()
                    methods.add("Found su via 'which su': $foundSuPath")
                    Log.d(TAG, "✓ Found su via 'which su': $foundSuPath")
                }
            } catch (e: Exception) {
                methods.add("Error running 'which su': ${e.message}")
            }
        }
        
        // Method 2c: Try to execute 'su -c id' to check if su works
        if (!suExists) {
            try {
                val process = Runtime.getRuntime().exec("su -c id")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readLine()
                reader.close()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (output != null && output.contains("uid=0")) {
                    suExists = true
                    foundSuPath = "su (working)"
                    methods.add("Found working su via 'su -c id': $output")
                    Log.d(TAG, "✓ Found working su via 'su -c id': $output")
                }
            } catch (e: Exception) {
                methods.add("Error testing 'su -c id': ${e.message}")
            }
        }
        
        if (!suExists) {
            methods.add("No su binary found")
            val details = "No su binary found in standard locations. Methods tried: ${methods.joinToString("; ")}"
            Log.w(TAG, "✗ No su binary found")
            return RootCheckResult(
                isAvailable = false,
                isGranted = false,
                details = details,
                checkMethods = methods
            )
        }
        
        methods.add("su binary found at: $foundSuPath")
        
        // Method 3: Try to get root shell via libsu
        val hasRootShell = try {
            val shell = Shell.getShell()
            val isRoot = shell.isRoot
            methods.add("libsu shell check: $isRoot")
            isRoot
        } catch (e: Exception) {
            methods.add("libsu shell error: ${e.message}")
            false
        }
        
        val details = if (hasRootShell) {
            "Root available and granted. su found at: $foundSuPath"
        } else {
            "Device is rooted (su found at: $foundSuPath) but app needs root permission. Methods: ${methods.joinToString("; ")}"
        }
        
        return RootCheckResult(
            isAvailable = suExists,
            isGranted = hasRootShell,
            details = details,
            foundSuPath = foundSuPath,
            checkMethods = methods
        )
    }

    /**
     * Check if device has root access
     * Uses multiple methods to verify root availability:
     * 1. Check if libsu already has root granted
     * 2. Check if su binary exists (try multiple methods)
     * 3. Try to execute a simple root command via Runtime
     */
    fun isRootAvailable(): Boolean {
        Log.d(TAG, "=== Starting root check ===")
        
        // Method 1: Check if root was already granted to app via libsu
        val libsuGranted = try {
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking libsu granted: ${e.message}")
            false
        }
        Log.d(TAG, "Method 1 - libsu granted: $libsuGranted")
        if (libsuGranted) {
            Log.d(TAG, "✓ Root already granted via libsu")
            return true
        }
        
        // Method 2: Check if su binary exists (device is rooted)
        // Try multiple methods to find su
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/system/xbin/daemonsu",
            "/system/etc/init.d/99SuperSUDaemon"
        )
        
        var suExists = false
        var foundPath: String? = null
        
        // Method 2a: Check file existence
        for (path in suPaths) {
            try {
                val exists = java.io.File(path).exists()
                Log.d(TAG, "Checking $path: $exists")
                if (exists) {
                    suExists = true
                    foundPath = path
                    Log.d(TAG, "✓ Found su binary at: $path")
                    break
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error checking $path: ${e.message}")
            }
        }
        
        // Method 2b: Try to execute 'which su' command
        if (!suExists) {
            try {
                val process = Runtime.getRuntime().exec("which su")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val suPath = reader.readLine()
                reader.close()
                process.waitFor()
                
                if (suPath != null && suPath.isNotEmpty()) {
                    suExists = true
                    foundPath = suPath.trim()
                    Log.d(TAG, "✓ Found su via 'which su': $foundPath")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error running 'which su': ${e.message}")
            }
        }
        
        // Method 2c: Try to execute 'su -c id' to check if su works
        if (!suExists) {
            try {
                val process = Runtime.getRuntime().exec("su -c id")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readLine()
                reader.close()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (output != null && output.contains("uid=0")) {
                    suExists = true
                    foundPath = "su (working)"
                    Log.d(TAG, "✓ Found working su via 'su -c id': $output")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error testing 'su -c id': ${e.message}")
            }
        }
        
        if (!suExists) {
            Log.w(TAG, "✗ No su binary found - device may not be rooted")
            Log.d(TAG, "=== Root check result: FALSE (no su binary) ===")
            return false
        }
        
        Log.d(TAG, "Method 2 - su binary found: $foundPath")
        
        // Method 3: Try to get root shell via libsu (non-blocking check)
        val hasRootShell = try {
            val shell = Shell.getShell()
            val isRoot = shell.isRoot
            if (isRoot) {
                Log.d(TAG, "✓ Root shell obtained successfully via libsu")
            } else {
                Log.w(TAG, "⚠ Root shell not available via libsu, but su exists")
            }
            isRoot
        } catch (e: Exception) {
            Log.w(TAG, "⚠ Cannot get root shell via libsu: ${e.message}")
            false
        }
        
        // If su exists, device is rooted (even if app doesn't have permission yet)
        val result = suExists
        if (result) {
            if (hasRootShell) {
                Log.d(TAG, "=== Root check result: TRUE (root shell available) ===")
            } else {
                Log.d(TAG, "=== Root check result: TRUE (device rooted, permission needed) ===")
            }
        }
        
        return result
    }

    /**
     * Request root access
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request root: ${e.message}")
            false
        }
    }

    /**
     * Get root shell
     */
    private fun getShell(): Shell {
        return Shell.getShell()
    }

    // ==================== Command Execution ====================

    /**
     * Execute shell command with root
     */
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) {
                return@withContext CommandResult(
                    success = false,
                    output = "",
                    error = "Root not available"
                )
            }
            
            Log.d(TAG, "Executing: $command")
            
            val result = Shell.cmd(command).exec()
            
            CommandResult(
                success = result.isSuccess,
                output = result.out.joinToString("\n"),
                error = result.err.joinToString("\n"),
                exitCode = result.code
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: ${e.message}")
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Execute multiple commands
     */
    suspend fun executeCommands(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        commands.map { executeCommand(it) }
    }

    /**
     * Execute command and get output as string
     */
    suspend fun executeForOutput(command: String): String? {
        val result = executeCommand(command)
        return if (result.success) result.output else null
    }

    /**
     * Execute command and check success
     */
    suspend fun executeForSuccess(command: String): Boolean {
        return executeCommand(command).success
    }

    // ==================== Settings Commands ====================

    /**
     * Get system setting
     */
    suspend fun getSetting(namespace: String, key: String): String? {
        val result = executeCommand("settings get $namespace $key")
        return if (result.success && result.output.isNotEmpty() && result.output != "null") {
            result.output.trim()
        } else {
            null
        }
    }

    /**
     * Set system setting
     */
    suspend fun setSetting(namespace: String, key: String, value: String): Boolean {
        return executeForSuccess("settings put $namespace $key $value")
    }

    /**
     * Delete system setting
     */
    suspend fun deleteSetting(namespace: String, key: String): Boolean {
        return executeForSuccess("settings delete $namespace $key")
    }

    /**
     * Get secure setting
     */
    suspend fun getSecureSetting(key: String): String? = getSetting("secure", key)

    /**
     * Set secure setting
     */
    suspend fun setSecureSetting(key: String, value: String): Boolean = setSetting("secure", key, value)

    /**
     * Get global setting
     */
    suspend fun getGlobalSetting(key: String): String? = getSetting("global", key)

    /**
     * Set global setting
     */
    suspend fun setGlobalSetting(key: String, value: String): Boolean = setSetting("global", key, value)

    /**
     * Get system setting
     */
    suspend fun getSystemSetting(key: String): String? = getSetting("system", key)

    /**
     * Set system setting
     */
    suspend fun setSystemSetting(key: String, value: String): Boolean = setSetting("system", key, value)

    // ==================== System Properties ====================

    /**
     * Get system property
     */
    suspend fun getProperty(key: String): String? {
        val result = executeCommand("getprop $key")
        return if (result.success && result.output.isNotEmpty()) {
            result.output.trim()
        } else {
            null
        }
    }

    /**
     * Set system property (runtime only, resets on reboot)
     */
    suspend fun setProperty(key: String, value: String): Boolean {
        return executeForSuccess("setprop $key \"$value\"")
    }

    /**
     * Get all properties
     */
    suspend fun getAllProperties(): Map<String, String> {
        val result = executeCommand("getprop")
        if (!result.success) return emptyMap()
        
        val props = mutableMapOf<String, String>()
        val regex = Regex("""\[([^\]]+)\]:\s*\[([^\]]*)\]""")
        
        result.output.lines().forEach { line ->
            regex.find(line)?.let { match ->
                val (key, value) = match.destructured
                props[key] = value
            }
        }
        
        return props
    }

    // ==================== File Operations ====================

    /**
     * Read file with root
     */
    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) return@withContext null
            
            val file = SuFile.open(path)
            if (!file.exists()) return@withContext null
            
            SuFileInputStream.open(file).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file $path: ${e.message}")
            null
        }
    }

    /**
     * Write file with root
     */
    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) return@withContext false
            
            val file = SuFile.open(path)
            SuFileOutputStream.open(file).bufferedWriter().use { it.write(content) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file $path: ${e.message}")
            false
        }
    }

    /**
     * Append to file with root
     */
    suspend fun appendFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) return@withContext false
            
            val file = SuFile.open(path)
            SuFileOutputStream.open(file, true).bufferedWriter().use { it.write(content) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to file $path: ${e.message}")
            false
        }
    }

    /**
     * Delete file with root
     */
    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) return@withContext false
            
            val file = SuFile.open(path)
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file $path: ${e.message}")
            false
        }
    }

    /**
     * Delete directory recursively
     */
    suspend fun deleteDirectory(path: String): Boolean {
        return executeForSuccess("rm -rf \"$path\"")
    }

    /**
     * Check if file exists
     */
    suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isRootAvailable()) return@withContext false
            SuFile.open(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copy file
     */
    suspend fun copyFile(source: String, destination: String): Boolean {
        return executeForSuccess("cp \"$source\" \"$destination\"")
    }

    /**
     * Move file
     */
    suspend fun moveFile(source: String, destination: String): Boolean {
        return executeForSuccess("mv \"$source\" \"$destination\"")
    }

    /**
     * Change file permissions
     */
    suspend fun chmod(path: String, permissions: String): Boolean {
        return executeForSuccess("chmod $permissions \"$path\"")
    }

    /**
     * Change file owner
     */
    suspend fun chown(path: String, owner: String, group: String = owner): Boolean {
        return executeForSuccess("chown $owner:$group \"$path\"")
    }

    // ==================== Package Management ====================

    /**
     * Clear app data
     */
    suspend fun clearAppData(packageName: String): Boolean {
        return executeForSuccess("pm clear $packageName")
    }

    /**
     * Force stop app
     */
    suspend fun forceStopApp(packageName: String): Boolean {
        return executeForSuccess("am force-stop $packageName")
    }

    /**
     * Kill app process
     */
    suspend fun killApp(packageName: String): Boolean {
        return executeForSuccess("am kill $packageName")
    }

    /**
     * Get app data directory
     */
    suspend fun getAppDataDir(packageName: String): String? {
        val result = executeCommand("pm path $packageName")
        if (!result.success) return null
        
        // Extract base path from APK path
        val apkPath = result.output.trim().removePrefix("package:")
        return apkPath.substringBefore("/base.apk").replace("/app/", "/data/data/")
    }

    /**
     * Check if app is installed
     */
    suspend fun isAppInstalled(packageName: String): Boolean {
        val result = executeCommand("pm list packages $packageName")
        return result.success && result.output.contains(packageName)
    }

    /**
     * Get app version
     */
    suspend fun getAppVersion(packageName: String): String? {
        val result = executeCommand("dumpsys package $packageName | grep versionName")
        if (!result.success) return null
        
        val regex = Regex("""versionName=([^\s]+)""")
        return regex.find(result.output)?.groupValues?.get(1)
    }

    // ==================== System Operations ====================

    /**
     * Remount system as read-write
     */
    suspend fun remountSystemRW(): Boolean {
        // Try multiple methods for different Android versions
        val methods = listOf(
            "mount -o rw,remount /system",
            "mount -o rw,remount /",
            "mount -o rw,remount /system_root",
            "blockdev --setrw /dev/block/dm-0 2>/dev/null; mount -o rw,remount /",
            "magisk --denylist rm com.automation.agent 2>/dev/null; mount -o rw,remount /"
        )
        
        for (method in methods) {
            if (executeForSuccess(method)) {
                Log.i(TAG, "System remounted RW with: $method")
                return true
            }
        }
        
        Log.w(TAG, "Could not remount system as RW - using setprop fallback")
        return false
    }

    /**
     * Remount system as read-only
     */
    suspend fun remountSystemRO(): Boolean {
        // Try multiple methods
        executeCommand("mount -o ro,remount / 2>/dev/null")
        executeCommand("mount -o ro,remount /system 2>/dev/null")
        return true
    }

    /**
     * Reboot device
     */
    suspend fun reboot(): Boolean {
        return executeForSuccess("reboot")
    }

    /**
     * Soft reboot (restart runtime)
     */
    suspend fun softReboot(): Boolean {
        return executeForSuccess("setprop ctl.restart zygote")
    }

    /**
     * Get device serial number
     */
    suspend fun getSerialNumber(): String? {
        return getProperty("ro.serialno") ?: getProperty("ro.boot.serialno")
    }

    /**
     * Get Android ID
     */
    suspend fun getAndroidId(): String? {
        return getSecureSetting("android_id")
    }

    // ==================== build.prop Operations ====================

    /**
     * Read build.prop
     */
    suspend fun readBuildProp(): Map<String, String> {
        val content = readFile(BUILD_PROP_PATH) ?: return emptyMap()
        
        val props = mutableMapOf<String, String>()
        content.lines().forEach { line ->
            if (line.isNotBlank() && !line.startsWith("#") && line.contains("=")) {
                val (key, value) = line.split("=", limit = 2)
                props[key.trim()] = value.trim()
            }
        }
        
        return props
    }

    /**
     * Modify build.prop
     */
    suspend fun modifyBuildProp(modifications: Map<String, String>): Boolean {
        var success = false
        
        // Method 1: Try setprop (works on most rooted devices, runtime only)
        Log.i(TAG, "Trying setprop method for ${modifications.size} properties")
        var setpropSuccess = 0
        for ((key, value) in modifications) {
            if (setProperty(key, value)) {
                setpropSuccess++
            }
        }
        if (setpropSuccess > 0) {
            Log.i(TAG, "setprop set $setpropSuccess/${modifications.size} properties")
            success = true
        }
        
        // Method 2: Try resetprop (Magisk feature, persistent)
        Log.i(TAG, "Trying resetprop method (Magisk)")
        var resetpropSuccess = 0
        for ((key, value) in modifications) {
            val result = executeCommand("resetprop -n $key '$value' 2>/dev/null")
            if (result.success) resetpropSuccess++
        }
        if (resetpropSuccess > 0) {
            Log.i(TAG, "resetprop set $resetpropSuccess/${modifications.size} properties")
            success = true
        }
        
        // Method 3: Try modifying build.prop file (requires remount)
        if (remountSystemRW()) {
            try {
                val content = readFile(BUILD_PROP_PATH)
                if (content != null) {
                    val lines = content.lines().toMutableList()
                    
                    for ((key, value) in modifications) {
                        var found = false
                        for (i in lines.indices) {
                            if (lines[i].startsWith("$key=")) {
                                lines[i] = "$key=$value"
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            lines.add("$key=$value")
                        }
                    }
                    
                    val newContent = lines.joinToString("\n")
                    if (writeFile(BUILD_PROP_PATH, newContent)) {
                        chmod(BUILD_PROP_PATH, "644")
                        Log.i(TAG, "build.prop modified successfully")
                        success = true
                    }
                }
            } finally {
                remountSystemRO()
            }
        }
        
        Log.i(TAG, "modifyBuildProp result: $success")
        return success
    }

    /**
     * Backup build.prop
     */
    suspend fun backupBuildProp(): Boolean {
        return copyFile(BUILD_PROP_PATH, "$BUILD_PROP_PATH.bak")
    }

    /**
     * Restore build.prop from backup
     */
    suspend fun restoreBuildProp(): Boolean {
        if (!remountSystemRW()) return false
        
        try {
            val result = copyFile("$BUILD_PROP_PATH.bak", BUILD_PROP_PATH)
            chmod(BUILD_PROP_PATH, "644")
            return result
        } finally {
            remountSystemRO()
        }
    }

    // ==================== Data Classes ====================

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String = "",
        val exitCode: Int = if (success) 0 else 1
    )
}
