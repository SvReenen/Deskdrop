// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import kotlinx.coroutines.flow.MutableStateFlow

object AiStreamBridge {
    enum class State { IDLE, STREAMING, DONE, ERROR }

    val text = MutableStateFlow("")
    val state = MutableStateFlow(State.IDLE)
    @Volatile var errorMessage: String? = null
        private set

    fun start() {
        text.value = ""
        errorMessage = null
        state.value = State.STREAMING
    }

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        text.value = text.value + chunk
    }

    fun complete() {
        state.value = State.DONE
    }

    fun error(msg: String) {
        errorMessage = msg
        state.value = State.ERROR
    }

    fun reset() {
        text.value = ""
        errorMessage = null
        state.value = State.IDLE
    }

    /**
     * Cancel the current stream without surfacing an error message to the UI.
     * Used when the user hard-cancels an in-flight AI call.
     */
    fun cancel() {
        text.value = ""
        errorMessage = null
        state.value = State.IDLE
    }
}
