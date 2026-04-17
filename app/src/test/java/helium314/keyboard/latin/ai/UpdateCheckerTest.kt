// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test fun `newer major version is detected`() {
        assertTrue(UpdateChecker.isNewer("2.0", "1.3"))
    }

    @Test fun `newer minor version is detected`() {
        assertTrue(UpdateChecker.isNewer("1.4", "1.3"))
    }

    @Test fun `newer patch version is detected`() {
        assertTrue(UpdateChecker.isNewer("1.3.1", "1.3"))
    }

    @Test fun `same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.3", "1.3"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.2", "1.3"))
    }

    @Test fun `older patch is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.2.1", "1.3"))
    }

    @Test fun `version with more segments is newer`() {
        assertTrue(UpdateChecker.isNewer("1.3.0.1", "1.3"))
    }

    @Test fun `version with fewer segments is not newer if equal`() {
        assertFalse(UpdateChecker.isNewer("1.3", "1.3.0"))
    }
}
