package com.automation.agent.automation

import com.automation.agent.browser.BrowserController
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parser - Extracts data from web pages
 * 
 * Operations:
 * - Extract by CSS/XPath selectors
 * - Parse adurl from links (regex: &adurl=([^&]+))
 * - Deduplicate domains
 */
class Parser(private val browser: BrowserController) {

    /**
     * Extract data by CSS selector
     */
    suspend fun extractByCss(selector: String, attribute: String? = null): List<String> {
        val pageSource = browser.getPageSource()
        val doc: Document = Jsoup.parse(pageSource)
        
        return if (attribute != null) {
            doc.select(selector).map { it.attr(attribute) }
        } else {
            doc.select(selector).map { it.text() }
        }
    }

    /**
     * Extract data by XPath
     */
    suspend fun extractByXPath(xpath: String): List<String> {
        // TODO: Implement XPath extraction
        // Note: Jsoup doesn't support XPath, need alternative library
        return emptyList()
    }

    /**
     * Parse adurl from links
     * Regex pattern: &adurl=([^&]+) or adurl=([^&]+)
     */
    fun parseAdUrl(link: String): String? {
        val patterns = listOf(
            Regex("&adurl=([^&]+)"),
            Regex("adurl=([^&]+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(link)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }

    /**
     * Extract all adurls from page
     */
    suspend fun extractAdUrls(): List<String> {
        val links = extractByCss("a[href]", "href")
        return links.mapNotNull { parseAdUrl(it) }
    }

    /**
     * Extract domain from URL
     */
    fun extractDomain(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            uri.host
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deduplicate domains
     */
    fun deduplicateDomains(urls: List<String>): List<String> {
        val domains = urls.mapNotNull { extractDomain(it) }.toSet()
        return domains.toList()
    }
}

