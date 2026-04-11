// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

/**
 * Central list of tools built on the new [AiTool] interface.
 *
 * Legacy tools (calculator, weather, fetch_url, etc.) still live inside
 * [helium314.keyboard.latin.ai.AiToolRegistry] as private functions and
 * are combined with this catalog at lookup time. Add new tools here
 * instead of touching the legacy registry's `when` dispatch.
 */
object AiToolCatalog {

    /** Registered tools. Order is preserved in the tool list returned to the LLM. */
    val tools: List<AiTool> = listOf(
        SetReminderTool(),
        CalendarTool(),
        NavigationTool(),
        PhoneCallTool(),
        SendSmsTool(),
        ContactLookupTool()
    )

    fun byName(name: String): AiTool? = tools.firstOrNull { it.name == name }

    /** Filter tools by whether they are currently permitted. */
    fun enabled(allowNetwork: Boolean, allowActions: Boolean): List<AiTool> =
        tools.filter { tool ->
            when (tool.gate) {
                ToolGate.ALWAYS -> true
                ToolGate.NETWORK -> allowNetwork
                ToolGate.ACTIONS -> allowActions
            }
        }
}
