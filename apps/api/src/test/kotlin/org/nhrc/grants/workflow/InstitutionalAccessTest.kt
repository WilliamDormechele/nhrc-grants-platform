package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.nhrc.grants.config.PlatformAccess

class InstitutionalAccessTest {
 @Test fun `superadmin sees every workspace and controls administration without implicit business authority`() {
  val roles=setOf("SUPERADMIN")
  assertEquals(12,PlatformAccess.groups(roles).size)
  assertTrue(PlatformAccess.allowed("/api/finance/summary","GET",roles))
  assertTrue(PlatformAccess.allowed("/api/configuration/forms","PUT",roles))
  assertFalse(PlatformAccess.allowed("/api/approvals/a/decision","POST",roles))
  assertTrue(PlatformAccess.allowed("/api/approvals/a/decision","POST",roles+"DIRECTOR"))
  assertFalse(PlatformAccess.allowed("/api/pregrant/applications/a/submission","POST",roles))
  assertTrue(PlatformAccess.allowed("/api/pregrant/applications/a/submission","POST",roles+"GRANTS_OFFICER"))
 }

 @Test fun `researcher and technical roles cannot record institutional submission or funder outcome`() {
   val paths=listOf("/api/applications", "/api/approvals", "/api/applications/a/outcome", "/api/applications/a/convert-to-award", "/api/pregrant/applications/a/submission", "/api/proposals/a/reviews", "/api/pregrant/quality-checks/a")
   for(path in paths) {
     for(role in listOf("RESEARCHER","POST_AWARD_OFFICER","FINANCE_OFFICER","ADMIN","IT_ADMIN","SUPERADMIN","AUDITOR")) assertFalse(PlatformAccess.allowed(path,"POST",setOf(role)),"$role $path")
     assertTrue(PlatformAccess.allowed(path,"POST",setOf("GRANTS_OFFICER")),path)
   }
 }

 @Test fun `technical administration never grants business approval authority`() {
   for(role in listOf("IT_ADMIN","SUPERADMIN")) {
     assertFalse(PlatformAccess.allowed("/api/finance/receipts","POST",setOf(role)))
     assertFalse(PlatformAccess.allowed("/api/approvals/a/decision","POST",setOf(role)))
     assertTrue(PlatformAccess.allowed("/api/it/backups","GET",setOf(role)))
   }
 }
 @Test fun `auditor is read only`() {
   assertTrue(PlatformAccess.allowed("/api/finance/summary","GET",setOf("AUDITOR")))
   assertFalse(PlatformAccess.allowed("/api/finance/receipts","POST",setOf("AUDITOR")))
 }
 @Test fun `researcher cannot become an institutional decision maker`() {
   assertFalse(PlatformAccess.allowed("/api/approvals/a/decision","POST",setOf("RESEARCHER")))
   assertFalse(PlatformAccess.allowed("/api/admin/audit","GET",setOf("RESEARCHER")))
 }
 @Test fun `finance has read only AI support without grant submission authority`() {
   assertTrue(PlatformAccess.allowed("/api/intelligence/assistant","POST",setOf("FINANCE_OFFICER")))
   assertFalse(PlatformAccess.allowed("/api/pregrant/applications/a/submission","POST",setOf("FINANCE_OFFICER")))
 }
 @Test fun `empty authority does not expose a business workspace`() {
   assertTrue(PlatformAccess.groups(emptySet()).isEmpty())
   assertFalse(PlatformAccess.allowed("/api/people/researchers","GET",emptySet()))
 }
 @Test fun `auditors can acknowledge their own notifications without business write access`() {
  assertTrue(PlatformAccess.allowed("/api/notifications/11111111-1111-1111-1111-111111111111/read","POST",setOf("AUDITOR")))
  assertFalse(PlatformAccess.allowed("/api/finance/receipts","POST",setOf("AUDITOR")))
 }
}
