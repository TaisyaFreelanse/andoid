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
     * Check if device has root access
     */
    fun isRootAvailable(): Boolean {
        return Shell.isAppGrantedRoot() == true
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
        return executeForSuccess("mount -o rw,remount /system")
    }

    /**
     * Remount system as read-only
     */
    suspend fun remountSystemRO(): Boolean {
        return executeForSuccess("mount -o ro,remount /system")
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
        if (!remountSystemRW()) {
            Log.e(TAG, "Failed to remount system as RW")
            return false
        }
        
        try {
            val content = readFile(BUILD_PROP_PATH) ?: return false
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
            val result = writeFile(BUILD_PROP_PATH, newContent)
            
            // Set correct permissions
            chmod(BUILD_PROP_PATH, "644")
            
            return result
        } finally {
            remountSystemRO()
        }
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
