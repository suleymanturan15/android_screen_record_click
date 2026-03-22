package com.timemacro.scheduler.domain.scheduler

import com.timemacro.scheduler.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Bir Task için “bir sonraki çalıştırma zamanı”nı üreten planlayıcı.
 */
interface TaskPlanner {
    /**
     * @param nowEpochMs "şimdi" zamanı
     * @param lastScheduledOrLastRunEpochMs Son planlanan veya son çalıştırma zamanı (varsa).
     */
    fun computeNextRun(
        nowEpochMs: Long,
        task: Task,
        lastScheduledOrLastRunEpochMs: Long?,
        macroDurationMs: Long?,
    ): NextRunResult
}

data class NextRunResult(
    val nextRunAtEpochMs: Long?,
    val reason: String? = null,
)

class DefaultTaskPlanner(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : TaskPlanner {

    override fun computeNextRun(
        nowEpochMs: Long,
        task: Task,
        lastScheduledOrLastRunEpochMs: Long?,
        macroDurationMs: Long?,
    ): NextRunResult {
        if (!task.active) return NextRunResult(nextRunAtEpochMs = null, reason = "inactive")

        val now = Instant.ofEpochMilli(nowEpochMs)
        val planEndsAt = task.planEndsAt
        if (planEndsAt != null && now.isAfter(planEndsAt)) {
            return NextRunResult(nextRunAtEpochMs = null, reason = "expired")
        }

        val nowLocal = LocalDateTime.ofInstant(now, zoneId)
        val runsPerDay = task.runsPerDay.coerceIn(1, 24)
        val initialDelayMs = task.initialDelayMinutes.coerceAtLeast(0) * 60_000L

        fun firstEpochForDate(date: LocalDate): Long {
            return LocalDateTime.of(date, task.startTime).atZone(zoneId).toInstant().toEpochMilli() + initialDelayMs
        }

        fun computeWithinDate(date: LocalDate): Long? {
            val firstEpoch = firstEpochForDate(date)
            val last = lastScheduledOrLastRunEpochMs

            // If we haven't run at least once in this "day window", we only allow the very first slot.
            // If that slot is already in the past, we skip to the next plan occurrence (no catch-up runs).
            if (last == null || last < firstEpoch) {
                return if (nowEpochMs <= firstEpoch) firstEpoch else null
            }

            val intervalMode = task.intervalMode
            return when (intervalMode) {
                "FIXED_INTERVAL" -> {
                    val intervalMin = (task.fixedIntervalMinutes ?: 0).coerceAtLeast(1)
                    val intervalMs = intervalMin * 60_000L

                    val minCandidate = maxOf(nowEpochMs, last + intervalMs)
                    val steps = ceil((minCandidate - firstEpoch).toDouble() / intervalMs.toDouble()).toLong().coerceAtLeast(0L)
                    val candidate = firstEpoch + steps * intervalMs
                    val runIndex = steps + 1 // 1-based

                    if (runIndex <= runsPerDay) candidate else null
                }

                "MACRO_BASED" -> {
                    val extraDelayMs = (task.extraDelayMinutes ?: 0).coerceAtLeast(0) * 60_000L
                    val macroMs = (macroDurationMs ?: 0L).coerceAtLeast(0L)
                    val cycleMs = (macroMs + extraDelayMs).coerceAtLeast(1_000L)

                    val minCandidate = maxOf(nowEpochMs, last + cycleMs)
                    val steps = ceil((minCandidate - firstEpoch).toDouble() / cycleMs.toDouble()).toLong().coerceAtLeast(0L)
                    val candidate = firstEpoch + steps * cycleMs
                    val runIndex = steps + 1

                    if (runIndex <= runsPerDay) candidate else null
                }

                else -> null
            }
        }

        fun nextDailyDate(from: LocalDate): LocalDate = from.plusDays(1)

        fun nextMonthlyDate(from: LocalDate): LocalDate {
            val desiredDay = (task.monthlyDay ?: 1).coerceIn(1, 31)
            val thisMonth = from.withDayOfMonth(1)
            fun dateInMonth(base: LocalDate): LocalDate {
                val lastDay = base.lengthOfMonth()
                return base.withDayOfMonth(desiredDay.coerceAtMost(lastDay))
            }

            val candidate = dateInMonth(thisMonth)
            val firstEpoch = firstEpochForDate(candidate)
            val okForThisMonth =
                when {
                    from.isBefore(candidate) -> true
                    from.isEqual(candidate) && nowEpochMs <= firstEpoch -> true
                    else -> false
                }
            return if (okForThisMonth) candidate else dateInMonth(thisMonth.plusMonths(1))
        }

        fun nextYearlyDate(from: LocalDate): LocalDate {
            val m = (task.yearlyMonth ?: 1).coerceIn(1, 12)
            val d = (task.yearlyDay ?: 1).coerceIn(1, 31)

            fun safeDate(year: Int): LocalDate {
                val base = LocalDate.of(year, m, 1)
                val lastDay = base.lengthOfMonth()
                return LocalDate.of(year, m, d.coerceAtMost(lastDay))
            }

            val candidate = safeDate(from.year)
            val firstEpoch = firstEpochForDate(candidate)
            val okForThisYear =
                when {
                    from.isBefore(candidate) -> true
                    from.isEqual(candidate) && nowEpochMs <= firstEpoch -> true
                    else -> false
                }
            return if (okForThisYear) candidate else safeDate(from.year + 1)
        }

        val planType = task.planType
        val today = nowLocal.toLocalDate()

        // Compute next date (DAILY / MONTHLY / YEARLY) and within-day slot (runsPerDay).
        val maxHops = 24 // safety
        var date =
            when (planType) {
                "DAILY" -> today
                "MONTHLY" -> nextMonthlyDate(today)
                "YEARLY" -> nextYearlyDate(today)
                else -> return NextRunResult(nextRunAtEpochMs = null, reason = "unknown_plan_type")
            }

        repeat(maxHops) {
            val candidate = computeWithinDate(date)
            if (candidate != null) {
                // If planEndsAt exists, clamp to it.
                if (planEndsAt != null && candidate > planEndsAt.toEpochMilli()) {
                    return NextRunResult(nextRunAtEpochMs = null, reason = "expired")
                }
                return NextRunResult(nextRunAtEpochMs = candidate, reason = null)
            }

            date =
                when (planType) {
                    "DAILY" -> nextDailyDate(date)
                    "MONTHLY" -> nextMonthlyDate(date.plusDays(1))
                    "YEARLY" -> nextYearlyDate(date.plusDays(1))
                    else -> return NextRunResult(nextRunAtEpochMs = null, reason = "unknown_plan_type")
                }
        }

        return NextRunResult(nextRunAtEpochMs = null, reason = "no_next_run")
    }
}

