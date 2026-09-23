package org.nhrc.grants.proposal

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

data class BudgetLineCreate(
 val category:String,
 val description:String?=null,
 val yearNo:Int=1,
 val quantity:BigDecimal=BigDecimal.ONE,
 val unitCost:BigDecimal=BigDecimal.ZERO,
 val currency:String,
 val funderAmount:BigDecimal=BigDecimal.ZERO,
 val nhrcContribution:BigDecimal=BigDecimal.ZERO,
 val partnerAmount:BigDecimal=BigDecimal.ZERO
)
data class ProposalDocumentCreate(val documentType:String,val title:String,val storageKey:String,val versionNo:Int=1,val status:String="DRAFT",val uploadedBy:UUID?=null)
data class ProposalReviewCreate(val reviewType:String,val reviewerId:UUID?=null,val comments:String?=null)

@RestController
@RequestMapping("/api/proposals")
class ProposalController(private val jdbc:JdbcTemplate){
 @GetMapping("/{applicationId}/documents")
 fun documents(@PathVariable applicationId:UUID)=jdbc.queryForList("select * from application_documents where application_id=? order by document_type,version_no desc",applicationId)

 @PostMapping("/{applicationId}/documents") @Transactional
 fun addDocument(@PathVariable applicationId:UUID,@RequestBody r:ProposalDocumentCreate):Map<String,Any>{
  if(r.documentType.isBlank()||r.title.isBlank()||r.storageKey.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Document type, title and storage key are required")
  if(r.status !in setOf("DRAFT","FINAL","APPROVED","SUPERSEDED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid document status")
  val id=UUID.randomUUID()
  jdbc.update("""insert into application_documents(id,application_id,document_type,title,storage_key,version_no,status,uploaded_by) values (?,?,?,?,?,?,?,?)""",id,applicationId,r.documentType,r.title,r.storageKey,r.versionNo,r.status,r.uploadedBy)
  return mapOf("id" to id,"status" to r.status)
 }

 @GetMapping("/{applicationId}/budget")
 fun budget(@PathVariable applicationId:UUID)=jdbc.queryForList("select *,quantity*unit_cost calculated_cost from application_budget_lines where application_id=? order by year_no,category",applicationId)

 @PostMapping("/{applicationId}/budget") @Transactional
 fun addBudgetLine(@PathVariable applicationId:UUID,@RequestBody r:BudgetLineCreate):Map<String,Any>{
  if(r.yearNo<1 || r.quantity<BigDecimal.ZERO || r.unitCost<BigDecimal.ZERO) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid budget values")
  if(r.currency.length!=3) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Currency must be a 3-letter code")
  val id=UUID.randomUUID()
  jdbc.update("""insert into application_budget_lines(id,application_id,category,description,year_no,quantity,unit_cost,currency,funder_amount,nhrc_contribution,partner_amount) values (?,?,?,?,?,?,?,?,?,?,?)""",id,applicationId,r.category,r.description,r.yearNo,r.quantity,r.unitCost,r.currency.uppercase(),r.funderAmount,r.nhrcContribution,r.partnerAmount)
  return mapOf("id" to id,"calculatedCost" to r.quantity.multiply(r.unitCost))
 }

 @GetMapping("/{applicationId}/reviews")
 fun reviews(@PathVariable applicationId:UUID)=jdbc.queryForList("select r.*,u.display_name reviewer from application_reviews r left join users u on u.id=r.reviewer_id where r.application_id=? order by r.created_at",applicationId)

 @PostMapping("/{applicationId}/reviews") @Transactional
 fun addReview(@PathVariable applicationId:UUID,@RequestBody r:ProposalReviewCreate):Map<String,Any>{
  if(r.reviewType.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Review type is required")
  val id=UUID.randomUUID()
  jdbc.update("insert into application_reviews(id,application_id,review_type,reviewer_id,status,comments) values (?,?,?,?,'PENDING',?)",id,applicationId,r.reviewType.uppercase(),r.reviewerId,r.comments)
  return mapOf("id" to id,"status" to "PENDING")
 }
}
