package org.nhrc.grants.award

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/award-management")
class AwardManagementController(private val jdbc:JdbcTemplate){
 @GetMapping("/contracts") fun contracts(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select c.*,a.reference award_reference,p.name partner_name from contracts c left join awards a on a.id=c.award_id left join partners p on p.id=c.partner_id where (?='' or c.status=?) order by c.expiry_date nulls last limit 300",s,s) }
 @GetMapping("/amendments") fun amendments(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select m.*,a.reference award_reference from amendments m join awards a on a.id=m.award_id where (?='' or m.internal_status=?) order by m.created_at desc limit 300",s,s) }
 @GetMapping("/deliverables") fun deliverables(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select d.*,a.reference award_reference from award_deliverables d join awards a on a.id=d.award_id where (?='' or d.status=?) order by d.due_date limit 300",s,s) }
 @GetMapping("/risks") fun risks(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select r.*,a.reference award_reference from award_risks r join awards a on a.id=r.award_id where (?='' or r.status=?) order by r.review_date nulls last,r.created_at desc limit 300",s,s) }
 @GetMapping("/partners") fun partners(@RequestParam(required=false) awardId:UUID?):List<Map<String,Any?>> = if(awardId==null) jdbc.queryForList("select ap.*,p.name partner_name,a.reference award_reference from award_partners ap join partners p on p.id=ap.partner_id join awards a on a.id=ap.award_id order by a.reference,p.name limit 300") else jdbc.queryForList("select ap.*,p.name partner_name from award_partners ap join partners p on p.id=ap.partner_id where ap.award_id=? order by p.name",awardId)
}
