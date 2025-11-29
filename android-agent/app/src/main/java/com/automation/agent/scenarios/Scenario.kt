package com.automation.agent.scenarios

import com.google.gson.annotations.SerializedName

/**
 * Scenario - Main scenario model
 * 
 * Represents a complete automation scenario with:
 * - Metadata (id, name, description)
 * - Configuration (browser, proxy, timeout)
 * - Steps or actions to execute
 * - Post-processing options
 * - Output configuration
 */
data class Scenario(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "1.0",
    val type: String = "", // parsing, surfing, automation, uniqueness
    
    // Browser configuration
    val browser: String = "webview", // webview, chrome
    val proxy: String = "auto", // auto, none, or specific proxy
    
    // Timing
    val timeout: Long = 60000,
    val retries: Int = 2,
    
    // Root requirement (for uniqueness scenarios)
    @SerializedName("requires_root")
    val requiresRoot: Boolean = false,
    
    // Configuration object
    val config: ScenarioConfig = ScenarioConfig(),
    
    // Variables for substitution
    val variables: Map<String, String> = emptyMap(),
    
    // Steps (for parsing, surfing, automation)
    val steps: List<Step> = emptyList(),
    
    // Actions (for uniqueness)
    val actions: List<Action> = emptyList(),
    
    // Post-processing
    @SerializedName("post_process")
    val postProcess: PostProcess = PostProcess(),
    
    // Output configuration
    val output: OutputConfig = OutputConfig()
)

/**
 * Scenario configuration
 */
data class ScenarioConfig(
    // Parsing config
    @SerializedName("wait_for_ads")
    val waitForAds: Boolean = false,
    @SerializedName("scroll_before_extract")
    val scrollBeforeExtract: Boolean = false,
    @SerializedName("take_screenshots")
    val takeScreenshots: Boolean = true,
    
    // Surfing config
    @SerializedName("human_like")
    val humanLike: Boolean = false,
    @SerializedName("random_delays")
    val randomDelays: Boolean = false,
    @SerializedName("min_delay")
    val minDelay: Long = 1000,
    @SerializedName("max_delay")
    val maxDelay: Long = 5000,
    
    // Multi-page config
    @SerializedName("max_pages")
    val maxPages: Int = 10,
    @SerializedName("delay_between_pages")
    val delayBetweenPages: Long = 2000,
    @SerializedName("stop_on_empty")
    val stopOnEmpty: Boolean = true,
    
    // Form config
    @SerializedName("human_like_typing")
    val humanLikeTyping: Boolean = false,
    @SerializedName("typing_delay_ms")
    val typingDelayMs: Long = 50,
    @SerializedName("validate_before_submit")
    val validateBeforeSubmit: Boolean = false,
    
    // Uniqueness config
    @SerializedName("backup_before")
    val backupBefore: Boolean = false,
    @SerializedName("reboot_after")
    val rebootAfter: Boolean = false,
    @SerializedName("verify_changes")
    val verifyChanges: Boolean = true,
    @SerializedName("auto_detect_country")
    val autoDetectCountry: Boolean = false,
    @SerializedName("use_proxy_geolocation")
    val useProxyGeolocation: Boolean = false
)

/**
 * Step - Single step in scenario
 */
data class Step(
    val id: String = "",
    val type: String = "", // navigate, wait, click, input, scroll, extract, screenshot, loop, condition
    val description: String = "",
    
    // Navigation
    val url: String? = null,
    @SerializedName("wait_for_load")
    val waitForLoad: Boolean = true,
    
    // Timing
    val timeout: Long? = null,
    val duration: Long? = null,
    @SerializedName("random_offset")
    val randomOffset: Long? = null,
    val condition: String? = null, // page_loaded, element_visible, etc.
    
    // Element interaction
    val selector: String? = null,
    val value: String? = null,
    @SerializedName("clear_before")
    val clearBefore: Boolean = false,
    val optional: Boolean = false,
    
    // Scroll
    val direction: String? = null, // up, down
    val pixels: Int? = null,
    val percent: Int? = null,
    val smooth: Boolean = false,
    
    // Extract
    val attribute: String? = null,
    @SerializedName("save_as")
    val saveAs: String? = null,
    val multiple: Boolean = false,
    val append: Boolean = false,
    @SerializedName("extract_fields")
    val extractFields: Map<String, String>? = null,
    
    // Screenshot
    @SerializedName("full_page")
    val fullPage: Boolean = false,
    
    // Loop
    @SerializedName("max_iterations")
    val maxIterations: Int? = null,
    val steps: List<Step>? = null,
    
    // Condition
    val check: String? = null, // element_exists, text_contains, etc.
    @SerializedName("on_false")
    val onFalse: String? = null // break, continue, skip
)

/**
 * Action - Single action for uniqueness scenarios
 */
data class Action(
    val id: String = "",
    val type: String = "", // regenerate_android_id, regenerate_aaid, clear_chrome_data, etc.
    val description: String = "",
    
    // User agent
    val ua: String? = null, // random or specific UA
    val locale: String? = null,
    
    // Timezone
    val timezone: String? = null, // auto, random, or specific timezone
    @SerializedName("country_code")
    val countryCode: String? = null,
    
    // Location
    val latitude: String? = null, // auto, random, or specific value
    val longitude: String? = null,
    
    // Build prop
    val params: Map<String, String>? = null,
    
    // Output
    @SerializedName("save_as")
    val saveAs: String? = null
)

/**
 * Post-processing configuration
 */
data class PostProcess(
    // Parsing post-process
    @SerializedName("extract_adurl")
    val extractAdurl: Boolean = false,
    @SerializedName("deduplicate_domains")
    val deduplicateDomains: Boolean = false,
    @SerializedName("check_semrush")
    val checkSemrush: Boolean = false,
    @SerializedName("save_to_backend")
    val saveToBackend: Boolean = false,
    @SerializedName("deduplicate_items")
    val deduplicateItems: Boolean = false,
    @SerializedName("validate_data")
    val validateData: Boolean = false,
    @SerializedName("export_format")
    val exportFormat: String = "json",
    
    // Surfing post-process
    @SerializedName("log_visited_urls")
    val logVisitedUrls: Boolean = false,
    @SerializedName("track_time_on_page")
    val trackTimeOnPage: Boolean = false,
    
    // Form post-process
    @SerializedName("check_submission_success")
    val checkSubmissionSuccess: Boolean = false,
    @SerializedName("log_response")
    val logResponse: Boolean = false,
    
    // Uniqueness post-process
    @SerializedName("verify_android_id_changed")
    val verifyAndroidIdChanged: Boolean = false,
    @SerializedName("verify_user_agent_changed")
    val verifyUserAgentChanged: Boolean = false,
    @SerializedName("verify_timezone_matches_proxy")
    val verifyTimezoneMatchesProxy: Boolean = false,
    @SerializedName("verify_location_matches_proxy")
    val verifyLocationMatchesProxy: Boolean = false,
    @SerializedName("log_new_fingerprint")
    val logNewFingerprint: Boolean = false,
    @SerializedName("send_to_backend")
    val sendToBackend: Boolean = false
)

/**
 * Output configuration
 */
data class OutputConfig(
    val format: String = "json", // json, csv
    @SerializedName("include_screenshots")
    val includeScreenshots: Boolean = true,
    @SerializedName("include_metadata")
    val includeMetadata: Boolean = true
)

