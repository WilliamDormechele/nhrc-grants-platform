package org.nhrc.grants.workflow

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class AwardConversionRequest(val reference:String,val startDate:LocalDate?,val endDate:LocalDate?,val currency:String,val totalAward:BigDecimal,val nhrcAllocation:BigDecimal,val partnerAllocation:BigDecimal=BigDecimal.ZERO)

@RestController
@RequestMapping("/api/applications")
class AwardConversionController(private val jdbc:JdbcTemplate){
 @PostMapping("/{id}/convert-to-award") @Transactional
 fun convert(@PathVariable id:UUID,@RequestBody r:AwardConversionRequest):Map<String,Any>{
  val app=jdbc.queryForMap("select * from applications where id=?",id)
  if(app["funder_outcome"]!="AWARDED") throw ResponseStatusException(HttpStatus.CONFLICT,"Only an awarded application can be converted")
  val existing=jdbc.queryForObject("select count(*) from awards where application_id=?",Long::class.java,id)?:0
  if(existing>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Application already converted")
  val awardId=UUID.randomUUID()
  jdbc.update("""insert into awards(id,reference,application_id,title,funder_id,principal_investigator_id,start_date,end_date,currency,total_award,nhrc_allocation,partner_allocation,status) select ?,?,id,title,(select funder_id from opportunities where id=applications.opportunity_id),lead_researcher_id,?,?,?,?,?,?,'SETUP' from applications where id=?""",
   awardId,r.reference,r.startDate,r.endDate,r.currency,r.totalAward,r.nhrcAllocation,r.partnerAllocation,id)
  jdbc.update("insert into award_financial_positions(award_id,approved_budget) values (?,?)",awardId,r.nhrcAllocation)
  jdbc.update("update applications set stage='AWARDED',updated_at=now() where id=?",id)
  return mapOf("awardId" to awardId,"reference" to r.reference,"status" to "SETUP")
 }
}
