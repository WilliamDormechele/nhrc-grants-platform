package org.nhrc.grants.opportunity

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class OpportunityCreate(val title:String,val sourceType:String,val sourceReference:String?=null,val funderId:UUID?=null,val url:String?=null,val summary:String?=null,val currency:String?=null,val amountMin:BigDecimal?=null,val amountMax:BigDecimal?=null,val opensAt:OffsetDateTime?=null,val deadlineAt:OffsetDateTime?=null,val createdBy:UUID?=null)
data class OpportunityEligibilityDecision(val decision:String,val rationale:String,val conditions:String?=null,val reviewedBy:UUID?=null)
data class OpportunityFitUpdate(val score:BigDecimal,val rationale:String?=null)

@RestController
@RequestMapping("/api/opportunities")
class OpportunityWriteController(private val jdbc:JdbcTemplate){
 @PostMapping @Transactional
 fun create(@RequestBody r:OpportunityCreate):Map<String,Any>{
  val id=UUID.randomUUID()
  jdbc.update("""insert into opportunities(id,source_type,source_reference,title,funder_id,url,summary,currency,amount_min,amount_max,opens_at,deadline_at,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?)""",id,r.sourceType,r.sourceReference,r.title,r.funderId,r.url,r.summary,r.currency,r.amountMin,r.amountMax,r.opensAt,r.deadlineAt,r.createdBy)
  return mapOf("id" to id,"status" to "DISCOVERED")
 }

 @PostMapping("/{id}/eligibility") @Transactional
 fun eligibility(@PathVariable id:UUID,@RequestBody r:OpportunityEligibilityDecision):Map<String,Any>{
  if(r.decision !in setOf("ELIGIBLE","INELIGIBLE","CONDITIONAL")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid eligibility decision")
  if(r.rationale.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Eligibility rationale is required")
  val rows=jdbc.queryForList("select discovery_source_id,discovery_review_status from opportunities where id=? limit 1",id)
  if(rows.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Opportunity not found")
  val opportunity=rows.first()
  if(opportunity["discovery_source_id"]!=null && opportunity["discovery_review_status"]!="ACCEPTED")
   throw ResponseStatusException(HttpStatus.CONFLICT,"Externally discovered opportunity must be accepted by NHRC before eligibility review")
  jdbc.update("insert into eligibility_reviews(opportunity_id,decision,rationale,conditions,reviewed_by) values (?,?,?,?,?)",id,r.decision,r.rationale,r.conditions,r.reviewedBy)
  jdbc.update("update opportunities set eligibility_status=?,updated_at=now() where id=?",r.decision,id)
  return mapOf("id" to id,"eligibilityStatus" to r.decision)
 }

 @PatchMapping("/{id}/fit") @Transactional
 fun fit(@PathVariable id:UUID,@RequestBody r:OpportunityFitUpdate):Map<String,Any>{
  if(r.score < BigDecimal.ZERO || r.score > BigDecimal("100")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Fit score must be between 0 and 100")
  val n=jdbc.update("update opportunities set institutional_fit_score=?,fit_rationale=?,updated_at=now() where id=?",r.score,r.rationale,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Opportunity not found")
  return mapOf("id" to id,"institutionalFitScore" to r.score)
 }
}
