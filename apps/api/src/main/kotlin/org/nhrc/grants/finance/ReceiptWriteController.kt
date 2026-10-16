package org.nhrc.grants.finance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ReceiptCreate(val awardId:UUID,val instalmentNo:Int?,val expectedAmount:BigDecimal,val expectedDate:LocalDate?,val receivedAmount:BigDecimal?,val receivedDate:LocalDate?,val currency:String,val reference:String?)
@RestController
@RequestMapping("/api/finance/receipts")
class ReceiptWriteController(private val jdbc:JdbcTemplate){
 @PostMapping @Transactional fun create(@RequestBody r:ReceiptCreate):Map<String,Any>{ val id=UUID.randomUUID(); jdbc.update("insert into fund_receipts(id,award_id,instalment_no,expected_amount,expected_date,received_amount,received_date,currency,reference) values (?,?,?,?,?,?,?,?,?)",id,r.awardId,r.instalmentNo,r.expectedAmount,r.expectedDate,r.receivedAmount,r.receivedDate,r.currency,r.reference); if(r.receivedAmount!=null) jdbc.update("update award_financial_positions set cash_received=cash_received+?,updated_at=now() where award_id=?",r.receivedAmount,r.awardId); return mapOf("id" to id) }
}
