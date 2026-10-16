package org.nhrc.grants.workflow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.env.MockEnvironment
import org.nhrc.grants.discovery.OpportunityDiscoveryController
import org.nhrc.grants.discovery.OpportunityDiscoveryService
import java.time.OffsetDateTime
class DiscoveryScheduleTest {
 @Test fun `enabled schedule reports next real quarter hour`() {
  val result=OpportunityDiscoveryController(mock(OpportunityDiscoveryService::class.java),MockEnvironment()).schedule()
  assertEquals(true,result["enabled"]);assertEquals("Every 15 minutes",result["label"])
  val next=OffsetDateTime.parse(result["nextRunAt"].toString());assertEquals(0,next.second);assertEquals(0,next.minute%15);assertTrue(next.isAfter(OffsetDateTime.now()))
 }
 @Test fun `disabled deployment does not advertise next scan`() {
  val result=OpportunityDiscoveryController(mock(OpportunityDiscoveryService::class.java),MockEnvironment().withProperty("nhrc.discovery.enabled","false")).schedule()
  assertEquals(false,result["enabled"]);assertNull(result["nextRunAt"])
 }
 @Test fun `custom schedule calculates actual next run rather than claiming quarter hourly`() {
  val result=OpportunityDiscoveryController(mock(OpportunityDiscoveryService::class.java),MockEnvironment().withProperty("nhrc.discovery.cron","0 15 6 * * *")).schedule()
  val next=OffsetDateTime.parse(result["nextRunAt"].toString());assertEquals(6,next.hour);assertEquals(15,next.minute);assertEquals("Administrator-configured schedule",result["label"])
 }
}
