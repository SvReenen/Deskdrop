// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.settings.screens.TipsData
import kotlin.test.Test
import kotlin.test.assertTrue

class TipsDataTest {

    @Test fun `all tips have non-empty title`() {
        TipsData.all.forEachIndexed { i, tip ->
            assertTrue(tip.title.isNotBlank(), "Tip $i has empty title")
        }
    }

    @Test fun `all tips have non-empty body`() {
        TipsData.all.forEachIndexed { i, tip ->
            assertTrue(tip.body.isNotBlank(), "Tip $i has empty body")
        }
    }

    @Test fun `tips list is not empty`() {
        assertTrue(TipsData.all.isNotEmpty())
    }

    @Test fun `inline icon placeholders exist in body`() {
        TipsData.all.forEachIndexed { i, tip ->
            tip.inlineIcons.forEach { icon ->
                assertTrue(
                    tip.body.contains(icon.placeholder),
                    "Tip $i body does not contain placeholder '${icon.placeholder}'"
                )
            }
        }
    }
}
