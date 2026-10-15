package org.nhrc.grants.notifications

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

class EmailOnboardingTest {
 @TempDir lateinit var directory:Path
 private fun content()=BrandedEmail("NHRC eGMS | Review","Application review","Hello Researcher,","Review the submitted application.",listOf("Application" to "APP-UAT-001","Deadline" to "15 Oct 2026"),"Review the evidence and record your assigned decision.","View this notification","http://localhost:3000/?notification=11111111-1111-1111-1111-111111111111","http://localhost:3000")
 @Test fun `email has branded accessible HTML and complete plain text alternative`() {
  val r=EmailTemplates.render(content());assertTrue(r.html.contains("cid:nhrc-logo"));assertTrue(r.html.contains("<html lang=\"en\">"));assertTrue(r.html.contains("scope=\"row\""));assertTrue(r.html.contains("What to do next"));assertTrue(r.html.contains("APP-UAT-001"));assertTrue(r.plain.contains(content().actionUrl));assertTrue(r.plain.contains("Deadline: 15 Oct 2026"));assertFalse(r.html.contains("<script"));assertFalse(r.html.contains("https://fonts"))
 }
 @Test fun `untrusted email text is escaped and subject headers cannot be injected`() {
  val r=EmailTemplates.render(content().copy(subject="Update\r\nBcc: outsider@example.test",heading="<script>alert(1)</script>",message="<img src=x onerror='alert(1)'> & evidence",details=listOf("<b>Record</b>" to "<iframe>")))
  assertFalse(r.html.contains("<script>"));assertFalse(r.html.contains("<iframe>"));assertTrue(r.html.contains("&lt;script&gt;"));assertTrue(r.html.contains("&amp; evidence"));assertFalse(r.subject.contains('\n'));assertFalse(r.subject.contains('\r'))
 }
 @Test fun `unsafe action URL schemes are rejected`() {assertThrows(IllegalArgumentException::class.java){EmailTemplates.render(content().copy(actionUrl="javascript:alert(1)"))}}
 @Test fun `dates show institutional UK timezone and accept JDBC timestamps`() {val dt=OffsetDateTime.parse("2026-10-15T10:00:00Z");assertTrue(EmailTemplates.timestamp(dt).contains("11:00 BST"));assertEquals(EmailTemplates.timestamp(dt),EmailTemplates.timestamp(java.sql.Timestamp.from(dt.toInstant())))}
 @Test fun `invitation stores only a hash and authenticated encrypted token`() {
  val jdbc=Mockito.mock(JdbcTemplate::class.java);val encoder=Mockito.mock(PasswordEncoder::class.java);val service=AccountInvitations(jdbc,encoder,directory.toString());service.issue(UUID.randomUUID(),UUID.randomUUID())
  val calls=Mockito.mockingDetails(jdbc).invocations
  val insert=calls.first{it.arguments[0].toString().startsWith("insert into account_invitations")};val args=insert.arguments
  val encrypted=args[4].toString();val token=MailSecretVault(directory).decrypt(encrypted)
  assertEquals(43,token.length);assertEquals(AccountInvitations.hash(token),args[3]);assertNotEquals(token,encrypted)
  val notification=calls.first{it.arguments[0].toString().startsWith("insert into notifications")};assertTrue(notification.arguments.none{it.toString().contains(token)})
 }
 @Test fun `password length respects passphrases and bcrypt byte boundary`() {assertFalse(AccountInvitations.validPassword("short"));assertTrue(AccountInvitations.validPassword("A memorable passphrase 2026"));assertFalse(AccountInvitations.validPassword("é".repeat(37)));assertTrue(AccountInvitations.validPassword("é".repeat(36)))}
 @Test fun `malformed and unknown invitation tokens are rejected without encoding passwords`() {
  val jdbc=Mockito.mock(JdbcTemplate::class.java);val encoder=Mockito.mock(PasswordEncoder::class.java);val service=AccountInvitations(jdbc,encoder,directory.toString())
  assertThrows(IllegalArgumentException::class.java){service.inspect("invalid")};Mockito.verifyNoInteractions(jdbc)
  assertThrows(ResponseStatusException::class.java){service.accept("x".repeat(43),"A valid test passphrase")};Mockito.verifyNoInteractions(encoder)
 }
}
