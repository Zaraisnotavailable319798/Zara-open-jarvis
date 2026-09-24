package com.openjarvis.ui.tutorial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.agent.Action
import com.openjarvis.voice.VoiceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TutorialMode(private val context: Context) {

    private val voiceManager = VoiceManager(context)

    private val modeFlow =
        MutableStateFlow<TutorialState>(TutorialState.Off)

    private val currentStepFlow =
        MutableStateFlow<TutorialStep?>(null)

    private var overlayWindow: WindowManager? = null
    private var highlightView: TutorialHighlightView? = null

    private val handler = Handler(Looper.getMainLooper())

    private val stepHistory = mutableListOf<String>()

    sealed class TutorialState {
        object Off : TutorialState()
        object Guided : TutorialState()
        object Observe : TutorialState()
        object Shadow : TutorialState()
    }

    data class TutorialStep(
        val stepNumber: Int,
        val description: String,
        val targetBounds: RectF?,
        val narration: String
    )

    val state: StateFlow<TutorialState> = modeFlow

    val currentStep: StateFlow<TutorialStep?> = currentStepFlow

    suspend fun startGuidedMode() {
        modeFlow.value = TutorialState.Guided
    }

    suspend fun executeStep(
        action: Action,
        bounds: RectF?
    ) {
        if (modeFlow.value != TutorialState.Guided) {
            return
        }

        val stepNum = stepHistory.size + 1
        val narration = generateNarration(action)

        val step = TutorialStep(
            stepNumber = stepNum,
            description = action.description ?: action.action,
            targetBounds = bounds,
            narration = narration
        )

        currentStepFlow.value = step

        showHighlight(
            bounds = bounds,
            text = narration
        )

        speak(narration)

        delay(1000)

        executeAction(action)

        stepHistory.add(
            "${stepNum}. ${action.action}"
        )

        currentStepFlow.value = null

        hideHighlight()
    }

    private fun generateNarration(action: Action): String {
        return when (action.action) {

            Action.OPEN_APP ->
                "Opening ${action.label ?: action.packageName ?: "the app"}"

            Action.TAP ->
                "Tapping ${action.text ?: "the button"}"

            Action.TYPE ->
                "Typing ${action.value ?: ""}"

            Action.SCROLL ->
                "Scrolling ${action.direction ?: "down"}"

            Action.PRESS_BACK ->
                "Pressing back"

            Action.PRESS_HOME ->
                "Going to home screen"

            else ->
                "Performing ${action.action}"
        }
    }

    private fun speak(text: String) {
        voiceManager.speak(text)
    }

    private fun showHighlight(
        bounds: RectF?,
        text: String
    ) {
        if (bounds == null) {
            return
        }

        try {
            hideHighlight()

            highlightView = TutorialHighlightView(context).apply {
                setTarget(bounds, text)
            }

            overlayWindow =
                context.getSystemService(Context.WINDOW_SERVICE)
                        as? WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            overlayWindow?.addView(
                highlightView,
                params
            )

        } catch (e: Exception) {
            e.printStackTrace()
            highlightView = null
        }
    }

    private fun hideHighlight() {
        try {
            highlightView?.let { view ->
                overlayWindow?.removeView(view)
            }
        } catch (_: Exception) {
        }

        highlightView = null
    }

    private fun executeAction(action: Action) {

        val service = JarvisAccessibilityService.instance

        when (action.action) {

            Action.OPEN_APP -> {
                action.packageName?.let { packageName ->
                    service?.openAppByPackage(packageName)
                }
            }

            Action.TAP -> {
                action.text?.let { text ->
                    service?.tapByText(text)
                }
            }

            Action.TYPE -> {
                action.value?.let { value ->
                    service?.typeText(value)
                }
            }

            Action.PRESS_BACK -> {
                service?.pressBack()
            }

            Action.PRESS_HOME -> {
                service?.pressHome()
            }

            else -> {
                // Unsupported tutorial action.
            }
        }
    }

    fun getSummary(): String {
        return stepHistory
            .mapIndexed { index, step ->
                "${index + 1}. $step"
            }
            .joinToString("\n")
    }

    fun endSession() {
        hideHighlight()

        modeFlow.value = TutorialState.Off

        currentStepFlow.value = null
    }

    fun isActive(): Boolean {
        return modeFlow.value != TutorialState.Off
    }

    private class TutorialHighlightView(
        context: Context
    ) : View(context) {

        private var targetBounds: RectF? = null
        private var targetText: String = ""

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF9B59B6.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }

        fun setTarget(
            bounds: RectF,
            text: String
        ) {
            targetBounds = RectF(bounds)
            targetText = text

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            targetBounds?.let { bounds ->

                val animatedAlpha =
                    0.4f +
                            0.6f *
                            ((System.currentTimeMillis() % 1000L) / 1000f)

                paint.alpha =
                    (animatedAlpha * 255f)
                        .toInt()
                        .coerceIn(0, 255)

                canvas.drawRoundRect(
                    bounds,
                    16f,
                    16f,
                    paint
                )

                postInvalidateDelayed(50)
            }
        }
    }
}
