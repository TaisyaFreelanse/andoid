package com.automation.agent.automation

import android.util.Log
import com.automation.agent.browser.BrowserController
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI
import java.net.URLDecoder

/**
 * Parser - Extracts data from web pages
 * 
 * Operations:
 * - Extract by CSS selectors (Jsoup)
 * - Extract by XPath (via JavaScript)
 * - Parse adurl from advertising links
 * - Extract and deduplicate domains
 * - Extract structured data (tables, lists, forms)
 * - Extract meta information
 */
class Parser(private val browser: BrowserController) {

    companion object {
        private const val TAG = "Parser"
        
        // Common adurl patterns
        private val ADURL_PATTERNS = listOf(
            Regex("[?&]adurl=([^&]+)"),
            Regex("[?&]ad_url=([^&]+)"),
            Regex("[?&]dest_url=([^&]+)"),
            Regex("[?&]redirect=([^&]+)"),
            Regex("[?&]url=([^&]+)"),
            Regex("[?&]goto=([^&]+)"),
            Regex("[?&]target=([^&]+)")
        )
        
        // Google Ads specific patterns
        private val GOOGLE_ADS_PATTERNS = listOf(
            Regex("googleads\\.g\\.doubleclick\\.net.*[?&]adurl=([^&]+)"),
            Regex("www\\.googleadservices\\.com.*[?&]adurl=([^&]+)"),
            Regex("pagead2\\.googlesyndication\\.com.*[?&]adurl=([^&]+)")
        )
    }

    // Cache for parsed document
    private var cachedDocument: Document? = null
    private var cachedUrl: String? = null

    // ==================== CSS Selector Extraction ====================

    /**
     * Extract data by CSS selector
     * 
     * @param selector CSS selector
     * @param attribute Optional attribute to extract (null = text content)
     * @return List of extracted values
     */
    suspend fun extractByCss(selector: String, attribute: String? = null): List<String> {
        val doc = getDocument()
        
        return try {
            val elements = doc.select(selector)
            Log.d(TAG, "Found ${elements.size} elements for selector '$selector'")
            
            val results = when {
                // "text" or "textContent" means get text content, not HTML attribute
                attribute == null || attribute == "text" || attribute == "textContent" -> {
                    elements.map { it.text() }.filter { it.isNotEmpty() }
                }
                // "html" or "innerHTML" means get inner HTML
                attribute == "html" || attribute == "innerHTML" -> {
                    elements.map { it.html() }.filter { it.isNotEmpty() }
                }
                // "outerHtml" means get outer HTML
                attribute == "outerHtml" -> {
                    elements.map { it.outerHtml() }.filter { it.isNotEmpty() }
                }
                // Otherwise get the HTML attribute
                else -> {
                    elements.map { it.attr(attribute) }.filter { it.isNotEmpty() }
                }
            }
            
            Log.d(TAG, "Extracted ${results.size} values from selector '$selector' with attribute '$attribute'")
            results
        } catch (e: Exception) {
            Log.e(TAG, "CSS extraction failed for '$selector': ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract single value by CSS selector
     */
    suspend fun extractFirstByCss(selector: String, attribute: String? = null): String? {
        return extractByCss(selector, attribute).firstOrNull()
    }

    /**
     * Extract element HTML by CSS selector
     */
    suspend fun extractHtmlByCss(selector: String): List<String> {
        val doc = getDocument()
        
        return try {
            doc.select(selector).map { it.outerHtml() }
        } catch (e: Exception) {
            Log.e(TAG, "HTML extraction failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract inner HTML by CSS selector
     */
    suspend fun extractInnerHtmlByCss(selector: String): List<String> {
        val doc = getDocument()
        
        return try {
            doc.select(selector).map { it.html() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Count elements matching selector
     */
    suspend fun countElements(selector: String): Int {
        val doc = getDocument()
        return try {
            doc.select(selector).size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Check if element exists
     */
    suspend fun elementExists(selector: String): Boolean {
        return countElements(selector) > 0
    }

    // ==================== XPath Extraction ====================

    /**
     * Extract data by XPath (via JavaScript)
     * 
     * @param xpath XPath expression
     * @return List of extracted values
     */
    suspend fun extractByXPath(xpath: String): List<String> {
        val script = """
            (function() {
                var results = [];
                var query = document.evaluate('$xpath', document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                for (var i = 0; i < query.snapshotLength; i++) {
                    var node = query.snapshotItem(i);
                    results.push(node.textContent || node.nodeValue || '');
                }
                return JSON.stringify(results);
            })();
        """.trimIndent()
        
        val result = browser.evaluateJavascript(script) ?: "[]"
        
        return try {
            parseJsonArray(result)
        } catch (e: Exception) {
            Log.e(TAG, "XPath extraction failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract single value by XPath
     */
    suspend fun extractFirstByXPath(xpath: String): String? {
        return extractByXPath(xpath).firstOrNull()
    }

    // ==================== Ad URL Extraction ====================

    /**
     * Parse adurl from link
     * Supports multiple patterns for different ad networks
     * 
     * @param link URL to parse
     * @return Extracted destination URL or null
     */
    fun parseAdUrl(link: String): String? {
        // Try Google Ads patterns first
        for (pattern in GOOGLE_ADS_PATTERNS) {
            val match = pattern.find(link)
            if (match != null) {
                return decodeUrl(match.groupValues[1])
            }
        }
        
        // Try common adurl patterns
        for (pattern in ADURL_PATTERNS) {
            val match = pattern.find(link)
            if (match != null) {
                return decodeUrl(match.groupValues[1])
            }
        }
        
        return null
    }

    /**
     * Extract all adurls from current page
     */
    suspend fun extractAdUrls(): List<String> {
        val links = extractAllLinks()
        return links.mapNotNull { parseAdUrl(it) }.distinct()
    }

    /**
     * Extract all advertising links (links containing ad patterns)
     */
    suspend fun extractAdLinks(): List<AdLink> {
        val doc = getDocument()
        val adLinks = mutableListOf<AdLink>()
        
        val links = doc.select("a[href]")
        
        for (link in links) {
            val href = link.attr("href")
            val adUrl = parseAdUrl(href)
            
            if (adUrl != null) {
                adLinks.add(AdLink(
                    originalUrl = href,
                    destinationUrl = adUrl,
                    domain = extractDomain(adUrl),
                    text = link.text(),
                    title = link.attr("title"),
                    className = link.className()
                ))
            }
        }
        
        return adLinks
    }

    /**
     * Extract all links from page
     */
    suspend fun extractAllLinks(): List<String> {
        return extractByCss("a[href]", "href")
    }

    /**
     * Extract external links (different domain)
     */
    suspend fun extractExternalLinks(): List<String> {
        val currentDomain = extractDomain(browser.getCurrentUrl())
        val links = extractAllLinks()
        
        return links.filter { link ->
            val linkDomain = extractDomain(link)
            linkDomain != null && linkDomain != currentDomain
        }
    }

    // ==================== Domain Extraction ====================

    /**
     * Extract domain from URL
     */
    fun extractDomain(url: String): String? {
        return try {
            val cleanUrl = if (url.startsWith("http")) url else "http://$url"
            val uri = URI(cleanUrl)
            uri.host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract root domain (e.g., example.com from sub.example.com)
     */
    fun extractRootDomain(url: String): String? {
        val domain = extractDomain(url) ?: return null
        
        val parts = domain.split(".")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts.last()}"
        } else {
            domain
        }
    }

    /**
     * Deduplicate domains from URLs
     */
    fun deduplicateDomains(urls: List<String>): List<String> {
        return urls.mapNotNull { extractDomain(it) }
            .distinct()
            .sorted()
    }

    /**
     * Deduplicate root domains from URLs
     */
    fun deduplicateRootDomains(urls: List<String>): List<String> {
        return urls.mapNotNull { extractRootDomain(it) }
            .distinct()
            .sorted()
    }

    // ==================== Structured Data Extraction ====================

    /**
     * Extract table data
     */
    suspend fun extractTable(selector: String): List<List<String>> {
        val doc = getDocument()
        val table = doc.selectFirst(selector) ?: return emptyList()
        
        val rows = mutableListOf<List<String>>()
        
        for (row in table.select("tr")) {
            val cells = row.select("th, td").map { it.text() }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }
        
        return rows
    }

    /**
     * Extract list items
     */
    suspend fun extractList(selector: String): List<String> {
        val doc = getDocument()
        val list = doc.selectFirst(selector) ?: return emptyList()
        
        return list.select("li").map { it.text() }
    }

    /**
     * Extract form data
     */
    suspend fun extractForm(selector: String): FormData? {
        val doc = getDocument()
        val form = doc.selectFirst(selector) ?: return null
        
        val inputs = mutableMapOf<String, String>()
        
        for (input in form.select("input, textarea, select")) {
            val name = input.attr("name")
            val value = input.attr("value")
            val type = input.attr("type")
            
            if (name.isNotEmpty()) {
                inputs[name] = when (type) {
                    "checkbox", "radio" -> if (input.hasAttr("checked")) value else ""
                    else -> value
                }
            }
        }
        
        return FormData(
            action = form.attr("action"),
            method = form.attr("method").uppercase().ifEmpty { "GET" },
            inputs = inputs
        )
    }

    // ==================== Meta Data Extraction ====================

    /**
     * Extract page meta information
     */
    suspend fun extractMeta(): PageMeta {
        val doc = getDocument()
        
        return PageMeta(
            title = doc.title(),
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: "",
            keywords = doc.selectFirst("meta[name=keywords]")?.attr("content") ?: "",
            author = doc.selectFirst("meta[name=author]")?.attr("content") ?: "",
            ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "",
            ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: "",
            ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "",
            canonical = doc.selectFirst("link[rel=canonical]")?.attr("href") ?: ""
        )
    }

    /**
     * Extract all images
     */
    suspend fun extractImages(): List<ImageInfo> {
        val doc = getDocument()
        
        return doc.select("img").map { img ->
            ImageInfo(
                src = img.attr("src"),
                alt = img.attr("alt"),
                title = img.attr("title"),
                width = img.attr("width").toIntOrNull(),
                height = img.attr("height").toIntOrNull()
            )
        }
    }

    /**
     * Extract all scripts
     */
    suspend fun extractScripts(): List<String> {
        val doc = getDocument()
        return doc.select("script[src]").map { it.attr("src") }
    }

    /**
     * Extract all stylesheets
     */
    suspend fun extractStylesheets(): List<String> {
        val doc = getDocument()
        return doc.select("link[rel=stylesheet]").map { it.attr("href") }
    }

    // ==================== Text Extraction ====================

    /**
     * Extract visible text from page
     */
    suspend fun extractVisibleText(): String {
        val doc = getDocument()
        return doc.body()?.text() ?: ""
    }

    /**
     * Extract text matching pattern
     */
    suspend fun extractByPattern(pattern: String): List<String> {
        val text = extractVisibleText()
        val regex = Regex(pattern)
        return regex.findAll(text).map { it.value }.toList()
    }

    /**
     * Extract emails from page
     */
    suspend fun extractEmails(): List<String> {
        val emailPattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
        return extractByPattern(emailPattern).distinct()
    }

    /**
     * Extract phone numbers from page
     */
    suspend fun extractPhoneNumbers(): List<String> {
        val phonePattern = "\\+?[0-9][0-9\\s\\-\\(\\)]{8,}[0-9]"
        return extractByPattern(phonePattern).distinct()
    }

    // ==================== Helper Methods ====================

    /**
     * Get parsed document (with caching)
     */
    private suspend fun getDocument(): Document {
        val currentUrl = browser.getCurrentUrl()
        
        // Return cached document if URL hasn't changed
        if (cachedDocument != null && cachedUrl == currentUrl) {
            return cachedDocument!!
        }
        
        // Parse new document
        val pageSource = browser.getPageSource()
        cachedDocument = Jsoup.parse(pageSource)
        cachedUrl = currentUrl
        
        return cachedDocument!!
    }

    /**
     * Clear document cache
     */
    fun clearCache() {
        cachedDocument = null
        cachedUrl = null
    }

    /**
     * Decode URL-encoded string
     */
    private fun decodeUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Parse JSON array string
     */
    private fun parseJsonArray(json: String): List<String> {
        return json
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() && it != "null" }
    }

    // ==================== Data Classes ====================

    data class AdLink(
        val originalUrl: String,
        val destinationUrl: String,
        val domain: String?,
        val text: String,
        val title: String,
        val className: String
    )

    data class FormData(
        val action: String,
        val method: String,
        val inputs: Map<String, String>
    )

    data class PageMeta(
        val title: String,
        val description: String,
        val keywords: String,
        val author: String,
        val ogTitle: String,
        val ogDescription: String,
        val ogImage: String,
        val canonical: String
    )

    data class ImageInfo(
        val src: String,
        val alt: String,
        val title: String,
        val width: Int?,
        val height: Int?
    )

    /**
     * Extraction result with metadata
     */
    data class ExtractionResult(
        val selector: String,
        val values: List<String>,
        val count: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
}
