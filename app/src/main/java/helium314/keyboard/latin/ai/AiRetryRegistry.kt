// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

/**
 * Holds a single "retry the last AI call" closure so the result view can offer
 * a Retry button after a transient failure (rate limit, timeout, …) without
 * needing the user to navigate back to the original prompt screen.
 *
 * The retry action is registered when a stream starts and cleared when the
 * result view closes (or when a new stream is registered).
 */
object AiRetryRegistry {
    @Volatile
    private var action: (() -> Unit)? = null

    fun set(action: () -> Unit) {
        this.action = action
    }

    fun hasAction(): Boolean = action != null

    fun clear() {
        action = null
    }

    fun retry() {
        action?.invoke()
    }
}
