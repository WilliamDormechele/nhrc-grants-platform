package org.nhrc.grants.opportunity

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class MatchConfirmation(val confirmed:Boolean=true,val confirmedBy:UUID?=null)

@RestController
@RequestMapping("/api/opportunity-intelligence")
class OpportunityIntelligenceController(private val jdbc:JdbcTemplate){
 @GetMapping("/matches")
 fun matches(@RequestParam(required=false) confirmed:Boolean?):List<Map<String,Any?>> =
  if(confirmed==null) jdbc.queryForList("""select m.*,o.title opportunity_title,o.discovery_review_status,u.display_name researcher
      from opportunity_researcher_matches m join opportunities o on o.id=m.opportunity_id
      join researcher_profiles rp on rp.id=m.researcher_profile_id join users u on u.id=rp.user_id
      order by m.fit_score desc nulls last limit 300""")
  else jdbc.queryForList("""select m.*,o.title opportunity_title,o.discovery_review_status,u.display_name researcher
      from opportunity_researcher_matches m join opportunities o on o.id=m.opportunity_id
      join researcher_profiles rp on rp.id=m.researcher_profile_id join users u on u.id=rp.user_id
      where m.human_confirmed=? order by m.fit_score desc nulls last limit 300""",confirmed)

 @PostMapping("/matches/{opportunityId}/{researcherProfileId}/confirmation") @Transactional
 fun confirm(@PathVariable opportunityId:UUID,@PathVariable researcherProfileId:UUID,@RequestBody r:MatchConfirmation):Map<String,Any>{
  val n=jdbc.update("""update opportunity_researcher_matches set human_confirmed=?,confirmed_by=?,confirmed_at=case when ? then now() else null end
      where opportunity_id=? and researcher_profile_id=?""",r.confirmed,r.confirmedBy,r.confirmed,opportunityId,researcherProfileId)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Opportunity/researcher match not found")
  return mapOf("opportunityId" to opportunityId,"researcherProfileId" to researcherProfileId,"humanConfirmed" to r.confirmed)
 }
}
