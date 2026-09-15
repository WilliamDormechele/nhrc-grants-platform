package org.nhrc.grants.opportunity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class OpportunityCreate(val title:String,val sourceType:String,val sourceReference:String?=null,val funderId:UUID?=null,val url:String?=null,val summary:String?=null,val currency:String?=null,val amountMin:BigDecimal?=null,val amountMax:BigDecimal?=null,val opensAt:OffsetDateTime?=null,val deadlineAt:OffsetDateTime?=null,val createdBy:UUID?=null)

@RestController
@RequestMapping("/api/opportunities")
class OpportunityWriteController(private val jdbc:JdbcTemplate){
 @PostMapping @Transactional fun create(@RequestBody r:OpportunityCreate):Map<String,Any>{
  val id=UUID.randomUUID(); jdbc.update("""insert into opportunities(id,source_type,source_reference,title,funder_id,url,summary,currency,amount_min,amount_max,opens_at,deadline_at,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?)""",id,r.sourceType,r.sourceReference,r.title,r.funderId,r.url,r.summary,r.currency,r.amountMin,r.amountMax,r.opensAt,r.deadlineAt,r.createdBy); return mapOf("id" to id,"status" to "DISCOVERED")
 }
}
