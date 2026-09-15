package org.nhrc.grants.opportunity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/opportunity-intelligence")
class OpportunityIntelligenceController(private val jdbc:JdbcTemplate){
 @GetMapping("/matches") fun matches(@RequestParam(required=false) confirmed:Boolean?):List<Map<String,Any?>> = if(confirmed==null) jdbc.queryForList("select m.*,o.title opportunity_title,u.display_name researcher from opportunity_researcher_matches m join opportunities o on o.id=m.opportunity_id join researcher_profiles rp on rp.id=m.researcher_profile_id join users u on u.id=rp.user_id order by m.fit_score desc nulls last limit 300") else jdbc.queryForList("select m.*,o.title opportunity_title,u.display_name researcher from opportunity_researcher_matches m join opportunities o on o.id=m.opportunity_id join researcher_profiles rp on rp.id=m.researcher_profile_id join users u on u.id=rp.user_id where m.human_confirmed=? order by m.fit_score desc nulls last limit 300",confirmed)
}
