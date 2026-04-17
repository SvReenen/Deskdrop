// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.ShadowInputMethodManager2
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class AiServiceSyncTest {

    // --- extractInlinePseudoToolCalls ---

    @Test fun `no tool calls returns original text`() {
        val (text, calls) = AiServiceSync.extractInlinePseudoToolCalls("Hello world")
        assertEquals("Hello world", text)
        assertTrue(calls.isEmpty())
    }

    @Test fun `extracts double bracket tool call`() {
        val input = "Sure! [[{\"name\":\"set_reminder\",\"parameters\":{\"title\":\"Meeting\"}}]]"
        val (text, calls) = AiServiceSync.extractInlinePseudoToolCalls(input)
        assertEquals("Sure!", text)
        assertEquals(1, calls.size)
        assertEquals("set_reminder", calls[0].name)
        assertEquals("Meeting", calls[0].args.getString("title"))
    }

    @Test fun `extracts unicode bracket tool call`() {
        val input = "Done \u27E6{\"name\":\"phone_call\",\"parameters\":{\"number\":\"123\"}}\u27E7"
        val (text, calls) = AiServiceSync.extractInlinePseudoToolCalls(input)
        assertEquals("Done", text)
        assertEquals(1, calls.size)
        assertEquals("phone_call", calls[0].name)
    }

    @Test fun `extracts tool_call tag format`() {
        val input = "OK <|tool_call|>{\"name\":\"send_sms\",\"parameters\":{\"to\":\"Bob\"}}<|/tool_call|>"
        val (text, calls) = AiServiceSync.extractInlinePseudoToolCalls(input)
        assertEquals("OK", text)
        assertEquals(1, calls.size)
        assertEquals("send_sms", calls[0].name)
    }

    @Test fun `multiple tool calls extracted`() {
        val input = "[[{\"name\":\"a\",\"parameters\":{}}]] and [[{\"name\":\"b\",\"parameters\":{}}]]"
        val (_, calls) = AiServiceSync.extractInlinePseudoToolCalls(input)
        assertEquals(2, calls.size)
    }

    @Test fun `invalid json is ignored`() {
        val input = "Test [[not json]] done"
        val (_, calls) = AiServiceSync.extractInlinePseudoToolCalls(input)
        assertTrue(calls.isEmpty())
    }

    // --- friendlyHttpError ---

    @Test fun `invalid api key returns friendly message`() {
        val msg = AiServiceSync.friendlyHttpError("Gemini", 400, "Invalid API key provided")
        assertTrue(msg.contains("invalid API key"))
    }

    @Test fun `quota exceeded returns friendly message`() {
        val msg = AiServiceSync.friendlyHttpError("OpenRouter", 400, "quota exceeded for today")
        assertTrue(msg.contains("quota exceeded"))
    }

    @Test fun `country not supported returns friendly message`() {
        val msg = AiServiceSync.friendlyHttpError("Gemini", 403, "User location is not supported")
        assertTrue(msg.contains("not available in your country"))
    }

    @Test fun `safety blocked returns friendly message`() {
        val msg = AiServiceSync.friendlyHttpError("Gemini", 400, "blocked due to safety HARM_CATEGORY")
        assertTrue(msg.contains("safety filter"))
    }

    @Test fun `model not found returns friendly message`() {
        val msg = AiServiceSync.friendlyHttpError("OpenRouter", 400, "model_not_found")
        assertTrue(msg.contains("model not available"))
    }
}
