package org.nhrc.grants.workflow

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** A later return, rejection or pending reconsideration supersedes earlier authorisation. */
object ApplicationDecisions {
 fun approved(jdbc:JdbcTemplate,id:UUID,type:String):Boolean = jdbc.queryForList(
  "select status from approvals where entity_type='APPLICATION' and entity_id=? and approval_type=? order by created_at desc,id desc limit 1",id,type
 ).firstOrNull()?.get("status")=="APPROVED"

 fun allApproved(jdbc:JdbcTemplate,id:UUID):Boolean {
  val latest=jdbc.queryForList("select distinct on (approval_type) status from approvals where entity_type='APPLICATION' and entity_id=? order by approval_type,created_at desc,id desc",id)
  return latest.isNotEmpty() && latest.all {it["status"]=="APPROVED"}
 }
}
