package com.openjarvis.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openjarvis.R
import com.openjarvis.agent.AgentCore
import com.openjarvis.bridge.SocketServer
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.graphify.nodes.TaskNode
import com.openjarvis.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class OverlayService : Service() {

    private var agentCore: AgentCore? = null
    private var voiceManager: VoiceManager? = null
    private var graphifyRepo: GraphifyRepository? = null
    private var socketServer: SocketServer? = null

    private var stateCollectJob: Job? = null
    private var serviceJob: Job? = null

    @Volatile
    private var recentTasks: List<TaskNode> = emptyList()

    @Volatile
    private var isInitialized = false

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    override fun onCreate() {
        super.onCreate()

        agentCore = AgentCore(this)
        voiceManager = VoiceManager(this)
        graphifyRepo = GraphifyRepository(this)

        val core = agentCore
        val repository = graphifyRepo

        if (core != null && repository != null) {
            socketServer = SocketServer(
                this,
                core,
                repository
            )
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        try {
            socketServer?.start()
        } catch (_: Exception) {
        }

        serviceJob = serviceScope.launch {
            initializeServices()
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                recentTasks = repository?.getRecentTasks(10).orEmpty()
            } catch (_: Exception) {
                recentTasks = emptyList()
            }
        }
    }

    private suspend fun initializeServices() {
        if (isInitialized) return

        try {
            graphifyRepo?.getRecentTasks(10)
        } catch (_: Exception) {
        }

        isInitialized = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateCollectJob?.cancel()
        serviceJob?.cancel()

        try {
            socketServer?.stop()
        } catch (_: Exception) {
        }

        try {
            voiceManager?.release()
        } catch (_: Exception) {
        }

        serviceScope.cancel()

        socketServer = null
        voiceManager = null
        graphifyRepo = null
        agentCore = null

        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                getString(R.string.notification_channel_description)
        }

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                getString(R.string.notification_title)
            )
            .setContentText(
                getString(R.string.notification_text)
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun getAgentCore(): AgentCore? = agentCore

    fun getVoiceManager(): VoiceManager? = voiceManager

    fun getRecentTasks(): List<TaskNode> = recentTasks

    fun executeCommand(command: String) {
        val core = agentCore ?: return
        val repository = graphifyRepo ?: return

        serviceScope.launch {
            try {
                core.executeTask(command)

                delay(1000)

                recentTasks = repository.getRecentTasks(10)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_overlay_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(
                context,
                OverlayService::class.java
            )

            context.startForegroundService(intent)
        }
    }
}
