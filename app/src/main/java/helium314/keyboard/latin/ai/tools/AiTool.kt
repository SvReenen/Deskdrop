// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.ai.AiToolRegistry
import org.json.JSONObject

/**
 * Plugin interface for a single AI tool.
 *
 * New tools should be added as implementations of this interface under
 * `ai/tools/` and registered in [AiToolCatalog]. Existing tools still live
 * as private functions inside [AiToolRegistry]; they will be migrated over
 * time as they are touched.
 *
 * Design notes:
 *  - The interface is synchronous. Tools that need I/O should block inside
 *    their own [execute] call; callers already run tools on Dispatchers.IO.
 *  - Progress reporting, cancellation and async streaming are deliberately
 *    not included. They can be added later behind default methods without
 *    breaking existing tools.
 *  - Gating (network/action/always) moves from hardcoded strings in
 *    [AiToolRegistry.availableTools] to the [gate] property on each tool.
 */
interface AiTool {

    /** Unique tool name (must match across provider payloads). */
    val name: String

    /** Human-readable description used in the LLM-facing tool schema. */
    val description: String

    /** JSON Schema object describing this tool's parameters. */
    val parametersSchema: JSONObject

    /** When this tool is allowed to run. */
    val gate: ToolGate

    /** Run the tool. Must not throw for expected failures — return an error result instead. */
    fun execute(args: JSONObject, ctx: ToolContext): AiToolRegistry.ToolResult

    /** Convenience converter for the legacy [AiToolRegistry] list. */
    fun toLegacyDef(): AiToolRegistry.ToolDef =
        AiToolRegistry.ToolDef(name, description, parametersSchema)
}

/**
 * Permission gate for a tool. Maps to the existing `allowNetwork` /
 * `allowActions` prefs used by [AiToolRegistry.availableTools].
 */
enum class ToolGate {
    /** Safe read/compute tools — always available. */
    ALWAYS,

    /** Tools that make outbound HTTP requests. */
    NETWORK,

    /** Tools with side effects on the device (open app, set alarm, schedule reminder, …). */
    ACTIONS
}

/**
 * Per-invocation context passed to every tool.
 *
 *  - [appContext] is always the application context, never an activity.
 *  - [chatId] is whatever the current [helium314.keyboard.latin.ai.AiChatContextRegistry]
 *    contained at the moment the tool was dispatched. Null for inline AI use.
 *  - [prefs] is the device-protected shared prefs used throughout the app.
 *
 * New fields can be added here without breaking tools (data class copy).
 */
data class ToolContext(
    val appContext: Context,
    val chatId: String?,
    val prefs: SharedPreferences
)
