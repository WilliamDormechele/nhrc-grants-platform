package org.nhrc.grants.operations

import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@RestController
@RequestMapping("/api/operations/proposals")
class ProposalNarrativeController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity) {
 private val sections=setOf("summary","rationale","objectives","methods","delivery","governance","data","dissemination","costing")
 private fun access(id:UUID,write:Boolean=false) {
   val actor=identity.current()
   if(actor.roles.any { it in setOf("GRANTS_OFFICER","DIRECTOR","ADMIN") })return
   if(!write && jdbc.queryForObject("select count(*) from application_reviews where application_id=? and reviewer_id=?",Long::class.java,id,actor.id)!!>0L)return
   if("RESEARCHER" !in actor.roles || jdbc.queryForObject("select count(*) from applications a where a.id=? and (a.lead_researcher_id=? or a.owner_user_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active=true))",Long::class.java,id,actor.id,actor.id,actor.id)!=1L)throw ResponseStatusException(HttpStatus.FORBIDDEN,"This proposal is outside your assigned work")
 }
 @GetMapping("/{id}/permissions") fun permissions(@PathVariable id:UUID):Map<String,Boolean> {identity.current();val read=runCatching {access(id)}.isSuccess;val write=runCatching {access(id,true)}.isSuccess;return mapOf("canRead" to read,"canEdit" to write,"canAttach" to (write && identity.current().roles.any {it in setOf("GRANTS_OFFICER","RESEARCHER","DIRECTOR")}))}
 @GetMapping("/{id}/narrative") fun read(@PathVariable id:UUID):List<Map<String,Any?>> {access(id);return jdbc.queryForList("select section,content,revision,saved_at from proposal_narratives where application_id=? order by section",id)}
 @PutMapping("/{id}/narrative") @Transactional fun save(@PathVariable id:UUID,@RequestBody r:NarrativeSave):Map<String,Any> {
   access(id,true);require(r.section in sections && r.content.length<=100000){"Choose a valid proposal section up to 100,000 characters"}
   jdbc.queryForMap("select id from applications where id=? for update",id)
   val old=jdbc.queryForList("select revision from proposal_narratives where application_id=? and section=?",id,r.section).firstOrNull()
   if(((old?.get("revision") as? Number)?.toLong() ?: 0L) != r.revision)throw ResponseStatusException(HttpStatus.CONFLICT,"This proposal section changed. Reload it before saving.")
   val version=r.revision+1
   jdbc.update("insert into proposal_narratives(application_id,section,content,revision,saved_by) values (?,?,?,?,?) on conflict(application_id,section) do update set content=excluded.content,revision=excluded.revision,saved_by=excluded.saved_by,saved_at=now()",id,r.section,r.content,version,identity.current().id)
   return mapOf("section" to r.section,"revision" to version)
 }
}
