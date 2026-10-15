package org.nhrc.grants.notifications
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.nhrc.grants.operations.OperationalCatalog
import java.util.UUID
class NotificationRouteTest {
 private val jdbc=mock(JdbcTemplate::class.java)
 private val mapper=ObjectMapper()
 private val service=NotificationEmails(jdbc,mapper,mock(OperationalCatalog::class.java),mock(AccountInvitations::class.java),"http://localhost:3000")
 private val id=UUID.randomUUID()
 private fun record(json:String){`when`(jdbc.queryForList(anyString(),eq(id))).thenReturn(listOf(mapOf("data" to json)))}
 @Test fun `approval link selects parent application`(){val app=UUID.randomUUID();record("""{"entity_type":"APPLICATION","entity_id":"$app"}""");val link=service.route("approvals",id)!!;assertEquals(app.toString(),link["record"]);assertEquals("Approvals",link["workspace"])}
 @Test fun `returned review link selects its application`(){val app=UUID.randomUUID();record("""{"application_id":"$app"}""");assertEquals(app.toString(),service.route("REVIEW",id)!!["record"])}
 @Test fun `advance reminder selects exact advance`(){record("{}");val link=service.route("ADVANCE",id)!!;assertEquals(id.toString(),link["record"]);assertEquals("Partner Advances",link["workspace"])}
 @Test fun `handover selects linked award`(){val award=UUID.randomUUID();record("""{"award_id":"$award"}""");assertEquals(award.toString(),service.route("HANDOVER",id)!!["record"])}
 @Test fun `missing and unsupported records have no action link`(){assertNull(service.route("unknown",id));assertNull(service.route("applications",null));assertNull(service.route("partner_advances",id))}
}
