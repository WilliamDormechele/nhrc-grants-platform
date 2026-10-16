package org.nhrc.grants.workflow
import org.nhrc.grants.intelligence.RegisterAssistant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class RegisterAssistantTest {
 @Test fun `scan explains discovery without inventing live results`() {
  val a=RegisterAssistant.answer("scan for grants",listOf(mapOf("id" to "S3","label" to "Funding","evidence" to emptyList<Any>())))
  assertTrue(a.contains("does not search the internet"));assertTrue(a.contains("discovery controls"));assertTrue(a.contains("No records"));assertFalse(a.contains("grant found"))
 }
 @Test fun `retains currency separate amounts and uncertainty`() {
  val a=RegisterAssistant.answer("financial risks",listOf(mapOf("id" to "S2","label" to "Forecasts","evidence" to listOf(mapOf("title" to "Alpha","currency" to "GBP","projection" to 300,"status" to "INSUFFICIENT_HISTORY")))))
  assertTrue(a.contains("currency: GBP"));assertTrue(a.contains("projection: 300"));assertTrue(a.contains("INSUFFICIENT HISTORY"));assertTrue(a.contains("[S2]"));assertFalse(a.contains("USD"))
 }
 @Test fun `source text is presented as data and coverage is bounded`() {
  val a=RegisterAssistant.answer("deadlines",listOf(mapOf("id" to "S1","label" to "Actions","evidence" to (1..9).map {mapOf("title" to "Task $it","due_date" to "2026-10-16")})))
  assertTrue(a.contains("Task 5"));assertFalse(a.contains("Task 6"));assertTrue(a.contains("Showing 5 of 9"));assertTrue(a.contains("no generative AI model"))
 }
}
