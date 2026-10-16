package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class ApplicationDecisionsTest {
 private val id=UUID.randomUUID()
 private fun gate(latest:String?,type:String="INSTITUTIONAL",earlierApproved:Boolean=false):Boolean {
  val jdbc=mock(JdbcTemplate::class.java)
  val rows=mutableListOf<Map<String,Any>>()
  if(latest!=null)rows.add(mapOf("status" to latest))
  if(earlierApproved)rows.add(mapOf("status" to "APPROVED"))
  `when`(jdbc.queryForList(anyString(),eq(id),eq(type))).thenReturn(rows)
  return ApplicationDecisions.approved(jdbc,id,type)
 }
 @Test fun `later return supersedes earlier authorisation`() {assertFalse(gate("RETURNED",earlierApproved=true))}
 @Test fun `later rejection supersedes earlier authorisation`() {assertFalse(gate("REJECTED",earlierApproved=true))}
 @Test fun `pending reconsideration blocks older authorisation`() {assertFalse(gate("PENDING",earlierApproved=true))}
 @Test fun `no decision does not authorise submission`() {assertFalse(gate(null))}
 @Test fun `current named approval authorises its gate`() {assertTrue(gate("APPROVED"));assertTrue(gate("APPROVED","DIRECTOR_DECISION"))}
 private fun combined(vararg states:String):Boolean {
  val jdbc=mock(JdbcTemplate::class.java)
  `when`(jdbc.queryForList(anyString(),eq(id))).thenReturn(states.map {mapOf<String,Any>("status" to it)})
  return ApplicationDecisions.allApproved(jdbc,id)
 }
 @Test fun `finance return blocks despite institutional approval`() {assertFalse(combined("APPROVED","RETURNED"))}
 @Test fun `finance rejection blocks despite institutional approval`() {assertFalse(combined("APPROVED","REJECTED"))}
 @Test fun `pending finance clearance blocks despite institutional approval`() {assertFalse(combined("APPROVED","PENDING"))}
 @Test fun `all current decision types must be approved`() {assertTrue(combined("APPROVED","APPROVED","APPROVED"))}
 @Test fun `empty decision set is not authorised`() {assertFalse(combined())}
}
