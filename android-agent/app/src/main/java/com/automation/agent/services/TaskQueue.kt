package com.automation.agent.services

import android.util.Log
import com.automation.agent.network.ApiClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TaskQueue - Manages task queue with priority support
 * 
 * Features:
 * - Priority-based task ordering
 * - Concurrent access support
 * - Task deduplication
 * - Task status tracking
 */
class TaskQueue {

    companion object {
        private const val TAG = "TaskQueue"
    }

    private val queue = ConcurrentLinkedQueue<QueuedTask>()
    private val processedTaskIds = mutableSetOf<String>()
    private val isProcessing = AtomicBoolean(false)

    /**
     * Add task to queue
     */
    fun enqueue(task: ApiClient.TaskResponse): Boolean {
        // Skip if already processed or in queue
        if (processedTaskIds.contains(task.id)) {
            Log.d(TAG, "Task ${task.id} already processed, skipping")
            return false
        }
        
        if (queue.any { it.task.id == task.id }) {
            Log.d(TAG, "Task ${task.id} already in queue, skipping")
            return false
        }
        
        val queuedTask = QueuedTask(
            task = task,
            priority = task.priority,
            addedAt = System.currentTimeMillis()
        )
        
        queue.add(queuedTask)
        Log.i(TAG, "Task ${task.id} added to queue (priority: ${task.priority})")
        
        return true
    }

    /**
     * Add multiple tasks to queue
     */
    fun enqueueAll(tasks: List<ApiClient.TaskResponse>): Int {
        var added = 0
        tasks.forEach { task ->
            if (enqueue(task)) {
                added++
            }
        }
        return added
    }

    /**
     * Get next task (highest priority first)
     */
    fun dequeue(): ApiClient.TaskResponse? {
        if (queue.isEmpty()) {
            return null
        }
        
        // Sort by priority (descending) and get first
        val sorted = queue.sortedByDescending { it.priority }
        val next = sorted.firstOrNull() ?: return null
        
        queue.remove(next)
        processedTaskIds.add(next.task.id)
        
        Log.i(TAG, "Dequeued task ${next.task.id} (priority: ${next.priority})")
        
        return next.task
    }

    /**
     * Peek at next task without removing
     */
    fun peek(): ApiClient.TaskResponse? {
        if (queue.isEmpty()) {
            return null
        }
        
        return queue.sortedByDescending { it.priority }.firstOrNull()?.task
    }

    /**
     * Check if queue is empty
     */
    fun isEmpty(): Boolean = queue.isEmpty()

    /**
     * Get queue size
     */
    fun size(): Int = queue.size

    /**
     * Clear queue
     */
    fun clear() {
        queue.clear()
        Log.i(TAG, "Queue cleared")
    }

    /**
     * Clear processed task history
     */
    fun clearHistory() {
        processedTaskIds.clear()
        Log.i(TAG, "Task history cleared")
    }

    /**
     * Remove specific task from queue
     */
    fun remove(taskId: String): Boolean {
        val task = queue.find { it.task.id == taskId }
        return if (task != null) {
            queue.remove(task)
            Log.i(TAG, "Task $taskId removed from queue")
            true
        } else {
            false
        }
    }

    /**
     * Check if task is in queue
     */
    fun contains(taskId: String): Boolean {
        return queue.any { it.task.id == taskId }
    }

    /**
     * Check if task was already processed
     */
    fun wasProcessed(taskId: String): Boolean {
        return processedTaskIds.contains(taskId)
    }

    /**
     * Get all queued tasks
     */
    fun getAll(): List<ApiClient.TaskResponse> {
        return queue.sortedByDescending { it.priority }.map { it.task }
    }

    /**
     * Set processing state
     */
    fun setProcessing(processing: Boolean) {
        isProcessing.set(processing)
    }

    /**
     * Check if currently processing
     */
    fun isProcessing(): Boolean = isProcessing.get()

    /**
     * Internal queued task wrapper
     */
    private data class QueuedTask(
        val task: ApiClient.TaskResponse,
        val priority: Int,
        val addedAt: Long
    )
}

