package com.openjarvis.intelligence

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import kotlinx.coroutines.delay

class AIAppInteractor(private val context: Context) {

    private val screenReader = ScreenReader(context)
    private val workingMemory = TaskWorkingMemory()

    fun openAIApp(meta: AIAppMeta): Boolean {
        return JarvisAccessibilityService.instance
            ?.openAppByPackage(meta.packageName)
            ?: false
    }

    suspend fun prepareAIApp(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        if (!service.openAppByPackage(meta.packageName)) {
            return false
        }

        // Give the target app time to render.
        delay(1500)

        // Try to focus the known input field first.
        if (meta.inputFieldHint.isNotBlank()) {
            if (service.focusInputByText(meta.inputFieldHint)) {
                return true
            }
        }

        // Fallback: find any editable input.
        return service.focusFirstInput()
    }

    fun clearContext() {
        try {
            JarvisAccessibilityService.instance?.pressBack()
        } catch (e: Exception) {
        }
    }

    suspend fun typePrompt(prompt: String): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        // First try the currently focused input.
        if (service.typeText(prompt)) {
            return true
        }

        // If there was no focused input, try to locate one.
        if (service.focusFirstInput()) {
            delay(200)
            return service.typeText(prompt)
        }

        return false
    }

    suspend fun typePrompt(
        meta: AIAppMeta,
        prompt: String
    ): Boolean {

        val service = JarvisAccessibilityService.instance
            ?: return false

        // Try the metadata-provided input hint.
        if (meta.inputFieldHint.isNotBlank()) {
            if (
                service.typeTextIntoInput(
                    meta.inputFieldHint,
                    prompt
                )
            ) {
                return true
            }
        }

        // Fallback to the currently focused / first input.
        return typePrompt(prompt)
    }

    suspend fun sendPrompt(meta: AIAppMeta): Boolean {

        val service = JarvisAccessibilityService.instance
            ?: return false

        /*
         * Some apps expose a text button/content description
         * for sending a message. Try common labels first.
         */
        val sendLabels = listOf(
            "Send",
            "send",
            "Submit",
            "submit",
            "Ask",
            "ask"
        )

        for (label in sendLabels) {
            if (service.tapByText(label)) {
                return true
            }

            if (service.tapByContentDescription(label)) {
                return true
            }
        }

        /*
         * Fallback:
         * many Android chat inputs submit with IME Enter.
         */
        return service.pressEnter()
    }

    suspend fun runPrompt(
        meta: AIAppMeta,
        prompt: String,
        timeoutMs: Long = 60_000
    ): String {

        if (!prepareAIApp(meta)) {
            return ""
        }

        delay(300)

        if (!typePrompt(meta, prompt)) {
            return ""
        }

        delay(300)

        sendPrompt(meta)

        return waitForResponse(timeoutMs)
    }

    suspend fun waitForResponse(
        timeoutMs: Long = 60_000
    ): String {

        val startTime = System.currentTimeMillis()

        var lastText = ""
        var sameCount = 0

        while (
            System.currentTimeMillis() - startTime < timeoutMs
        ) {

            delay(1000)

            val currentText =
                screenReader.extractAllText()

            if (
                currentText.isNotBlank() &&
                currentText == lastText
            ) {

                sameCount++

                /*
                 * Same screen text for several checks means
                 * the response is probably finished.
                 */
                if (sameCount >= 2) {
                    return currentText
                }

            } else {

                sameCount = 0

                if (currentText.isNotBlank()) {
                    lastText = currentText
                }
            }
        }

        return lastText
    }

    fun extractResponse(meta: AIAppMeta): String {

        return when (meta.responseExtraction) {

            ResponseExtraction.SCREEN_TEXT ->
                screenReader.extractAllText()

            ResponseExtraction.OCR_REQUIRED ->
                screenReader.extractAllText()

            ResponseExtraction.COPY_BUTTON ->
                tryCopyFromUI()

            ResponseExtraction.SHARE_MENU ->
                tryShareFromUI()
        }
    }

    fun getWorkingMemory(): TaskWorkingMemory =
        workingMemory

    private fun tryCopyFromUI(): String {
        return screenReader.extractAllText()
    }

    private fun tryShareFromUI(): String {
        return screenReader.extractAllText()
    }
}
