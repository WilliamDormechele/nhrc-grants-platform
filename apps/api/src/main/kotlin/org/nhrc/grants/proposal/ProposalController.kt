package org.nhrc.grants.proposal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/proposals")
class ProposalController(private val jdbc:JdbcTemplate){
 @GetMapping("/{applicationId}/documents") fun documents(@PathVariable applicationId:UUID)=jdbc.queryForList("select * from application_documents where application_id=? order by document_type,version_no desc",applicationId)
 @GetMapping("/{applicationId}/budget") fun budget(@PathVariable applicationId:UUID)=jdbc.queryForList("select *,quantity*unit_cost calculated_cost from application_budget_lines where application_id=? order by year_no,category",applicationId)
 @GetMapping("/{applicationId}/reviews") fun reviews(@PathVariable applicationId:UUID)=jdbc.queryForList("select r.*,u.display_name reviewer from application_reviews r left join users u on u.id=r.reviewer_id where r.application_id=? order by r.created_at",applicationId)
}
