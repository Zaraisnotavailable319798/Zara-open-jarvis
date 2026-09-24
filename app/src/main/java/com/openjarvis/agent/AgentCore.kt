package com.openjarvis.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import com.openjarvis.graphify.AnalysisEngine
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.intelligence.AIAppInteractor
import com.openjarvis.intelligence.AIApps
import com.openjarvis.intelligence.AppAnalyzer
import com.openjarvis.intelligence.TaskRouter
import com.openjarvis.intelligence.TaskWorkingMemory
import com.openjarvis.llm.UniversalAdapter
import com.openjarvis.vision.VisionModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AgentCore(private val context: Context) {

    private val graphifyRepo = GraphifyRepository(context)
    private val analysisEngine = AnalysisEngine(context)
    private val universalAdapter = UniversalAdapter(context)

    private fun readScreenText(): String {
        val service =
            JarvisAccessibilityService.instance
                ?: return ""

        return ScreenReader(service).extractAllText()
    }

    private val visionModule = VisionModule.getInstance(context)
    private val taskRouter = TaskRouter(context)
    private val appAnalyzer = AppAnalyzer(context)

    /*
     * AIAppInteractor requires the active AccessibilityService.
     * Resolve it only when needed because the service may not exist
     * when AgentCore is created.
     */
    private val aiAppInteractor: AIAppInteractor?
        get() {
            val service =
                JarvisAccessibilityService.instance
                    ?: return null

            return AIAppInteractor(service)
        }

    private var workingMemory = TaskWorkingMemory()

    private val scope =
        CoroutineScope(Dispatchers.IO)

    private val taskMutex =
        Mutex()

    private val _state =
        MutableStateFlow<AgentState>(
            AgentState.Idle
        )

    val state: StateFlow<AgentState> =
        _state

    private val systemPrompt = """
You are Open Jarvis — an Android device control AI agent.
The user gives you a cleanCommand in natural language.
You must respond with ONLY a valid JSON array of actions. No explanation. No markdown fences. No preamble. Pure JSON array only.

AVAILABLE ACTIONS:
open_app     → {"action":"open_app","package":"com.package","label":"AppName"}
tap          → {"action":"tap","text":"Button text on screen"}
tap_coords   → {"action":"tap_coords","x":540,"y":960}
long_press   → {"action":"long_press","text":"Element text"}
type         → {"action":"type","value":"text to type"}
clear_type   → {"action":"clear_type","value":"clears field then types"}
swipe        → {"action":"swipe","direction":"up|down|left|right","distance":"short|medium|long"}
scroll       → {"action":"scroll","direction":"up|down"}
press_back   → {"action":"press_back"}
press_home   → {"action":"press_home"}
press_recents → {"action":"press_recents"}
wait_for     → {"action":"wait_for","text":"expected text","timeout_ms":3000}
screenshot   → {"action":"screenshot"}
read_screen  → {"action":"read_screen"}
ai_prompt    → {"action":"ai_prompt","package":"com.openai.chatgpt","prompt":"{prompt}","outputKey":"result"}
extract_text → {"action":"extract_text","outputKey":"page_text"}

CURRENT SCREEN CONTENT: {SCREEN_OCR}

APP SELECTION REASONING: {APP_REASONING}

INSTALLED AI APPS: {AI_APPS}

RECENT MEMORY CONTEXT: {GRAPHIFY_CONTEXT}

RULES:
- Always start complex tasks with open_app
- Add wait_for after open_app to confirm app loaded
- If screen content is empty or unclear, add read_screen as first action
- Never assume UI state — always verify with wait_for
- Keep action arrays short: 2-8 steps per task
- Use ai_prompt to delegate complex reasoning to installed AI apps
- Prefer a known installed AI app when the user explicitly asks for one
- For coordinate actions, use realistic screen coordinates
- Use clear_type before typing into an already populated field
- Use wait_for when an action depends on a UI element appearing
- If a task is impossible to do safely, return: [{"action":"error","message":"reason"}]
""".trimIndent()

    fun executeTask(cleanCommand: String) {

        workingMemory =
            TaskWorkingMemory()

        scope.launch {

            taskMutex.withLock {

                try {

                    val sanitized =
                        PromptSanitizer.sanitize(
                            cleanCommand
                        )

                    when (sanitized) {

                        is PromptSanitizer.SanitizeResult.Rejected -> {
                            _state.value =
                                AgentState.Error(
                                    sanitized.reason
                                )

                            return@withLock
                        }

                        is PromptSanitizer.SanitizeResult.Suspicious -> {
                            _state.value =
                                AgentState.Running(
                                    "analyzing..."
                                )
                        }

                        is PromptSanitizer.SanitizeResult.Clean -> {
                        }
                    }

                    val finalCommand =
                        when (sanitized) {

                            is PromptSanitizer.SanitizeResult.Suspicious ->
                                sanitized.sanitized

                            is PromptSanitizer.SanitizeResult.Clean ->
                                sanitized.text

                            else ->
                                cleanCommand
                        }

                    _state.value =
                        AgentState.Running(
                            "analyzing task..."
                        )

                    val plan =
                        taskRouter.analyze(
                            finalCommand
                        )

                    _state.value =
                        AgentState.Running(
                            "reading screen..."
                        )

                    val screenText =
                        withContext(Dispatchers.IO) {
                            readScreenText()
                        }

                    _state.value =
                        AgentState.Running(
                            "getting context..."
                        )

                    val memoryContext =
                        graphifyRepo.buildMemoryContext(
                            finalCommand
                        )

                    val fullSystem =
                        systemPrompt
                            .replace(
                                "{SCREEN_OCR}",
                                screenText.take(2000)
                            )
                            .replace(
                                "{APP_REASONING}",
                                plan.reasoning
                            )
                            .replace(
                                "{AI_APPS}",
                                getInstalledAIApps()
                            )
                            .replace(
                                "{GRAPHIFY_CONTEXT}",
                                if (memoryContext.isBlank()) {
                                    "No recent tasks"
                                } else {
                                    memoryContext
                                }
                            )

                    _state.value =
                        AgentState.Running(
                            "thinking..."
                        )

                    val startTime =
                        System.currentTimeMillis()

                    val result =
                        universalAdapter.complete(
                            fullSystem,
                            finalCommand
                        )

                    result.fold(

                        onSuccess = { rawJson ->

                            val latency =
                                System.currentTimeMillis() -
                                    startTime

                            val validation =
                                LLMResponseValidator.validate(
                                    rawJson
                                )

                            if (
                                !validation.isValid &&
                                validation.errors.isNotEmpty()
                            ) {

                                _state.value =
                                    AgentState.Error(
                                        "Invalid response: ${
                                            validation.errors.first()
                                        }"
                                    )

                                graphifyRepo.logTask(
                                    finalCommand,
                                    "failed: validation error",
                                    "",
                                    0
                                )

                                return@fold
                            }

                            val actions =
                                ActionJsonParser.parse(
                                    rawJson
                                ) ?: run {

                                    val retry =
                                        universalAdapter.complete(
                                            fullSystem,
                                            "$finalCommand\n\nRespond with JSON array ONLY. No other text."
                                        )

                                    retry
                                        .getOrNull()
                                        ?.let {
                                            ActionJsonParser.parse(
                                                it
                                            )
                                        }
                                }

                            if (actions == null) {

                                _state.value =
                                    AgentState.Error(
                                        "Could not parse AI response"
                                    )

                                graphifyRepo.logTask(
                                    finalCommand,
                                    "failed: parse error",
                                    "",
                                    0
                                )

                                return@fold
                            }

                            _state.value =
                                AgentState.Running(
                                    "executing ${actions.size} actions..."
                                )

                            executeActions(actions)

                            graphifyRepo.logTask(
                                finalCommand,
                                "success",
                                universalAdapter.getProviderName(),
                                latency
                            )

                            analysisEngine.analyzeLastTask()

                            _state.value =
                                AgentState.Done(
                                    "done in ${latency}ms"
                                )
                        },

                        onFailure = { error ->

                            val msg =
                                when {

                                    error.message
                                        ?.contains("401") == true ->
                                        "Invalid API key"

                                    error.message
                                        ?.contains("429") == true ->
                                        "Rate limited — wait a moment"

                                    error.message
                                        ?.contains("timeout") == true ->
                                        "Request timed out"

                                    error.message
                                        ?.contains("Unable to resolve") == true ->
                                        "Network error — check connection"

                                    else ->
                                        error.message
                                            ?: "Unknown error"
                                }

                            _state.value =
                                AgentState.Error(msg)

                            graphifyRepo.logTask(
                                finalCommand,
                                "failed: $msg",
                                "",
                                0
                            )
                        }
                    )

                } catch (e: Exception) {

                    _state.value =
                        AgentState.Error(
                            e.message
                                ?: "Unknown error"
                        )

                    graphifyRepo.logTask(
                        cleanCommand,
                        "failed: ${e.message}",
                        "",
                        0
                    )
                }
            }
        }
    }

    suspend fun testConnection(): Result<Long> =
        universalAdapter.testConnection()

    fun getCurrentProviderName(): String =
        universalAdapter.getProviderName()

    fun getStateFlow(): StateFlow<AgentState> =
        state

    private fun getInstalledAIApps(): String {

        val pm =
            context.packageManager

        val installed =
            AIApps.KNOWN_AI_APPS
                .filter { (packageName, _) ->
                    try {
                        pm.getApplicationInfo(
                            packageName,
                            0
                        )

                        true

                    } catch (_: Exception) {
                        false
                    }
                }
                .map { (_, meta) ->
                    "${meta.appName} (${meta.packageName})"
                }

        return if (installed.isEmpty()) {
            "No known AI apps installed"
        } else {
            installed.joinToString(", ")
        }
    }

    suspend fun getAnalyzedAppCount(): Int =
        appAnalyzer.getAnalyzedCount()

    suspend fun getAIAppCount(): Int =
        appAnalyzer.getAICount()

    private suspend fun executeActions(
        actions: List<Action>
    ) {

        for (
            (index, action)
            in actions.withIndex()
        ) {

            _state.value =
                AgentState.Running(
                    "action ${index + 1}/${actions.size}: ${action.action}"
                )

            val success =
                when (action.action) {

                    Action.OPEN_APP ->
                        executeOpenApp(action)

                    Action.TAP ->
                        executeTap(action)

                    Action.TAP_COORDS ->
                        executeTapCoords(action)

                    Action.TYPE ->
                        executeType(action)

                    Action.CLEAR_TYPE ->
                        executeClearType(action)

                    Action.LONG_PRESS ->
                        executeLongPress(action)

                    Action.SWIPE ->
                        executeSwipe(action)

                    Action.SCROLL ->
                        executeScroll(action)

                    Action.PRESS_BACK ->
                        JarvisAccessibilityService
                            .instance
                            ?.pressBack() == true

                    Action.PRESS_HOME ->
                        JarvisAccessibilityService
                            .instance
                            ?.pressHome() == true

                    Action.PRESS_RECENTS ->
                        JarvisAccessibilityService
                            .instance
                            ?.pressRecents() == true

                    Action.WAIT_FOR ->
                        executeWaitFor(action)

                    Action.SCREENSHOT ->
                        executeScreenshot()

                    Action.READ_SCREEN ->
                        executeReadScreen()

                    Action.AI_PROMPT ->
                        executeAIPrompt(action)

                    Action.EXTRACT_TEXT ->
                        executeExtractText(action)

                    Action.ERROR -> {

                        _state.value =
                            AgentState.Error(
                                action.message
                                    ?: "Task failed"
                            )

                        return
                    }

                    else -> {

                        _state.value =
                            AgentState.Error(
                                "Unknown action: ${action.action}"
                            )

                        return
                    }
                }

            if (!success) {

                _state.value =
                    AgentState.Error(
                        "Action failed: ${action.action}"
                    )

                return
            }

            delay(500)
        }
    }

    private suspend fun executeOpenApp(
        action: Action
    ): Boolean {

        var packageName =
            action.packageName

        if (packageName.isNullOrBlank()) {

            val label =
                action.label

            if (label != null) {
                packageName =
                    findPackageByLabel(label)
            }
        }

        if (packageName.isNullOrBlank()) {
            return false
        }

        val label =
            action.label
                ?: packageName

        val opened =
            JarvisAccessibilityService
                .instance
                ?.openAppByPackage(
                    packageName
                )
                ?: false

        if (opened) {

            graphifyRepo.logAppOpened(
                packageName,
                label
            )
        }

        return opened
    }

    private fun executeTap(
        action: Action
    ): Boolean {

        val text =
            action.text
                ?: return false

        return JarvisAccessibilityService
            .instance
            ?.tapByText(text)
            ?: false
    }

    private fun executeTapCoords(
        action: Action
    ): Boolean {

        val x =
            action.x
                ?: return false

        val y =
            action.y
                ?: return false

        return performTap(
            x,
            y
        )
    }

    private fun executeType(
        action: Action
    ): Boolean {

        val value =
            workingMemory.interpolate(
                action.value ?: ""
            )

        return JarvisAccessibilityService
            .instance
            ?.typeText(value)
            ?: false
    }

    private fun executeClearType(
        action: Action
    ): Boolean {

        val value =
            workingMemory.interpolate(
                action.value ?: ""
            )

        val service =
            JarvisAccessibilityService
                .instance
                ?: return false

        if (!service.typeText("")) {
            return false
        }

        return service.typeText(value)
    }

    private fun executeLongPress(
        action: Action
    ): Boolean {

        val text =
            action.text
                ?: return false

        val service =
            JarvisAccessibilityService
                .instance
                ?: return false

        val root =
            service.rootInActiveWindow
                ?: return false

        val node =
            findNodeByText(
                root,
                text
            )
                ?: return false

        val bounds =
            Rect()

        node.getBoundsInScreen(bounds)

        node.recycle()

        if (bounds.isEmpty) {
            return false
        }

        return performLongPress(
            bounds.centerX(),
            bounds.centerY()
        )
    }

    private fun executeSwipe(
        action: Action
    ): Boolean {

        val direction =
            action.direction
                ?.lowercase()
                ?: return false

        val distance =
            when (
                action.distance?.lowercase()
            ) {

                "short" -> 300f
                "long" -> 850f
                else -> 550f
            }

        val service =
            JarvisAccessibilityService
                .instance
                ?: return false

        val metrics =
            context.resources.displayMetrics

        val centerX =
            metrics.widthPixels / 2f

        val centerY =
            metrics.heightPixels / 2f

        var startX = centerX
        var startY = centerY
        var endX = centerX
        var endY = centerY

        when (direction) {

            "up" -> {
                startY =
                    centerY + distance / 2f

                endY =
                    centerY - distance / 2f
            }

            "down" -> {
                startY =
                    centerY - distance / 2f

                endY =
                    centerY + distance / 2f
            }

            "left" -> {
                startX =
                    centerX + distance / 2f

                endX =
                    centerX - distance / 2f
            }

            "right" -> {
                startX =
                    centerX - distance / 2f

                endX =
                    centerX + distance / 2f
            }

            else ->
                return false
        }

        return dispatchSwipe(
            service,
            startX,
            startY,
            endX,
            endY
        )
    }

    private fun executeScroll(
        action: Action
    ): Boolean {

        val direction =
            action.direction
                ?.lowercase()
                ?: "down"

        return executeSwipe(
            action.copy(
                action = Action.SWIPE,
                direction = direction,
                distance =
                    action.distance
                        ?: "medium"
            )
        )
    }

    private suspend fun executeWaitFor(
        action: Action
    ): Boolean {

        val expected =
            action.text
                ?: action.value
                ?: return false

        val timeout =
            action.timeoutMs
                .coerceAtLeast(500L)
                .coerceAtMost(30_000L)

        val start =
            SystemClock.uptimeMillis()

        while (
            SystemClock.uptimeMillis() - start <
            timeout
        ) {

            val screen =
                withContext(Dispatchers.IO) {
                    readScreenText()
                }

            if (
                screen.contains(
                    expected,
                    ignoreCase = true
                )
            ) {
                return true
            }

            delay(250)
        }

        return false
    }

    private suspend fun executeScreenshot(): Boolean {

        return withContext(Dispatchers.IO) {
            readScreenText()
            true
        }
    }

    private suspend fun executeReadScreen(): Boolean {

        val text =
            withContext(Dispatchers.IO) {
                readScreenText()
            }

        workingMemory.set(
            "screen_text",
            text
        )

        return true
    }

    private suspend fun executeAIPrompt(
        action: Action
    ): Boolean {

        val packageName =
            action.packageName
                ?: return false

        val prompt =
            workingMemory.interpolate(
                action.prompt ?: ""
            )

        val outputKey =
            action.outputKey
                ?: "ai_result"

        val meta =
            AIApps.KNOWN_AI_APPS[
                packageName
            ]
                ?: return false

        val interactor =
            aiAppInteractor
                ?: return false

        _state.value =
            AgentState.Running(
                "opening ${meta.appName}..."
            )

        val response =
            interactor.runPrompt(
                meta = meta,
                prompt = prompt,
                timeoutMs = 60_000
            )

        if (response.isBlank()) {
            return false
        }

        workingMemory.set(
            outputKey,
            response
        )

        _state.value =
            AgentState.Running(
                "${meta.appName} responded"
            )

        return true
    }

    private suspend fun executeExtractText(
        action: Action
    ): Boolean {

        val outputKey =
            action.outputKey
                ?: "page_text"

        val text =
            withContext(Dispatchers.IO) {
                readScreenText()
            }

        workingMemory.set(
            outputKey,
            text
        )

        return true
    }

    private fun performTap(
        x: Int,
        y: Int
    ): Boolean {

        val service =
            JarvisAccessibilityService
                .instance
                ?: return false

        val metrics =
            context.resources.displayMetrics

        val safeX =
            x.coerceIn(
                1,
                metrics.widthPixels - 1
            )

        val safeY =
            y.coerceIn(
                1,
                metrics.heightPixels - 1
            )

        val path =
            Path().apply {
                moveTo(
                    safeX.toFloat(),
                    safeY.toFloat()
                )
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0L,
                80L
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        return service.dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun performLongPress(
        x: Int,
        y: Int
    ): Boolean {

        val service =
            JarvisAccessibilityService
                .instance
                ?: return false

        val path =
            Path().apply {
                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0L,
                900L
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        return service.dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun dispatchSwipe(
        service: JarvisAccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {

        val path =
            Path().apply {
                moveTo(
                    startX,
                    startY
                )

                lineTo(
                    endX,
                    endY
                )
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0L,
                500L
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        return service.dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {

        val normalized =
            text.lowercase().trim()

        val queue =
            java.util.ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node =
                queue.removeFirst()

            val nodeText =
                node.text
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val description =
                node.contentDescription
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            if (
                (
                    nodeText?.contains(normalized) == true ||
                    description?.contains(normalized) == true
                ) &&
                node.isVisibleToUser
            ) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            if (node !== root) {
                node.recycle()
            }
        }

        return null
    }

    private fun findPackageByLabel(
        label: String
    ): String? {

        val pm =
            context.packageManager

        val intent =
            Intent(
                Intent.ACTION_MAIN,
                null
            ).apply {
                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )
            }

        val apps: List<ResolveInfo> =
            pm.queryIntentActivities(
                intent,
                0
            )

        val normalizedLabel =
            label.lowercase().trim()

        for (app in apps) {

            val appLabel =
                app.loadLabel(pm)
                    .toString()
                    .lowercase()
                    .trim()

            if (
                appLabel == normalizedLabel ||
                appLabel.contains(normalizedLabel) ||
                normalizedLabel.contains(appLabel)
            ) {
                return app.activityInfo.packageName
            }
        }

        return null
    }
}
