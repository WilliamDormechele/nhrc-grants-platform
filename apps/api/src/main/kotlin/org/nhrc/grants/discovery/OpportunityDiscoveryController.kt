package org.nhrc.grants.discovery

import org.springframework.web.bind.annotation.*
import java.util.UUID
import org.springframework.core.env.Environment
import org.springframework.scheduling.support.CronExpression
import java.time.ZonedDateTime
import java.time.ZoneId

data class DiscoveryRunRequest(val sourceCode:String?=null)

@RestController
@RequestMapping("/api/opportunity-intelligence/discovery")
class OpportunityDiscoveryController(private val service:OpportunityDiscoveryService,private val environment:Environment){
 @GetMapping("/schedule") fun schedule():Map<String,Any?> {
  val enabled=environment.getProperty("nhrc.discovery.enabled",Boolean::class.java,true)
  val cron=environment.getProperty("nhrc.discovery.cron","0 */15 * * * *")!!
  val zone=environment.getProperty("nhrc.discovery.zone","UTC")!!
  return mapOf("enabled" to enabled,"label" to if(cron=="0 */15 * * * *")"Every 15 minutes" else "Administrator-configured schedule","zone" to zone,"nextRunAt" to if(enabled)CronExpression.parse(cron).next(ZonedDateTime.now(ZoneId.of(zone)))?.toOffsetDateTime()?.toString() else null)
 }
 @GetMapping("/sources") fun sources()=service.sources()
 @GetMapping("/runs") fun runs(@RequestParam(defaultValue="50") limit:Int)=service.runs(limit)
 @GetMapping("/evidence") fun evidence(@RequestParam(required=false) opportunityId:UUID?,@RequestParam(defaultValue="100") limit:Int)=service.evidence(opportunityId,limit)
 @GetMapping("/evidence/{id}") fun evidencePayload(@PathVariable id:UUID)=service.evidencePayload(id)
 @PostMapping("/run") fun run(@RequestBody(required=false) r:DiscoveryRunRequest?):Any =
   if(r?.sourceCode.isNullOrBlank()) service.runAll("MANUAL") else service.runOne(r!!.sourceCode!!,"MANUAL")
 @PatchMapping("/sources/{code}") fun updateSource(@PathVariable code:String,@RequestBody r:SourceUpdateRequest)=service.updateSource(code,r)
 @PostMapping("/opportunities/{id}/review") fun review(@PathVariable id:UUID,@RequestBody r:DiscoveryReviewRequest)=service.reviewOpportunity(id,r)
}
