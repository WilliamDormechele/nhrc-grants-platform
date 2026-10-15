package org.nhrc.grants.operations

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AwardClosureReadiness(private val jdbc:JdbcTemplate) {
 fun checks(id:UUID):List<Map<String,Any>> {
   fun count(sql:String)=jdbc.queryForObject(sql,Long::class.java,id)?:0L
   val items=count("select count(*) from closeout_items where award_id=?")
   val checks=listOf(
     Triple("Closeout evidence",if(items==0L)1L else count("select count(*) from closeout_items where award_id=? and status<>'COMPLETE'"),if(items==0L)"Record the applicable closeout checklist and supporting evidence." else "Complete the recorded closeout items."),
     Triple("Funder reports",count("select count(*) from reports where award_id=? and status<>'ACCEPTED'"),"Obtain acceptance of the recorded funder reports."),
     Triple("Award deliverables",count("select count(*) from award_deliverables where award_id=? and status<>'ACCEPTED'"),"Obtain acceptance of the recorded deliverables."),
     Triple("Financial reports",count("select count(*) from financial_reports where award_id=? and status<>'ACCEPTED'"),"Complete approval, submission and acceptance of recorded financial reports."),
     Triple("Outstanding commitments",count("select count(*) from commitments where award_id=? and status='OPEN'"),"Settle or cancel the remaining commitments."),
     Triple("Partner advances",count("select count(*) from partner_advances p join award_partners a on a.id=p.award_partner_id where a.award_id=? and (p.status<>'RETIRED' or p.amount_accounted<>p.amount_advanced)"),"Account for and retire the recorded partner advances."),
     Triple("Ledger reconciliations",count("select count(*) from reconciliations where award_id=? and (status<>'RECONCILED' or variance<>0)"),"Resolve and complete the recorded ledger reconciliations."),
     Triple("Unreconciled expenditure",count("select count(*) from expenditures where award_id=? and reconciled=false"),"Reconcile the recorded expenditure before closure.")
   )
   return checks.map {(label,pending,guidance)->mapOf("label" to label,"pending" to pending,"complete" to (pending==0L),"guidance" to guidance)}
 }
}

