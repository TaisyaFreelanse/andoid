package com.automation.agent.automation

import com.automation.agent.browser.BrowserController

/**
 * Navigator - Handles navigation operations
 * 
 * Operations:
 * - navigate(url) - Navigate to URL
 * - wait(duration) - Wait for specified duration
 * - click(selector) - Click element by selector
 */
class Navigator(private val browser: BrowserController) {

    suspend fun navigate(url: String) {
        browser.navigate(url)
    }

    suspend fun wait(duration: Long) {
        browser.wait(duration)
    }

    suspend fun click(selector: String) {
        browser.click(selector)
    }

    suspend fun scroll(direction: String, pixels: Int) {
        browser.scroll(direction, pixels)
    }
}

