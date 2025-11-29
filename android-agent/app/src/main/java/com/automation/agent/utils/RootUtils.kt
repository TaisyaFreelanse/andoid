package com.automation.agent.utils

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile

/**
 * RootUtils - Utility for root operations
 * 
 * Uses libsu library for safe root access.
 * All operations require root privileges.
 */
class RootUtils {

    private val shell: Shell = Shell.getShell()

    /**
     * Check if device has root access
     */
    fun isRootAvailable(): Boolean {
        return Shell.isAppGrantedRoot()
    }

    /**
     * Execute shell command with root
     */
    fun executeRootCommand(command: String): String? {
        if (!isRootAvailable()) {
            return null
        }

        val result = shell.newJob().add(command).exec()
        return if (result.isSuccess) {
            result.out.joinToString("\n")
        } else {
            null
        }
    }

    /**
     * Execute multiple commands
     */
    fun executeRootCommands(commands: List<String>): List<String?> {
        if (!isRootAvailable()) {
            return commands.map { null }
        }

        val job = shell.newJob()
        commands.forEach { job.add(it) }
        val result = job.exec()

        return if (result.isSuccess) {
            result.out
        } else {
            commands.map { null }
        }
    }

    /**
     * Read file with root
     */
    fun readFile(path: String): String? {
        if (!isRootAvailable()) {
            return null
        }

        val file = SuFile.open(path)
        return file.readText()
    }

    /**
     * Write file with root
     */
    fun writeFile(path: String, content: String): Boolean {
        if (!isRootAvailable()) {
            return false
        }

        val file = SuFile.open(path)
        return try {
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if file exists (with root)
     */
    fun fileExists(path: String): Boolean {
        if (!isRootAvailable()) {
            return false
        }

        val file = SuFile.open(path)
        return file.exists()
    }
}

