package org.nhrc.grants.workflow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import org.springframework.jdbc.core.JdbcTemplate
import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.notifications.NotificationEmails
import org.nhrc.grants.notifications.AccountInvitations
import org.nhrc.grants.operations.OperationalCatalog
import org.nhrc.grants.config.PlatformAccess
import java.util.UUID
class MatchedFundingTest {
 @Test fun `matched opportunity links to personal funding with exact id`() {
  val jdbc=mock(JdbcTemplate::class.java);val id=UUID.randomUUID();val user=UUID.randomUUID()
  `when`(jdbc.queryForObject(anyString(),eq(Boolean::class.java),eq(id),eq(user))).thenReturn(true)
  val emails=NotificationEmails(jdbc,ObjectMapper(),mock(OperationalCatalog::class.java),mock(AccountInvitations::class.java),"https://example.org")
  val route=emails.route("opportunities",id,user)!!
  assertEquals("Personal",route["group"]);assertEquals("Funding Matches",route["workspace"]);assertEquals(id.toString(),route["record"])
 }
 @Test fun `superadmin may manage technical automation without business approval authority`() {
  assertTrue(PlatformAccess.allowed("/api/intelligence/automation/settings","PUT",setOf("SUPERADMIN")))
  assertTrue(PlatformAccess.allowed("/api/intelligence/automation/run","POST",setOf("SUPERADMIN")))
  assertFalse(PlatformAccess.allowed("/api/approvals/00000000-0000-0000-0000-000000000001/decision","POST",setOf("SUPERADMIN")))
 }
 @Test fun `research fellow may read personal matches without application access`() {
  assertTrue(PlatformAccess.allowed("/api/personal/funding-matches","GET",setOf("RESEARCH_FELLOW")))
  assertFalse(PlatformAccess.allowed("/api/applications","GET",setOf("RESEARCH_FELLOW")))
 }
}
