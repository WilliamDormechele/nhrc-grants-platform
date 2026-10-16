package org.nhrc.grants.personal
import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID
@RestController
@RequestMapping("/api/personal/funding-matches")
class MatchedFundingController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity) {
 private val select="""select o.id,o.title,o.summary,o.url,o.deadline_at,o.status,o.discovery_review_status,o.eligibility_status,o.institutional_fit_score,o.fit_rationale,m.fit_score researcher_fit_score,m.rationale expertise_rationale,m.human_confirmed from opportunity_researcher_matches m join researcher_profiles rp on rp.id=m.researcher_profile_id join opportunities o on o.id=m.opportunity_id where rp.user_id=? and rp.active and m.fit_score>0"""
 @GetMapping fun list()=jdbc.queryForList(select+" and o.status not in ('CLOSED','DISMISSED') and (o.deadline_at is null or o.deadline_at>now()) order by m.fit_score desc,o.deadline_at nulls last limit 200",identity.current().id)
 @GetMapping("/{id}") fun detail(@PathVariable id:UUID):Map<String,Any?> = jdbc.queryForList(select+" and o.id=?",identity.current().id,id).firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"This opportunity is not matched to your active researcher profile")
}
