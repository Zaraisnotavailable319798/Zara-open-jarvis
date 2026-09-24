package com.openjarvis.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {

    private val db = AutomationDB.getInstance(context)
    private val dao = db.automationDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _automationsFlow =
        MutableStateFlow<List<Automation>>(emptyList())

    val automationsFlow: StateFlow<List<Automation>> =
        _automationsFlow

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    suspend fun loadAutomations() {
        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }
    }

    // ---------------------------------------------------------
    // CREATE
    // ---------------------------------------------------------

    suspend fun createAutomation(
        automation: Automation
    ): String = withContext(Dispatchers.IO) {

        dao.insertEntity(automation.toEntity())

        if (automation.enabled) {
            scheduleAutomation(automation)
        }

        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }

        automation.id
    }

    // ---------------------------------------------------------
    // UPDATE
    // ---------------------------------------------------------

    suspend fun updateAutomation(
        automation: Automation
    ) = withContext(Dispatchers.IO) {

        dao.updateEntity(automation.toEntity())

        cancelAutomation(automation.id)

        if (automation.enabled) {
            scheduleAutomation(automation)
        }

        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }
    }

    // ---------------------------------------------------------
    // DELETE
    // ---------------------------------------------------------

    suspend fun deleteAutomation(
        id: String
    ) = withContext(Dispatchers.IO) {

        cancelAutomation(id)

        dao.delete(id)

        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }
    }

    // ---------------------------------------------------------
    // ENABLE / DISABLE
    // ---------------------------------------------------------

    suspend fun toggleAutomation(
        id: String,
        enabled: Boolean
    ) = withContext(Dispatchers.IO) {

        val entity = dao.getEntityById(id)
            ?: return@withContext

        val updated = entity.copy(
            enabled = enabled
        )

        dao.updateEntity(updated)

        if (enabled) {
            scheduleAutomation(updated.toAutomation())
        } else {
            cancelAutomation(id)
        }

        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }
    }

    // ---------------------------------------------------------
    // RUN NOW
    // ---------------------------------------------------------

    suspend fun runNow(
        id: String
    ) = withContext(Dispatchers.IO) {

        val entity = dao.getEntityById(id)
            ?: return@withContext

        executeAutomation(entity.toAutomation())
    }

    // ---------------------------------------------------------
    // SCHEDULING
    // ---------------------------------------------------------

    private suspend fun scheduleAutomation(
        automation: Automation
    ) {

        val workManager = WorkManager.getInstance(context)

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val inputData = workDataOf(
            "automation_id" to automation.id,
            "automation_command" to automation.command
        )

        when (val schedule = automation.schedule) {

            is AutomationSchedule.Daily -> {

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        24,
                        TimeUnit.HOURS,
                        15,
                        TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateDelay(
                                schedule.hour,
                                schedule.minute
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Weekly -> {

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        7,
                        TimeUnit.DAYS,
                        15,
                        TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateWeeklyDelay(
                                schedule.dayOfWeek,
                                schedule.hour,
                                schedule.minute
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Interval -> {

                val interval = maxOf(
                    schedule.intervalMs,
                    TimeUnit.MINUTES.toMillis(15)
                )

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        interval,
                        TimeUnit.MILLISECONDS,
                        15,
                        TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Once -> {

                val delay =
                    schedule.atMs - System.currentTimeMillis()

                if (delay <= 0) {
                    return
                }

                val request =
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            delay,
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniqueWork(
                    automation.id,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }

    // ---------------------------------------------------------
    // CANCEL
    // ---------------------------------------------------------

    private fun cancelAutomation(
        id: String
    ) {
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(id)
    }

    // ---------------------------------------------------------
    // EXECUTE
    // ---------------------------------------------------------

    private suspend fun executeAutomation(
        automation: Automation
    ) {

        val result = try {

            // Actual command execution can be connected
            // to AgentCore later.
            //
            // For now we record the automation run.

            "success"

        } catch (e: Exception) {

            "error: ${e.message}"
        }

        val updated = automation.copy(
            lastRun = System.currentTimeMillis(),
            lastResult = result,
            runCount = automation.runCount + 1
        )

        dao.updateEntity(updated.toEntity())

        _automationsFlow.value =
            dao.getAllEntities().map { it.toAutomation() }
    }

    // ---------------------------------------------------------
    // DAILY DELAY
    // ---------------------------------------------------------

    private fun calculateDelay(
        targetHour: Int,
        targetMinute: Int
    ): Long {

        val now = Calendar.getInstance()

        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return target.timeInMillis - now.timeInMillis
    }

    // ---------------------------------------------------------
    // WEEKLY DELAY
    // ---------------------------------------------------------

    private fun calculateWeeklyDelay(
        targetDayOfWeek: Int,
        targetHour: Int,
        targetMinute: Int
    ): Long {

        val now = Calendar.getInstance()

        val target = Calendar.getInstance().apply {

            set(Calendar.DAY_OF_WEEK, targetDayOfWeek)
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            while (timeInMillis <= now.timeInMillis) {
                add(Calendar.WEEK_OF_YEAR, 1)
            }
        }

        return target.timeInMillis - now.timeInMillis
    }

    // ---------------------------------------------------------
    // NATURAL LANGUAGE SCHEDULE PARSER
    // ---------------------------------------------------------

    fun parseSchedule(
        input: String
    ): AutomationSchedule? {

        val lower = input
            .trim()
            .lowercase()

        // every day at 8
        // every day at 8:30
        // every day at 8 pm
        // every day at 8:30 pm

        val dailyMatch = Regex(
            """every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (dailyMatch != null) {

            var hour =
                dailyMatch.groupValues[1].toInt()

            val minute =
                dailyMatch.groupValues[2]
                    .takeIf { it.isNotBlank() }
                    ?.toInt()
                    ?: 0

            val amPm =
                dailyMatch.groupValues[3]

            if (!amPm.isNullOrBlank()) {

                when (amPm.lowercase()) {

                    "am" -> {
                        if (hour == 12) {
                            hour = 0
                        }
                    }

                    "pm" -> {
                        if (hour != 12) {
                            hour += 12
                        }
                    }
                }
            }

            if (hour in 0..23 && minute in 0..59) {
                return AutomationSchedule.Daily(
                    hour,
                    minute
                )
            }
        }

        // every monday at 8 pm
        val weeklyMatch = Regex(
            """every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (weeklyMatch != null) {

            val dayName =
                weeklyMatch.groupValues[1].lowercase()

            var hour =
                weeklyMatch.groupValues[2].toInt()

            val minute =
                weeklyMatch.groupValues[3]
                    .takeIf { it.isNotBlank() }
                    ?.toInt()
                    ?: 0

            val amPm =
                weeklyMatch.groupValues[4]

            if (!amPm.isNullOrBlank()) {

                when (amPm.lowercase()) {

                    "am" -> {
                        if (hour == 12) {
                            hour = 0
                        }
                    }

                    "pm" -> {
                        if (hour != 12) {
                            hour += 12
                        }
                    }
                }
            }

            val dayOfWeek = when (dayName) {

                "sunday" -> Calendar.SUNDAY
                "monday" -> Calendar.MONDAY
                "tuesday" -> Calendar.TUESDAY
                "wednesday" -> Calendar.WEDNESDAY
                "thursday" -> Calendar.THURSDAY
                "friday" -> Calendar.FRIDAY
                "saturday" -> Calendar.SATURDAY

                else -> return null
            }

            if (hour in 0..23 && minute in 0..59) {

                return AutomationSchedule.Weekly(
                    dayOfWeek,
                    hour,
                    minute
                )
            }
        }

        // every 5 minutes
        // every 2 hours
        // every 1 day

        val intervalMatch = Regex(
            """every\s+(\d+)\s*(minute|minutes|hour|hours|day|days)""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (intervalMatch != null) {

            val amount =
                intervalMatch.groupValues[1].toLong()

            val unit =
                intervalMatch.groupValues[2]
                    .lowercase()

            val intervalMs = when {

                unit.startsWith("minute") ->
                    TimeUnit.MINUTES.toMillis(amount)

                unit.startsWith("hour") ->
                    TimeUnit.HOURS.toMillis(amount)

                unit.startsWith("day") ->
                    TimeUnit.DAYS.toMillis(amount)

                else -> return null
            }

            return AutomationSchedule.Interval(
                intervalMs
            )
        }

        // once at unix timestamp
        val onceMatch = Regex(
            """(?:once|at)\s+(\d{10,})"""
        ).find(lower)

        if (onceMatch != null) {

            val timestamp =
                onceMatch.groupValues[1].toLongOrNull()

            if (timestamp != null) {
                return AutomationSchedule.Once(timestamp)
            }
        }

        return null
    }

    // ---------------------------------------------------------
    // DATA MODELS
    // ---------------------------------------------------------

    data class Automation(
        val id: String,
        val name: String,
        val command: String,
        val schedule: AutomationSchedule,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val lastResult: String? = null,
        val runCount: Int = 0
    )

    sealed class AutomationSchedule {

        data class Daily(
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Weekly(
            val dayOfWeek: Int,
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Interval(
            val intervalMs: Long
        ) : AutomationSchedule()

        data class Once(
            val atMs: Long
        ) : AutomationSchedule()
    }

    // ---------------------------------------------------------
    // ENTITY -> MODEL
    // ---------------------------------------------------------

    private fun AutomationEntity.toAutomation(): Automation {

        val schedule: AutomationSchedule = when (scheduleType) {

            "daily" -> {
                AutomationSchedule.Daily(
                    hour = scheduleHour,
                    minute = scheduleMinute
                )
            }

            "weekly" -> {
                AutomationSchedule.Weekly(
                    dayOfWeek = scheduleDayOfWeek,
                    hour = scheduleHour,
                    minute = scheduleMinute
                )
            }

            "interval" -> {
                AutomationSchedule.Interval(
                    intervalMs = scheduleIntervalMs
                )
            }

            "once" -> {
                AutomationSchedule.Once(
                    atMs = scheduleAtMs
                )
            }

            else -> {
                AutomationSchedule.Daily(
                    hour = scheduleHour,
                    minute = scheduleMinute
                )
            }
        }

        return Automation(
            id = id,
            name = name,
            command = command,
            schedule = schedule,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }

    // ---------------------------------------------------------
    // MODEL -> ENTITY
    // ---------------------------------------------------------

    private fun Automation.toEntity(): AutomationEntity {

        var scheduleType = "daily"
        var hour = 0
        var minute = 0
        var dayOfWeek = 0
        var intervalMs = 0L
        var atMs = 0L

        when (val currentSchedule = schedule) {

            is AutomationSchedule.Daily -> {

                scheduleType = "daily"
                hour = currentSchedule.hour
                minute = currentSchedule.minute
            }

            is AutomationSchedule.Weekly -> {

                scheduleType = "weekly"
                dayOfWeek = currentSchedule.dayOfWeek
                hour = currentSchedule.hour
                minute = currentSchedule.minute
            }

            is AutomationSchedule.Interval -> {

                scheduleType = "interval"
                intervalMs = currentSchedule.intervalMs
            }

            is AutomationSchedule.Once -> {

                scheduleType = "once"
                atMs = currentSchedule.atMs
            }
        }

        return AutomationEntity(
            id = id,
            name = name,
            command = command,
            scheduleType = scheduleType,
            scheduleHour = hour,
            scheduleMinute = minute,
            scheduleDayOfWeek = dayOfWeek,
            scheduleIntervalMs = intervalMs,
            scheduleAtMs = atMs,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }
}
