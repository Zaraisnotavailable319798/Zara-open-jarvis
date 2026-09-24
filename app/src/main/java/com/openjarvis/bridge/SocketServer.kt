package com.openjarvis.bridge

import android.content.Context
import android.os.Process
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.graphify.GraphifyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SocketServer(
    private val context: Context,
    private val agentCore: AgentCore,
    private val graphifyRepo: GraphifyRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var runningPort = 0

    private val socketFile: java.io.File
        get() = java.io.File(context.filesDir, SOCKET_NAME)

    private val allowedUids = setOf(
        context.applicationInfo.uid,
        2000,
        "com.termux".hashCode()
    )

    private fun isAuthorized(): Boolean {
        return try {
            Process.myUid() in allowedUids
        } catch (e: Exception) {
            false
        }
    }

    fun start() {
        if (isRunning) return

        isRunning = true

        scope.launch {
            acceptConnections()
        }
    }

    fun stop() {
        isRunning = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
        runningPort = 0

        try {
            if (socketFile.exists()) {
                socketFile.delete()
            }
        } catch (_: Exception) {
        }

        scope.coroutineContext[Job]?.cancelChildren()
    }

    fun getPort(): Int = runningPort

    private suspend fun acceptConnections() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(0)
            runningPort = serverSocket?.localPort ?: 0

            socketFile.writeText(runningPort.toString())

            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break

                    scope.launch {
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        delay(100)
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) =
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(
                    InputStreamReader(clientSocket.getInputStream())
                )

                val writer = PrintWriter(
                    clientSocket.getOutputStream(),
                    true
                )

                val line = reader.readLine()

                if (line.isNullOrBlank()) {
                    writer.println(
                        createErrorResponse("", "empty request")
                    )
                    return@withContext
                }

                if (line.length > MAX_COMMAND_LENGTH) {
                    writer.println(
                        createErrorResponse("", "request too long")
                    )
                    return@withContext
                }

                if (!isAuthorized()) {
                    writer.println(
                        createErrorResponse("", "unauthorized")
                    )
                    return@withContext
                }

                val request = parseRequest(line)

                if (request == null) {
                    writer.println(
                        createErrorResponse("", "invalid JSON")
                    )
                    return@withContext
                }

                val (requestId, cmd) = request

                when (cmd.lowercase()) {
                    "status" -> handleStatus(writer, requestId)
                    "history" -> handleHistory(writer, requestId)
                    "providers" -> handleProviders(writer, requestId)
                    "memory" -> handleMemory(writer, requestId)
                    else -> handleCommand(
                        writer,
                        requestId,
                        cmd
                    )
                }

                writer.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    clientSocket.close()
                } catch (_: Exception) {
                }
            }
        }

    private fun handleStatus(
        writer: PrintWriter,
        requestId: String
    ) {
        val accessibilityEnabled =
            com.openjarvis.accessibility.JarvisAccessibilityService.instance != null

        val provider = try {
            agentCore.getCurrentProviderName()
        } catch (_: Exception) {
            "unknown"
        }

        val statusJson = buildString {
            append("{")
            append("\"requestId\":\"")
            append(escapeJson(requestId))
            append("\",")
            append("\"status\":\"done\",")
            append("\"result\":{")
            append("\"service\":\"running\",")
            append("\"accessibility\":")
            append(accessibilityEnabled)
            append(",")
            append("\"provider\":\"")
            append(escapeJson(provider))
            append("\",")
            append("\"port\":")
            append(runningPort)
            append("}}")
        }

        writer.println(statusJson)
    }

    private suspend fun handleHistory(
        writer: PrintWriter,
        requestId: String
    ) {
        val tasks = graphifyRepo.getRecentTasks(10)

        val tasksJson = tasks.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { task ->
            """{"id":${task.id},"command":"${escapeJson(task.command)}","result":"${escapeJson(task.result)}","timestamp":${task.timestamp}}"""
        }

        writer.println(
            """{"requestId":"${escapeJson(requestId)}","status":"done","result":$tasksJson}"""
        )
    }

    private suspend fun handleProviders(
        writer: PrintWriter,
        requestId: String
    ) {
        val providers = graphifyRepo.getProviderStats()

        val providersJson = providers.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { provider ->
            """{"name":"${escapeJson(provider.name)}","useCount":${provider.useCount},"successRate":${provider.successRate}}"""
        }

        writer.println(
            """{"requestId":"${escapeJson(requestId)}","status":"done","result":$providersJson}"""
        )
    }

    private suspend fun handleMemory(
        writer: PrintWriter,
        requestId: String
    ) {
        val memoryContext = graphifyRepo.buildMemoryContext("")

        writer.println(
            """{"requestId":"${escapeJson(requestId)}","status":"done","result":"${escapeJson(memoryContext)}"}"""
        )
    }

    private suspend fun handleCommand(
        writer: PrintWriter,
        requestId: String,
        cmd: String
    ) {
        val job = scope.launch {
            agentCore.getStateFlow().collect { state ->
                when (state) {
                    is AgentState.Running -> {
                        writer.println(
                            """{"requestId":"${escapeJson(requestId)}","status":"progress","result":"${escapeJson(state.step)}"}"""
                        )
                        writer.flush()
                    }

                    is AgentState.Done -> {
                        writer.println(
                            """{"requestId":"${escapeJson(requestId)}","status":"done","result":"${escapeJson(state.result)}"}"""
                        )
                        writer.flush()
                        cancel()
                    }

                    is AgentState.Error -> {
                        writer.println(
                            """{"requestId":"${escapeJson(requestId)}","status":"error","result":"${escapeJson(state.message)}"}"""
                        )
                        writer.flush()
                        cancel()
                    }

                    is AgentState.Idle -> {
                    }
                }
            }
        }

        try {
            agentCore.executeTask(cmd)
            job.join()
        } finally {
            job.cancel()
        }
    }

    private fun parseRequest(
        line: String
    ): Pair<String, String>? {
        return try {
            val json = org.json.JSONObject(line)

            val cmd = json.optString("cmd", "")
            val requestId = json.optString(
                "requestId",
                ""
            )

            if (cmd.isBlank()) {
                null
            } else {
                Pair(requestId, cmd)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun createErrorResponse(
        requestId: String,
        error: String
    ): String {
        return """{"requestId":"${escapeJson(requestId)}","status":"error","result":"${escapeJson(error)}"}"""
    }

    internal fun escapeJson(
        value: String
    ): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private const val SOCKET_NAME = "jarvis.port"
        private const val MAX_COMMAND_LENGTH = 2000
        private const val MAX_REQUESTS_PER_SECOND = 10
    }
}
