package org.nhrc.grants.operations

import org.nhrc.grants.config.PlatformIdentity
import org.nhrc.grants.config.PlatformAccess
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.security.SecureRandom
import java.util.Base64
import java.time.LocalDate

data class InstitutionalAccount(val email:String,val displayName:String,val jobTitle:String?,val organisationUnitId:UUID?,val roles:List<String>,val reason:String)
data class RoleChange(val roles:List<String>,val active:Boolean,val reason:String,val email:String?=null,val displayName:String?=null,val jobTitle:String?=null,val organisationUnitId:UUID?=null)
data class GovernedSettings(val enabled:Boolean,val lookaheadDays:Int,val urgentDays:Int,val note:String)

data class NarrativeSave(val section:String,val content:String,val revision:Long)

@RestController
@RequestMapping("/api/operations")
class InstitutionalWorkspacesController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val encoder:PasswordEncoder,private val catalog:OperationalCatalog,private val invitations:org.nhrc.grants.notifications.AccountInvitations) {
 private fun requireGroup(group:String){if(group !in PlatformAccess.groups(identity.current().roles))throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your role does not permit this workspace")}
 private fun admin(){if(identity.current().roles.none {it in setOf("ADMIN","SUPERADMIN")})throw ResponseStatusException(HttpStatus.FORBIDDEN,"Business administration is required")}
 @GetMapping("/dashboard") fun dashboard(@RequestParam(defaultValue="Executive") group:String):Map<String,Any> {
   requireGroup(group)
   val totals=jdbc.queryForMap("""select (select count(*) from opportunities where status not in ('CLOSED','DISMISSED')) opportunities,
     (select count(*) from applications where stage not in ('CLOSED','AWARDED')) applications,
     (select count(*) from awards where status='ACTIVE') active_awards,
     (select count(*) from reports where status not in ('ACCEPTED','SUBMITTED') and due_date<=current_date+30) reports_due,
     (select count(*) from approvals where status='PENDING') pending_approvals,
     (select count(*) from award_risks where status not in ('CLOSED','RESOLVED')) open_risks""")
   val finance=if(group in setOf("Finance & Accounts","Executive","Award Management")) jdbc.queryForList("""select a.currency,sum(p.approved_budget) budget,sum(p.cash_received) received,sum(p.expenditure) spent,sum(p.commitments) committed,
     sum(p.approved_budget-p.expenditure-p.commitments) available from awards a join award_financial_positions p on p.award_id=a.id group by a.currency order by a.currency""") else emptyList()
   val pipeline=when(group){
     "Procurement" -> jdbc.queryForList("select status stage,count(*) total from procurement_requisitions group by status order by status")
     "Laboratory" -> jdbc.queryForList("select status stage,count(*) total from laboratory_maintenance group by status order by status")
     else -> jdbc.queryForList("select stage,count(*) total from applications group by stage order by min(created_at)")
   }
   fun total(sql:String)=jdbc.queryForObject(sql,Long::class.java)?:0L
   fun tile(title:String,value:Any,detail:String)=mapOf("title" to title,"value" to value,"detail" to detail)
   val tiles=when(group){
    "Finance & Accounts" -> listOf(tile("Award currencies",finance.size,"Financial positions kept in source currency"),tile("Receipts expected",total("select count(*) from fund_receipts where received_date is null"),"Awaiting actual receipt confirmation"),tile("Outstanding advances",total("select count(*) from partner_advances where status<>'RETIRED'"),"Partner accountability still open"),tile("Financial reports due",total("select count(*) from financial_reports where status not in ('SUBMITTED','ACCEPTED') and due_date<=current_date+30"),"Due or overdue within 30 days"))
    "Procurement" -> listOf(tile("Procurement plans",total("select count(*) from procurement_plans where status not in ('COMPLETE','CANCELLED')"),"Open planned procurement"),tile("Requisitions submitted",total("select count(*) from procurement_requisitions where status='SUBMITTED'"),"Awaiting independent approval"),tile("Orders outstanding",total("select count(*) from purchase_orders where status in ('ISSUED','PART_DELIVERED')"),"Delivery remains outstanding"),tile("Grant assets",total("select count(*) from assets where status<>'RETIRED'"),"Recorded grant-funded assets"))
    "Laboratory" -> listOf(tile("Reagents and consumables",total("select count(*) from laboratory_items where item_type in ('REAGENT','CONSUMABLE') and status='ACTIVE'"),"Active stock records"),tile("Laboratory equipment",total("select count(*) from laboratory_items where item_type='EQUIPMENT' and status<>'DISPOSED'"),"Equipment under management"),tile("Maintenance due",total("select count(*) from laboratory_maintenance where status not in ('COMPLETE','CANCELLED') and scheduled_date<=current_date+30"),"Due or overdue within 30 days"),tile("Stock expiry due",total("select count(*) from laboratory_items where status='ACTIVE' and expiry_date<=current_date+30"),"Review before use"))
    else -> listOf(tile("Funding opportunities",totals["opportunities"]!!,"Open opportunity register"),tile("Applications in progress",totals["applications"]!!,"Before award conversion"),tile("Active awards",totals["active_awards"]!!,"Current funded projects"),tile("Reports due",totals["reports_due"]!!,"Due or overdue within 30 days"))
   }
   val activity=jdbc.queryForList("""select to_char(m,'Mon YYYY') as month_label,(select count(*) from applications where created_at>=m and created_at<m+interval '1 month') applications,
     (select count(*) from awards where created_at>=m and created_at<m+interval '1 month') awards from generate_series(date_trunc('month',now())-interval '5 months',date_trunc('month',now()),interval '1 month') m order by m""")
   val alerts=when(group){
     "Finance & Accounts" -> jdbc.queryForList("select a.reference||' · Financial report' title,r.due_date,case when r.due_date<current_date then 'OVERDUE' else 'UPCOMING' end severity,'Financial Reporting' workspace,'Finance & Accounts' group_name from financial_reports r join awards a on a.id=r.award_id where r.status not in ('SUBMITTED','ACCEPTED') and r.due_date<=current_date+30 order by r.due_date limit 8")
     "Procurement" -> jdbc.queryForList("select reference||' · Delivery outstanding' title,expected_delivery_date due_date,case when expected_delivery_date<current_date then 'OVERDUE' else 'UPCOMING' end severity,'Purchase Orders' workspace,'Procurement' group_name from purchase_orders where status in ('ISSUED','PART_DELIVERED') and expected_delivery_date<=current_date+30 order by expected_delivery_date limit 8")
     "Laboratory" -> jdbc.queryForList("select l.name||' · '||m.activity_type title,m.scheduled_date due_date,case when m.scheduled_date<current_date then 'OVERDUE' else 'UPCOMING' end severity,'Maintenance & Calibration' workspace,'Laboratory' group_name from laboratory_maintenance m join laboratory_items l on l.id=m.laboratory_item_id where m.status not in ('COMPLETE','CANCELLED') and m.scheduled_date<=current_date+30 order by m.scheduled_date limit 8")
     else -> jdbc.queryForList("select title,due_date,severity,workspace,group_name from intelligence_alerts where status='OPEN' order by due_date limit 8")
   }
   val registers=catalog.definitions.filter { it.group==group && catalog.allowed(it.key,identity.current().roles,false) }.map { d -> mapOf("title" to d.title,"page" to d.pages.first(),"total" to (jdbc.queryForObject("select count(*) from ${d.table}",Long::class.java)?:0)) }
   return mapOf("tiles" to tiles,"totals" to totals,"finance" to finance,"pipeline" to pipeline,"activity" to activity,"alerts" to alerts.filter { it["group_name"] in PlatformAccess.groups(identity.current().roles) && it["workspace"] !in catalog.hiddenPages(identity.current().roles) },"registers" to registers)
 }
 @GetMapping("/institutional-settings") fun institutionalSettings():Map<String,Any?> {requireGroup("Administration");return jdbc.queryForMap("select enabled,lookahead_days,urgent_days,updated_at from intelligence_automation_settings where id=true")}
 @PutMapping("/institutional-settings") @Transactional fun settings(@RequestBody r:GovernedSettings):Map<String,Any?> {
   admin();require(r.lookaheadDays in 1..180 && r.urgentDays in 0..30 && r.urgentDays<=r.lookaheadDays && r.note.isNotBlank()){"Use a lookahead of 1–180 days, an urgent window up to 30 days within that period, and record the change reason"}
   jdbc.update("update intelligence_automation_settings set enabled=?,lookahead_days=?,urgent_days=?,updated_at=now() where id=true",r.enabled,r.lookaheadDays,r.urgentDays)
   jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('settings',?,?,'SETTINGS_UPDATED',?)",UUID.nameUUIDFromBytes("NHRC institutional settings".toByteArray()),identity.current().id,r.note)
   return institutionalSettings()
 }
 @GetMapping("/personal") fun personal():Map<String,Any> {
   val user=identity.current().id
   return mapOf("assignments" to jdbc.queryForList("select a.id,a.reference,a.title,a.stage,a.deadline_at,aa.response,aa.id assignment_id from application_assignments aa join applications a on a.id=aa.application_id where aa.researcher_id=? order by a.deadline_at nulls last",user),
     "deliverables" to jdbc.queryForList("select d.id,d.title,d.due_date,d.status,a.reference award_reference from award_deliverables d join awards a on a.id=d.award_id where d.owner_user_id=? and d.status not in ('ACCEPTED','CLOSED') order by d.due_date",user),
     "approvals" to jdbc.queryForList("select id,approval_type,status,created_at from approvals where assigned_user_id=? and status='PENDING'",user),
     "notifications" to jdbc.queryForList("select id,title,message,type,read_at,created_at from notifications where in_app_visible=true and user_id=? order by created_at desc limit 100",user))
 }
 @PostMapping("/notifications/{id}/read") fun read(@PathVariable id:UUID):Map<String,Any> {
   val changed=jdbc.update("update notifications set read_at=coalesce(read_at,now()) where id=? and user_id=?",id,identity.current().id)
   if(changed==0)throw ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found");return mapOf("read" to true)
 }
 @GetMapping("/calendar-events") fun calendar(@RequestParam month:String):List<Map<String,Any?>> {
   identity.current();val start=LocalDate.parse(month+"-01");val end=start.plusMonths(1)
   return jdbc.queryForList("""select * from (
     select title,deadline_at::date due_date,'Applications' workspace,'Pre-Award' group_name,stage status from applications where deadline_at is not null
     union all select a.reference||' · '||r.report_type,r.due_date,'Reports','Performance',r.status from reports r join awards a on a.id=r.award_id
     union all select title,due_date,'Deliverables','Award Management',status from award_deliverables
     union all select compliance_type,coalesce(renewal_due_date,expiry_date),'Ethics & Regulatory Links','Research Governance',status from compliance_records
     union all select name,rule_date,'Calendar Rules','Administration',case when working_day then 'WORKING_DAY' else 'HOLIDAY' end from calendar_rules
   ) c where due_date>=? and due_date<? order by due_date,title""",start,end)
 }
 @GetMapping("/accounts") fun accounts():Map<String,Any> {requireGroup("Administration");return mapOf("users" to jdbc.queryForList("select u.id,u.display_name,u.email,u.job_title,u.organisation_unit_id,u.active,exists(select 1 from account_invitations i where i.user_id=u.id and i.used_at is null and i.revoked_at is null) onboarding_pending,coalesce(string_agg(distinct r.code,','),'') role_codes from users u left join user_roles ur on ur.user_id=u.id left join roles r on r.id=ur.role_id group by u.id order by u.display_name"),"roles" to jdbc.queryForList("select code,name,description from roles where (privileged=false or ?=true) order by name","SUPERADMIN" in identity.current().roles))}
 @PostMapping("/accounts") @Transactional fun createAccount(@RequestBody r:InstitutionalAccount):Map<String,Any> {
   admin();require(r.email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) && r.displayName.isNotBlank() && r.reason.isNotBlank()){"Name, valid email and appointment reason are required"}
   val allowed=jdbc.queryForList("select code from roles where privileged=false or ?=true",String::class.java,"SUPERADMIN" in identity.current().roles).toSet();require(r.roles.isNotEmpty() && r.roles.all { it in allowed }){"Choose permitted roles"}
   val bytes=ByteArray(18);SecureRandom().nextBytes(bytes);val password=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);val id=UUID.randomUUID()
   jdbc.update("insert into users(id,email,display_name,job_title,organisation_unit_id) values (?,?,?,?,?)",id,r.email.lowercase(),r.displayName,r.jobTitle,r.organisationUnitId)
   jdbc.update("insert into uat_credentials(user_id,password_hash) values (?,?)",id,encoder.encode(password))
   assignRoles(id,r.roles)
   jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('accounts',?,?,'CREATED',?)",id,identity.current().id,r.reason)
   if(org.nhrc.grants.notifications.MailAddress.deliverable(r.email)) {
    val invitation=invitations.issue(id,identity.current().id)
    val queued=jdbc.queryForObject("select exists(select 1 from email_outbox o join notifications n on n.id=o.notification_id where n.entity_id=?)",Boolean::class.java,invitation)==true
    return mapOf("id" to id,"email" to r.email,"invitationQueued" to queued,"welcomeEmailDisabled" to !queued)
   }
   return mapOf("id" to id,"email" to r.email,"initialPassword" to password)
 }
 @PostMapping("/accounts/{id}/welcome") @Transactional fun resendWelcome(@PathVariable id:UUID,@RequestBody body:Map<String,String>):Map<String,Boolean> {
  admin();require(!body["reason"].isNullOrBlank() && body["reason"]!!.length<=2000){"Record the reason for resending the welcome email"}
  jdbc.queryForObject("select id from users where id=? for update",UUID::class.java,id)
  if("SUPERADMIN" !in identity.current().roles && jdbc.queryForObject("select exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and r.privileged)",Boolean::class.java,id)==true)throw ResponseStatusException(HttpStatus.FORBIDDEN,"Only Superadmin can manage privileged accounts")
  val pending=jdbc.queryForObject("select exists(select 1 from account_invitations i join users u on u.id=i.user_id where i.user_id=? and i.used_at is null and i.revoked_at is null and u.active)",Boolean::class.java,id)==true
  if(!pending)throw ResponseStatusException(HttpStatus.CONFLICT,"Only pending account setup invitations can be resent")
  val invitation=invitations.issue(id,identity.current().id)
  jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('accounts',?,?,'WELCOME_EMAIL_REISSUED',?)",id,identity.current().id,body["reason"])
  return mapOf("queued" to (jdbc.queryForObject("select exists(select 1 from email_outbox o join notifications n on n.id=o.notification_id where n.entity_id=?)",Boolean::class.java,invitation)==true))
 }
 @PutMapping("/accounts/{id}") @Transactional fun roles(@PathVariable id:UUID,@RequestBody r:RoleChange):Map<String,Any> {
   admin();require(r.reason.isNotBlank()){"Record the reason for this access change"}
   jdbc.queryForList("select id from users order by id for update")
   if("SUPERADMIN" !in identity.current().roles && jdbc.queryForObject("select exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and r.privileged)",Boolean::class.java,id)==true)throw ResponseStatusException(HttpStatus.FORBIDDEN,"Only Superadmin can change a privileged account")
   if(id==identity.current().id)throw ResponseStatusException(HttpStatus.CONFLICT,"Use another administrator to change your own access")
   val allowed=jdbc.queryForList("select code from roles where privileged=false or ?=true",String::class.java,"SUPERADMIN" in identity.current().roles).toSet();require(r.roles.all { it in allowed } && (!r.active || r.roles.isNotEmpty())){"Choose permitted roles"}
   if(r.email!=null) {
    require(r.email.length<=255 && r.email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))){"Enter a valid email address"}
    if(jdbc.queryForObject("select exists(select 1 from users where lower(email)=lower(?) and id<>?)",Boolean::class.java,r.email,id)==true)throw ResponseStatusException(HttpStatus.CONFLICT,"That email address is already assigned to another account")
   }
   if(r.displayName!=null)require(r.displayName.isNotBlank() && r.displayName.length<=255){"Enter the account holder's full name"}
   val previousAccess=jdbc.queryForObject("select jsonb_build_object('roles',(select jsonb_agg(r.code) from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id),'active',u.active,'email',u.email,'displayName',u.display_name,'jobTitle',u.job_title,'organisationUnitId',u.organisation_unit_id)::text from users u where u.id=?",String::class.java,id)
   jdbc.update("delete from user_roles where user_id=? and role_id in (select id from roles where privileged=false or ?=true)",id,"SUPERADMIN" in identity.current().roles);assignRoles(id,r.roles)
   if(r.displayName!=null)jdbc.update("update users set display_name=?,email=coalesce(?,email),job_title=?,organisation_unit_id=?,updated_at=now() where id=?",r.displayName,r.email?.lowercase(),r.jobTitle,r.organisationUnitId,id)
   else if(r.email!=null)jdbc.update("update users set email=?,updated_at=now() where id=?",r.email.lowercase(),id)
   jdbc.update("update users set active=?,updated_at=now() where id=?",r.active,id)
   jdbc.update("update email_outbox o set status='CANCELLED',last_error='Recipient account or address changed' from notifications n,users u where o.notification_id=n.id and n.user_id=u.id and u.id=? and o.status in ('QUEUED','RETRY','BLOCKED_RECIPIENT') and (not u.active or lower(o.recipient)<>lower(u.email))",id)
   jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,before_data,after_data,note) values ('accounts',?,?,'ACCESS_CHANGED',?::jsonb,jsonb_build_object('roles',string_to_array(?,','),'active',?::boolean,'email',(select email from users where id=?),'displayName',(select display_name from users where id=?)),?)",id,identity.current().id,previousAccess,r.roles.joinToString(","),r.active,id,id,r.reason)
   return mapOf("saved" to true)
 }
 private fun assignRoles(id:UUID,roles:List<String>){roles.forEach { role ->jdbc.update("insert into user_roles(user_id,role_id,scope_type,scope_id,granted_by) select ?,r.id,'INSTITUTION',o.id,? from roles r cross join organisation_units o where r.code=? and o.code='NHRC' on conflict do nothing",id,identity.current().id,role)}}
 @GetMapping("/assurance") fun assurance():Map<String,Any> {
   requireGroup("IT & Security")
   return mapOf("database" to jdbc.queryForObject("select version()",String::class.java)!!,"authentication" to "Session sign-in with CSRF protection","environment" to "Local acceptance environment","events" to jdbc.queryForList("select id,severity,component,message,created_at,acknowledged_at from system_events order by created_at desc limit 30"),"backups" to jdbc.queryForList("select id,backup_type,started_at,completed_at,status,size_bytes,verification_status from backup_runs order by started_at desc limit 20"),"recoveries" to jdbc.queryForList("select id,tested_at,status,recovery_minutes,notes from recovery_tests order by tested_at desc limit 20"),"jobs" to jdbc.queryForList("select id,trigger_type,started_at,completed_at,status,detected,created,resolved,error from intelligence_automation_runs order by started_at desc limit 20"),"discoveryJobs" to jdbc.queryForList("select r.id,s.name source,r.started_at,r.status,r.created_count,r.error_message from opportunity_discovery_runs r left join opportunity_sources s on s.id=r.source_id order by r.started_at desc limit 20"),"privileged" to jdbc.queryForList("select p.action,p.reason,p.occurred_at,u.display_name actor from privileged_events p left join users u on u.id=p.actor_user_id order by p.occurred_at desc limit 100"))
 }
}
