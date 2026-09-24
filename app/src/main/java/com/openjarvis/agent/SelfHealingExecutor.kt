package com.openjarvis.agent

import com.openjarvis.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

class SelfHealingExecutor(private val context: android.content.Context) {

    private val maxAttempts = 3
    private val baseDelayMs = 1000L

    suspend fun executeWithHealing(
        action: Action,
        context: ExecutionContext,
        attempt: Int = 1
    ): ActionResult {

        val result = tryExecuteAction(action, context)

        if (result is ActionResult.Success) return result

        if (attempt >= maxAttempts) {
            return ActionResult.Failed(
                "Could not complete after $maxAttempts attempts"
            )
        }

        delay(baseDelayMs * attempt)

        return executeWithHealing(action, context, attempt + 1)
    }

    private fun tryExecuteAction(
        action: Action,
        context: ExecutionContext
    ): ActionResult {
        return try {
            val service = JarvisAccessibilityService.instance
                ?: return ActionResult.Failed("Accessibility service is not available")

            when (action.action) {
                Action.TAP -> {
                    val tapped = service.tapByText(action.text ?: "")
                    if (tapped == true) {
                        ActionResult.Success("tapped ${action.text}")
                    } else {
                        ActionResult.Failed("tap failed")
                    }
                }

                Action.TYPE -> {
                    val typed = service.typeText(action.value ?: "")
                    if (typed == true) {
                        ActionResult.Success("typed ${action.value}")
                    } else {
                        ActionResult.Failed("type failed")
                    }
                }

                Action.OPEN_APP -> {
                    val packageName = action.packageName ?: ""
                    if (packageName.isBlank()) {
                        ActionResult.Failed("No package name provided")
                    } else {
                        val opened = service.openAppByPackage(packageName)
                        if (opened == true) {
                            ActionResult.Success("opened $packageName")
                        } else {
                            ActionResult.Failed("could not open $packageName")
                        }
                    }
                }

                Action.PRESS_BACK -> {
                    service.pressBack()
                    ActionResult.Success("pressed back")
                }

                else -> {
                    ActionResult.Success(
                        "action ${action.action} executed"
                    )
                }
            }
        } catch (e: Exception) {
            ActionResult.Failed(
                e.message ?: "Unknown error"
            )
        }
    }

    data class ExecutionContext(
        val expectedState: String?,
        val phaseId: String,
        val previousPhaseOutcome: String?,
        val screenBefore: String
    )

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class Failed(val reason: String) : ActionResult()
    }
}
