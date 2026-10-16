package org.nhrc.grants.intelligence

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object IntelligenceRules {
    fun severity(due: LocalDate, today: LocalDate, urgentDays: Int): String =
        if (due.isBefore(today)) "OVERDUE" else if (ChronoUnit.DAYS.between(today, due) <= urgentDays) "URGENT" else "UPCOMING"

    fun forecast(budget: BigDecimal, expenditure: BigDecimal, commitments: BigDecimal, start: LocalDate?, end: LocalDate?, today: LocalDate): Map<String, Any?> {
        val remaining = budget - expenditure - commitments
        if (start == null || end == null || !end.isAfter(start) || !today.isAfter(start))
            return mapOf("availableBudget" to remaining, "projection" to null, "variance" to null, "status" to "INSUFFICIENT_HISTORY")
        val elapsed = ChronoUnit.DAYS.between(start, today).coerceAtLeast(1)
        val future = ChronoUnit.DAYS.between(today, end).coerceAtLeast(0)
        val projected = expenditure + expenditure.multiply(BigDecimal(future)).divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP) + commitments
        return mapOf("availableBudget" to remaining, "projection" to projected, "variance" to budget - projected,
            "status" to if (remaining < BigDecimal.ZERO || projected > budget) "OVERSPEND_RISK" else "WITHIN_BUDGET")
    }
}
