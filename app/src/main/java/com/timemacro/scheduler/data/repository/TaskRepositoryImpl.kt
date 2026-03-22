package com.timemacro.scheduler.data.repository

import com.timemacro.scheduler.data.db.dao.TaskDao
import com.timemacro.scheduler.data.db.entities.TaskEntity
import com.timemacro.scheduler.domain.model.Task
import com.timemacro.scheduler.domain.repository.TaskRepository
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TaskRepositoryImpl(
    private val taskDao: TaskDao,
) : TaskRepository {

    override suspend fun getById(id: String): Task? {
        return taskDao.getById(id)?.toDomain()
    }

    override suspend fun listAll(): List<Task> {
        return taskDao.listAll().map { it.toDomain() }
    }

    override suspend fun listActive(): List<Task> {
        return taskDao.listActive().map { it.toDomain() }
    }

    override suspend fun upsert(task: Task) {
        val now = System.currentTimeMillis()
        val existing = taskDao.getById(task.id)
        val createdAt = existing?.createdAt ?: now
        taskDao.insert(task.toEntity(createdAt = createdAt, updatedAt = now))
    }

    override suspend fun update(task: Task) {
        // Domain model doesn't include createdAt/updatedAt; safest is to preserve createdAt via upsert().
        upsert(task)
    }

    override suspend fun updateScheduleState(
        taskId: String,
        nextScheduledAtEpochMs: Long?,
        lastScheduledAtEpochMs: Long?,
        lastRunAtEpochMs: Long?,
    ) {
        val now = System.currentTimeMillis()
        taskDao.updateScheduleState(
            taskId = taskId,
            nextScheduledAt = nextScheduledAtEpochMs,
            lastScheduledAt = lastScheduledAtEpochMs,
            lastRunAt = lastRunAtEpochMs,
            updatedAt = now,
        )
    }

    override suspend fun delete(id: String) {
        taskDao.delete(id)
    }
}

private val HHmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun TaskEntity.toDomain(): Task =
    Task(
        id = id,
        macroId = macroId,
        startTime = runCatching { LocalTime.parse(startTimeHHmm, HHmm) }.getOrDefault(LocalTime.MIDNIGHT),
        runsPerDay = runsPerDay.coerceIn(1, 24),
        planType = planType,
        intervalMode = intervalMode,
        fixedIntervalMinutes = fixedIntervalMinutes,
        extraDelayMinutes = extraDelayMinutes,
        initialDelayMinutes = initialDelayMinutes,
        monthlyDay = monthlyDay,
        yearlyMonth = yearlyMonth,
        yearlyDay = yearlyDay,
        planEndsAt = planEndsAt?.let { Instant.ofEpochMilli(it) },
        nextScheduledAt = nextScheduledAt?.let { Instant.ofEpochMilli(it) },
        lastScheduledAt = lastScheduledAt?.let { Instant.ofEpochMilli(it) },
        lastRunAt = lastRunAt?.let { Instant.ofEpochMilli(it) },
        active = active,
    )

private fun Task.toEntity(createdAt: Long, updatedAt: Long): TaskEntity =
    TaskEntity(
        id = id,
        macroId = macroId,
        startTimeHHmm = startTime.format(HHmm),
        dailyHoursToRun = runsPerDay.coerceIn(1, 24), // legacy column (kept for backward-compat)
        runsPerDay = runsPerDay.coerceIn(1, 24),
        planType = planType,
        intervalMode = intervalMode,
        fixedIntervalMinutes = fixedIntervalMinutes,
        extraDelayMinutes = extraDelayMinutes,
        initialDelayMinutes = initialDelayMinutes,
        monthlyDay = monthlyDay,
        yearlyMonth = yearlyMonth,
        yearlyDay = yearlyDay,
        planEndsAt = planEndsAt?.toEpochMilli(),
        nextScheduledAt = nextScheduledAt?.toEpochMilli(),
        lastScheduledAt = lastScheduledAt?.toEpochMilli(),
        lastRunAt = lastRunAt?.toEpochMilli(),
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

