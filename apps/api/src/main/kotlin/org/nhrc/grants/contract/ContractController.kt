package org.nhrc.grants.contract

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/contracts")
class ContractController(private val jdbc:JdbcTemplate){
 @GetMapping fun search(@RequestParam(required=false) q:String?,@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val t="%${q?:""}%";val s=status?:"";return jdbc.queryForList("select c.*,a.reference award_reference,p.name partner_name from contracts c left join awards a on a.id=c.award_id left join partners p on p.id=c.partner_id where (lower(c.title) like lower(?) or lower(coalesce(c.reference,'')) like lower(?)) and (?='' or c.status=?) order by c.expiry_date nulls last limit 300",t,t,s,s) }
}
