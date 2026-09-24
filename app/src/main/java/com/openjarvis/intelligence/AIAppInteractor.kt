package com.openjarvis.intelligence

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import kotlinx.coroutines.delay

class AIAppInteractor(private val context: Context) {

    private val screenReader = ScreenReader(context)
    private val workingMemory = TaskWorkingMemory()

    fun openAIApp(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        return service.openAppByPackage(meta.packageName)
    }

    suspend fun prepareAIApp(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        if (!service.openAppByPackage(meta.packageName)) {
            return false
        }

        delay(1800)

        if (meta.inputFieldHint.isNotBlank()) {
            if (service.focusInputByText(meta.inputFieldHint)) {
                return true
            }
        }

        return service.focusFirstInput()
    }

    fun clearContext(): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        return service.focusFirstInput()
    }

    suspend fun typePrompt(prompt: String): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        if (service.typeText(prompt)) {
            return true
        }

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

        return typePrompt(prompt)
    }

    suspend fun sendPrompt(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: return false

        val preferredLabel = meta.sendButtonText

        if (!preferredLabel.isNullOrBlank()) {
            if (service.tapByText(preferredLabel)) {
                return true
            }

            if (service.tapByContentDescription(preferredLabel)) {
                return true
            }
        }

        val sendLabels = listOf(
            "Send",
            "Submit",
            "Ask"
        )

        for (label in sendLabels) {
            if (service.tapByText(label)) {
                return true
            }

            if (service.tapByContentDescription(label)) {
                return true
            }
        }

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

        val previousScreen = screenReader.extractAllText()

        delay(300)

        if (!typePrompt(meta, prompt)) {
            return ""
        }

        delay(300)

        if (!sendPrompt(meta)) {
            return ""
        }

        return waitForResponse(
            timeoutMs = timeoutMs,
            previousText = previousScreen
        )
    }

    suspend fun waitForResponse(
        timeoutMs: Long = 60_000,
        previousText: String = ""
    ): String {

        val startTime = System.currentTimeMillis()
        var lastText = previousText
        var stableCount = 0

        while (
            System.currentTimeMillis() - startTime < timeoutMs
        ) {
            delay(1000)

            val currentText = screenReader.extractAllText()

            if (currentText.isBlank()) {
                continue
            }

            if (currentText != previousText) {
                if (currentText == lastText) {
                    stableCount++
                } else {
                    stableCount = 0
                    lastText = currentText
                }

                if (stableCount >= 2) {
                    return currentText
                }
            } else {
                stableCount = 0
            }
        }

        return if (lastText != previousText) {
            lastText
        } else {
            ""
        }
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
