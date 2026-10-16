package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.nhrc.grants.intelligence.IntelligenceRules
import java.math.BigDecimal
import java.time.LocalDate

class IntelligenceRulesTest {
 private val today=LocalDate.of(2026,10,14)
 @Test fun `due today and urgent boundary require action`() {
   assertEquals("OVERDUE",IntelligenceRules.severity(today.minusDays(1),today,7))
   assertEquals("URGENT",IntelligenceRules.severity(today,today,7))
   assertEquals("URGENT",IntelligenceRules.severity(today.plusDays(7),today,7))
   assertEquals("UPCOMING",IntelligenceRules.severity(today.plusDays(8),today,7))
 }
 @Test fun `forecast includes future spend and commitments once`() {
   val f=IntelligenceRules.forecast(BigDecimal("1000"),BigDecimal("400"),BigDecimal("100"),today.minusDays(10),today.plusDays(20),today)
   assertEquals(BigDecimal("1300.00"),f["projection"])
   assertEquals(BigDecimal("-300.00"),f["variance"])
   assertEquals(BigDecimal("500"),f["availableBudget"])
   assertEquals("OVERSPEND_RISK",f["status"])
 }
 @Test fun `missing or future history does not invent forecast`() {
   for(start in listOf(null,today,today.plusDays(2))) {
     val f=IntelligenceRules.forecast(BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ZERO,start,today.plusDays(20),today)
     assertNull(f["projection"]); assertEquals("INSUFFICIENT_HISTORY",f["status"])
   }
 }
 @Test fun `ended award does not project negative future spending`() {
   val f=IntelligenceRules.forecast(BigDecimal("1000"),BigDecimal("400"),BigDecimal("100"),today.minusDays(20),today.minusDays(1),today)
   assertEquals(BigDecimal("500.00"),f["projection"])
 }
}
