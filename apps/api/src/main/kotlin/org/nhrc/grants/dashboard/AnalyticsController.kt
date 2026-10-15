package org.nhrc.grants.dashboard

import org.nhrc.grants.config.PlatformIdentity
import org.nhrc.grants.config.PlatformAccess
import org.nhrc.grants.operations.OperationalCatalog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

@RestController
@RequestMapping("/api/operations/analytics")
class AnalyticsController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val catalog:OperationalCatalog) {
 @GetMapping fun analytics(@RequestParam(defaultValue="") q:String,@RequestParam(defaultValue="") currency:String,@RequestParam(defaultValue="") funder:String,@RequestParam(defaultValue="") status:String,@RequestParam(defaultValue="") year:String,@RequestParam(defaultValue="") owner:String):Map<String,Any> {
  val actor=identity.current();val groups=PlatformAccess.groups(actor.roles)
  if("Executive" !in groups)throw ResponseStatusException(HttpStatus.FORBIDDEN,"A business workspace is required")
  require(q.length<=200 && (currency.isBlank() || currency.matches(Regex("[A-Z]{3}"))) && (year.isBlank() || year.matches(Regex("20[0-9]{2}"))))
  val funderId=funder.takeIf {it.isNotBlank()}?.let(UUID::fromString);val ownerId=owner.takeIf {it.isNotBlank()}?.let(UUID::fromString)
  val own=actor.roles==setOf("RESEARCHER")
  val aa=mutableListOf<Any>();val aw=mutableListOf("true");val ba=mutableListOf<Any>();val bw=mutableListOf("true")
  fun filters(where:MutableList<String>,args:MutableList<Any>,term:String,value:Any?){if(value!=null && value!=""){where.add(term);args.add(value)}}
  if(q.isNotBlank()){filters(aw,aa,"concat_ws(' ',a.reference,a.title) ilike ?","%$q%");filters(bw,ba,"concat_ws(' ',a.reference,a.title) ilike ?","%$q%")}
  filters(aw,aa,"a.currency=?",currency);filters(bw,ba,"o.currency=?",currency)
  filters(aw,aa,"a.funder_id=?",funderId);filters(bw,ba,"o.funder_id=?",funderId)
  filters(aw,aa,"a.status=?",status);filters(bw,ba,"a.stage=?",status)
  if(year.isNotBlank()){filters(aw,aa,"extract(year from a.created_at)=?",year.toInt());filters(bw,ba,"extract(year from a.created_at)=?",year.toInt())}
  filters(aw,aa,"a.principal_investigator_id=?",if(own)actor.id else ownerId);filters(bw,ba,"coalesce(a.lead_researcher_id,a.owner_user_id)=?",if(own)actor.id else ownerId)
  if(own){bw.removeLast();ba.removeLast();bw.add("(a.lead_researcher_id=? or a.owner_user_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active))");ba.addAll(listOf(actor.id,actor.id,actor.id))}
  val awardsVisible=catalog.allowed("awards",actor.roles,false);val appsVisible="Pre-Award" in groups && catalog.allowed("proposals",actor.roles,false)
  if(!awardsVisible)aw.add("false");if(!appsVisible)bw.add("false")
  val awardWhere=aw.joinToString(" and ");val appWhere=bw.joinToString(" and ")
  fun awards(sql:String)=jdbc.queryForList(sql.replace("{where}",awardWhere),*aa.toTypedArray())
  fun apps(sql:String)=jdbc.queryForList(sql.replace("{where}",appWhere),*ba.toTypedArray())
  val portfolio=awards("select a.status,count(*) total from awards a where {where} group by a.status order by a.status")
  val finances=awards("select a.currency,count(*) awards,sum(a.total_award) award_value,sum(coalesce(p.approved_budget,0)) budget,sum(coalesce(p.cash_received,0)) received,sum(coalesce(p.expenditure,0)) spent,sum(coalesce(p.commitments,0)) committed from awards a left join award_financial_positions p on p.award_id=a.id where {where} group by a.currency order by a.currency")
  val pipeline=apps("select a.stage,count(*) total,round(avg(greatest(0,extract(epoch from now()-a.updated_at)/86400)),1) average_days, count(*) filter(where a.deadline_at<now() and a.stage not in ('SUBMITTED','AWARDED','CLOSED','REJECTED','WITHDRAWN')) overdue from applications a left join opportunities o on o.id=a.opportunity_id where {where} group by a.stage order by min(a.created_at)")
  val funders=awards("select coalesce(f.name,'Unspecified funder') funder,a.currency,count(*) awards,sum(a.total_award) amount from awards a left join funders f on f.id=a.funder_id where {where} group by f.name,a.currency order by count(*) desc")
  val risks=awards("select a.reference,a.title award_title,r.title,r.rating,r.status,r.review_date from award_risks r join awards a on a.id=r.award_id where {where} and r.status not in ('CLOSED','RESOLVED') order by r.review_date nulls last")
  val ending=awards("select a.reference,a.title,a.currency,a.total_award,a.end_date,a.status from awards a where {where} and a.status not in ('CLOSED','CANCELLED') and a.end_date<=current_date+90 order by a.end_date")
  val tasks=jdbc.queryForList("select t.*,u.display_name owner_name from lifecycle_tasks t left join users u on u.id=t.owner_id order by due_date nulls last,title").filter {t-> t["group_name"] in groups && ("SUPERADMIN" in actor.roles || t["owner_id"]==actor.id || t["owner_id"]==null && t["responsible_role"] in actor.roles)}.filter {t-> q.isBlank() || t["title"].toString().contains(q,true)}
  return mapOf("risks" to risks,"portfolio" to portfolio,"finance" to finances,"pipeline" to pipeline,"funders" to funders,"ending" to ending,"tasks" to tasks,"roles" to actor.roles,"superadmin" to ("SUPERADMIN" in actor.roles),"scope" to if(own)"Your assigned grants" else "Institutional records permitted by your roles","funderOptions" to jdbc.queryForList("select id,name from funders order by name"),"ownerOptions" to if(own)emptyList<Any>() else jdbc.queryForList("select id,display_name from users where active order by display_name"),"currencies" to jdbc.queryForList("select distinct currency from awards union select distinct currency from opportunities where currency is not null order by currency"),"years" to jdbc.queryForList("select distinct extract(year from created_at)::int as \"year\" from applications union select distinct extract(year from created_at)::int as \"year\" from awards order by 1 desc"))
 }
}
