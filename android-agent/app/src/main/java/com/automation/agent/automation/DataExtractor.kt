package com.automation.agent.automation

import android.util.Log
import com.automation.agent.browser.BrowserController
import com.automation.agent.network.ApiClient

/**
 * DataExtractor - High-level data extraction and processing
 * 
 * Combines Parser functionality with business logic:
 * - Extract advertising data
 * - Process and deduplicate results
 * - Prepare data for backend submission
 * - Handle extraction workflows
 */
class DataExtractor(
    private val browser: BrowserController,
    private val apiClient: ApiClient? = null
) {

    companion object {
        private const val TAG = "DataExtractor"
    }

    private val parser = Parser(browser)
    private val extractedData = mutableMapOf<String, Any>()
    private val extractedDomains = mutableSetOf<String>()
    private val extractedAdUrls = mutableListOf<AdUrlData>()

    // ==================== Ad URL Extraction ====================

    /**
     * Extract all advertising URLs from current page
     */
    suspend fun extractAdvertisingData(): ExtractionResult {
        Log.i(TAG, "Starting advertising data extraction")
        
        val startTime = System.currentTimeMillis()
        
        // Get all ad links
        val adLinks = parser.extractAdLinks()
        
        // Process each ad link
        for (link in adLinks) {
            if (link.destinationUrl.isNotEmpty()) {
                val adData = AdUrlData(
                    originalUrl = link.originalUrl,
                    destinationUrl = link.destinationUrl,
                    domain = link.domain ?: parser.extractDomain(link.destinationUrl),
                    rootDomain = parser.extractRootDomain(link.destinationUrl),
                    linkText = link.text,
                    pageUrl = browser.getCurrentUrl(),
                    timestamp = System.currentTimeMillis()
                )
                
                // Add to list if not duplicate
                if (!isDuplicateAdUrl(adData)) {
                    extractedAdUrls.add(adData)
                    link.domain?.let { extractedDomains.add(it) }
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        Log.i(TAG, "Extracted ${extractedAdUrls.size} ad URLs, ${extractedDomains.size} unique domains in ${duration}ms")
        
        return ExtractionResult(
            adUrls = extractedAdUrls.toList(),
            domains = extractedDomains.toList(),
            totalLinks = adLinks.size,
            duration = duration
        )
    }

    /**
     * Check if ad URL is duplicate
     */
    private fun isDuplicateAdUrl(data: AdUrlData): Boolean {
        return extractedAdUrls.any { 
            it.destinationUrl == data.destinationUrl || 
            (it.domain == data.domain && it.linkText == data.linkText)
        }
    }

    // ==================== Content Extraction ====================

    /**
     * Extract structured content from page
     */
    suspend fun extractPageContent(): PageContent {
        val meta = parser.extractMeta()
        val links = parser.extractAllLinks()
        val images = parser.extractImages()
        val text = parser.extractVisibleText()
        
        return PageContent(
            url = browser.getCurrentUrl(),
            title = meta.title,
            description = meta.description,
            keywords = meta.keywords,
            text = text,
            linkCount = links.size,
            imageCount = images.size,
            meta = meta
        )
    }

    /**
     * Extract data by custom selectors
     */
    suspend fun extractBySelectors(selectors: Map<String, SelectorConfig>): Map<String, List<String>> {
        val results = mutableMapOf<String, List<String>>()
        
        for ((name, config) in selectors) {
            val values = when (config.type) {
                SelectorType.CSS -> parser.extractByCss(config.selector, config.attribute)
                SelectorType.XPATH -> parser.extractByXPath(config.selector)
                SelectorType.REGEX -> parser.extractByPattern(config.selector)
            }
            
            results[name] = if (config.deduplicate) values.distinct() else values
            
            // Store in extracted data
            extractedData[name] = values
        }
        
        return results
    }

    // ==================== Domain Processing ====================

    /**
     * Get all extracted domains
     */
    fun getExtractedDomains(): List<String> {
        return extractedDomains.toList().sorted()
    }

    /**
     * Get unique root domains
     */
    fun getUniqueRootDomains(): List<String> {
        return extractedAdUrls
            .mapNotNull { it.rootDomain }
            .distinct()
            .sorted()
    }

    /**
     * Check if domain was already extracted
     */
    fun isDomainExtracted(domain: String): Boolean {
        return extractedDomains.contains(domain) ||
               extractedDomains.contains(domain.removePrefix("www."))
    }

    /**
     * Add domain to extracted set
     */
    fun addExtractedDomain(domain: String) {
        extractedDomains.add(domain.removePrefix("www."))
    }

    // ==================== Data Submission ====================

    /**
     * Submit extracted data to backend
     */
    suspend fun submitToBackend(
        deviceId: String,
        taskId: String?
    ): Boolean {
        val client = apiClient ?: return false
        
        var success = true
        
        for (adUrl in extractedAdUrls) {
            try {
                val request = ApiClient.ParsedDataRequest(
                    deviceId = deviceId,
                    taskId = taskId,
                    url = adUrl.pageUrl,
                    adUrl = adUrl.destinationUrl,
                    adDomain = adUrl.domain,
                    screenshotPath = null,
                    extractedData = mapOf(
                        "originalUrl" to adUrl.originalUrl,
                        "linkText" to adUrl.linkText,
                        "rootDomain" to (adUrl.rootDomain ?: "")
                    )
                )
                
                val result = client.sendParsedData(request)
                if (!result) {
                    success = false
                    Log.w(TAG, "Failed to submit ad URL: ${adUrl.destinationUrl}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting data: ${e.message}")
                success = false
            }
        }
        
        return success
    }

    // ==================== State Management ====================

    /**
     * Clear all extracted data
     */
    fun clearData() {
        extractedData.clear()
        extractedDomains.clear()
        extractedAdUrls.clear()
        parser.clearCache()
    }

    /**
     * Get extraction statistics
     */
    fun getStatistics(): ExtractionStatistics {
        return ExtractionStatistics(
            totalAdUrls = extractedAdUrls.size,
            uniqueDomains = extractedDomains.size,
            uniqueRootDomains = getUniqueRootDomains().size,
            dataFields = extractedData.size
        )
    }

    /**
     * Export all data as map
     */
    fun exportData(): Map<String, Any> {
        return mapOf(
            "adUrls" to extractedAdUrls.map { it.toMap() },
            "domains" to extractedDomains.toList(),
            "rootDomains" to getUniqueRootDomains(),
            "customData" to extractedData,
            "statistics" to getStatistics()
        )
    }

    // ==================== Data Classes ====================

    data class AdUrlData(
        val originalUrl: String,
        val destinationUrl: String,
        val domain: String?,
        val rootDomain: String?,
        val linkText: String,
        val pageUrl: String,
        val timestamp: Long
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "originalUrl" to originalUrl,
            "destinationUrl" to destinationUrl,
            "domain" to domain,
            "rootDomain" to rootDomain,
            "linkText" to linkText,
            "pageUrl" to pageUrl,
            "timestamp" to timestamp
        )
    }

    data class ExtractionResult(
        val adUrls: List<AdUrlData>,
        val domains: List<String>,
        val totalLinks: Int,
        val duration: Long
    )

    data class PageContent(
        val url: String,
        val title: String,
        val description: String,
        val keywords: String,
        val text: String,
        val linkCount: Int,
        val imageCount: Int,
        val meta: Parser.PageMeta
    )

    data class SelectorConfig(
        val selector: String,
        val type: SelectorType = SelectorType.CSS,
        val attribute: String? = null,
        val deduplicate: Boolean = true
    )

    enum class SelectorType {
        CSS,
        XPATH,
        REGEX
    }

    data class ExtractionStatistics(
        val totalAdUrls: Int,
        val uniqueDomains: Int,
        val uniqueRootDomains: Int,
        val dataFields: Int
    )
}

