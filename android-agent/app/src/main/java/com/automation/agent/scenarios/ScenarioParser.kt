package com.automation.agent.scenarios

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ScenarioParser - Parses and validates scenario files (JSON/YAML)
 * 
 * Supports:
 * - JSON scenario files
 * - Variable substitution ({{variable}})
 * - Step validation
 * - Scenario loading from assets
 */
class ScenarioParser(private val context: Context) {

    companion object {
        private const val TAG = "ScenarioParser"
        private const val SCENARIOS_PATH = "scenarios"
    }

    private val gson = Gson()

    // ==================== Loading ====================

    /**
     * Load scenario from assets by ID
     */
    fun loadScenario(scenarioId: String): Scenario? {
        return try {
            val filename = "$scenarioId.json"
            val json = loadAssetFile("$SCENARIOS_PATH/$filename")
            parseScenario(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scenario $scenarioId: ${e.message}")
            null
        }
    }

    /**
     * Load scenario from JSON string
     */
    fun parseScenario(json: String): Scenario? {
        return try {
            gson.fromJson(json, Scenario::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse scenario: ${e.message}")
            null
        }
    }

    /**
     * Load all available scenarios
     */
    fun loadAllScenarios(): List<Scenario> {
        val scenarios = mutableListOf<Scenario>()
        
        try {
            val files = context.assets.list(SCENARIOS_PATH) ?: return emptyList()
            
            for (file in files) {
                if (file.endsWith(".json")) {
                    val json = loadAssetFile("$SCENARIOS_PATH/$file")
                    parseScenario(json)?.let { scenarios.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scenarios: ${e.message}")
        }
        
        return scenarios
    }

    /**
     * List available scenario IDs
     */
    fun listScenarioIds(): List<String> {
        return try {
            context.assets.list(SCENARIOS_PATH)
                ?.filter { it.endsWith(".json") }
                ?.map { it.removeSuffix(".json") }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list scenarios: ${e.message}")
            emptyList()
        }
    }

    // ==================== Variable Substitution ====================

    /**
     * Apply variables to scenario
     */
    fun applyVariables(scenario: Scenario, variables: Map<String, String>): Scenario {
        val json = gson.toJson(scenario)
        var processedJson = json
        
        // Merge scenario variables with provided variables
        val allVariables = scenario.variables.toMutableMap()
        allVariables.putAll(variables)
        
        // Replace {{variable}} patterns
        for ((key, value) in allVariables) {
            processedJson = processedJson.replace("{{$key}}", value)
        }
        
        return gson.fromJson(processedJson, Scenario::class.java)
    }

    /**
     * Extract required variables from scenario
     */
    fun extractRequiredVariables(scenario: Scenario): List<String> {
        val json = gson.toJson(scenario)
        val pattern = Regex("\\{\\{(\\w+)\\}\\}")
        
        return pattern.findAll(json)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it !in scenario.variables.keys }
            .toList()
    }

    // ==================== Validation ====================

    /**
     * Validate scenario structure
     */
    fun validateScenario(scenario: Scenario): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check required fields
        if (scenario.id.isBlank()) {
            errors.add("Scenario ID is required")
        }
        
        if (scenario.name.isBlank()) {
            errors.add("Scenario name is required")
        }
        
        if (scenario.type.isBlank()) {
            errors.add("Scenario type is required")
        }
        
        // Validate steps or actions
        when (scenario.type) {
            "parsing", "surfing", "automation" -> {
                if (scenario.steps.isEmpty()) {
                    errors.add("Scenario must have at least one step")
                }
                
                scenario.steps.forEachIndexed { index, step ->
                    validateStep(step, index, errors, warnings)
                }
            }
            "uniqueness" -> {
                if (scenario.actions.isEmpty()) {
                    errors.add("Uniqueness scenario must have at least one action")
                }
                
                if (scenario.requiresRoot && !scenario.requiresRoot) {
                    warnings.add("Uniqueness scenario should set requires_root: true")
                }
                
                scenario.actions.forEachIndexed { index, action ->
                    validateAction(action, index, errors, warnings)
                }
            }
            else -> {
                warnings.add("Unknown scenario type: ${scenario.type}")
            }
        }
        
        // Check for unresolved variables
        val requiredVars = extractRequiredVariables(scenario)
        if (requiredVars.isNotEmpty()) {
            warnings.add("Scenario has unresolved variables: ${requiredVars.joinToString()}")
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Validate individual step
     */
    private fun validateStep(
        step: Step,
        index: Int,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val stepId = step.id.ifBlank { "step_$index" }
        
        if (step.type.isBlank()) {
            errors.add("Step $stepId: type is required")
            return
        }
        
        when (step.type) {
            "navigate" -> {
                if (step.url.isNullOrBlank()) {
                    errors.add("Step $stepId: navigate requires 'url'")
                }
            }
            "wait" -> {
                if (step.duration == null || step.duration <= 0) {
                    warnings.add("Step $stepId: wait should have positive 'duration'")
                }
            }
            "click", "input", "extract" -> {
                if (step.selector.isNullOrBlank()) {
                    errors.add("Step $stepId: ${step.type} requires 'selector'")
                }
            }
            "input" -> {
                if (step.value.isNullOrBlank()) {
                    warnings.add("Step $stepId: input should have 'value'")
                }
            }
            "scroll" -> {
                if (step.direction.isNullOrBlank() && step.percent == null) {
                    warnings.add("Step $stepId: scroll should have 'direction' or 'percent'")
                }
            }
            "screenshot" -> {
                if (step.saveAs.isNullOrBlank()) {
                    warnings.add("Step $stepId: screenshot should have 'save_as'")
                }
            }
            "loop" -> {
                if (step.steps.isNullOrEmpty()) {
                    errors.add("Step $stepId: loop requires nested 'steps'")
                }
            }
            "condition" -> {
                if (step.check.isNullOrBlank()) {
                    errors.add("Step $stepId: condition requires 'check'")
                }
            }
        }
    }

    /**
     * Validate uniqueness action
     */
    private fun validateAction(
        action: Action,
        index: Int,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val actionId = action.id.ifBlank { "action_$index" }
        
        if (action.type.isBlank()) {
            errors.add("Action $actionId: type is required")
            return
        }
        
        val validActions = listOf(
            "regenerate_android_id",
            "regenerate_aaid",
            "clear_chrome_data",
            "clear_webview_data",
            "change_user_agent",
            "change_timezone",
            "change_location",
            "change_locale",
            "modify_build_prop",
            "detect_proxy_location"
        )
        
        if (action.type !in validActions) {
            warnings.add("Action $actionId: unknown action type '${action.type}'")
        }
    }

    // ==================== Helpers ====================

    /**
     * Load file from assets
     */
    private fun loadAssetFile(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * Convert scenario to JSON
     */
    fun toJson(scenario: Scenario): String {
        return gson.toJson(scenario)
    }

    // ==================== Data Classes ====================

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
}

