package org.nhrc.grants.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

@Service
class NotificationEmails(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper,private val catalog:org.nhrc.grants.operations.OperationalCatalog,private val invitations:AccountInvitations,@Value("\${nhrc.portal-url:http://localhost:3000}") private val portal:String) {
 private fun base()=portal.trimEnd('/').also {require(it.startsWith("https://") || Regex("http://(localhost|127\\.0\\.0\\.1):[0-9]+").matches(it)){"Configure an HTTPS institutional portal URL or a local UAT URL"}}
 private fun enc(s:String)=URLEncoder.encode(s,StandardCharsets.UTF_8)
 private val routes=mapOf("applications" to ("Pre-Award" to "Applications"),"application_assignments" to ("Personal" to "My Work"),"application_reviews" to ("Pre-Award" to "Internal Review"),"approvals" to ("Pre-Award" to "Approvals"),"awards" to ("Award Management" to "Award Register"),"award_handovers" to ("Award Management" to "Award Register"),"award_deliverables" to ("Award Management" to "Deliverables"),"reports" to ("Performance" to "Reports"),"financial_reports" to ("Finance & Accounts" to "Financial Reporting"),"partner_advances" to ("Finance & Accounts" to "Partner Advances"),"procurement_requisitions" to ("Procurement" to "Requisitions"),"purchase_orders" to ("Procurement" to "Purchase Orders"),"contracts" to ("Research Governance" to "Legal & Contracts Review"),"laboratory_maintenance" to ("Laboratory" to "Maintenance & Calibration"),"closeout_items" to ("Performance" to "Closeout"),"compliance_records" to ("Research Governance" to "Ethics & Regulatory Links"))
 private fun tableFor(type:String)=mapOf("APPLICATION" to "applications","ASSIGNMENT" to "application_assignments","APPROVAL" to "approvals","REVIEW" to "application_reviews","DELIVERABLE" to "award_deliverables","REPORT" to "reports","FINANCIAL_REPORT" to "financial_reports","ADVANCE" to "partner_advances","CLOSEOUT" to "closeout_items","PROCUREMENT" to "purchase_orders","LABORATORY" to "laboratory_maintenance","GOVERNANCE" to "compliance_records","CONTRACT" to "contracts","HANDOVER" to "award_handovers")[type]?:type
 fun route(type:String,id:UUID?):Map<String,String>? {
  if(type=="communication_broadcasts")return mapOf("group" to "Personal","workspace" to "Announcements","query" to "")
  val table=tableFor(type)
  val pair=routes[table]?:return null
  if(id==null)return null
  val node=jdbc.queryForList("select to_jsonb(t)::text data from $table t where id=?",id).firstOrNull()?.let{mapper.readTree(it["data"].toString())}?:return null
  val application=if(table=="applications")id.toString() else if(table=="approvals" && node.path("entity_type").asText()=="APPLICATION")node.path("entity_id").asText("") else node.path("application_id").asText("")
  if(application.isNotBlank() && table in setOf("applications","application_assignments","application_reviews","approvals"))return mapOf("group" to "Pre-Award","workspace" to (if(table=="application_reviews")"Internal Review" else if(table=="approvals")"Approvals" else "Applications"),"record" to application)
  val actualId=if(table=="award_handovers")node.path("award_id").asText("") else id.toString()
  return mapOf("group" to pair.first,"workspace" to pair.second,"record" to actualId)
 }
 private fun scopedRecord(rawType:String,id:UUID,user:UUID,roles:Set<String>):com.fasterxml.jackson.databind.JsonNode? {
  val type=tableFor(rawType)
  if(type !in routes && type!="communication_broadcasts")return null
  val target=route(type,id)?:return null
  if(target["group"] !in org.nhrc.grants.config.PlatformAccess.groups(roles))return null
  val definitions=catalog.baseDefinitions.filter{it.table==type}
  if(definitions.isNotEmpty() && definitions.none{catalog.allowed(it.key,roles,false)})return null
  val legacy=when(type){"applications"->"/api/applications/$id";"approvals"->"/api/approvals/$id";"application_reviews"->"/api/pregrant/reviews/$id";else->null}
  if(legacy!=null && !catalog.legacyAllowed(legacy,"GET",roles))return null
  val node=jdbc.queryForList("select to_jsonb(t)::text data from $type t where id=?",id).firstOrNull()?.let{mapper.readTree(it["data"].toString())}?:return null
  if(type=="communication_broadcasts")return if("SUPERADMIN" in roles || node.path("audience_roles").any{it.asText() in roles})node else null
  if(roles.all{it in setOf("RESEARCHER","RESEARCH_FELLOW")}) {
   if(listOf("owner_user_id","user_id","lead_researcher_id","researcher_id","reviewer_id","principal_investigator_id","assigned_user_id").any{node.path(it).asText()==user.toString()})return node
   val application=if(type=="applications")id else runCatching{UUID.fromString(if(type=="approvals" && node.path("entity_type").asText()=="APPLICATION")node.path("entity_id").asText() else node.path("application_id").asText())}.getOrNull()
   if(application!=null && jdbc.queryForObject("select exists(select 1 from applications a where a.id=? and (a.owner_user_id=? or a.lead_researcher_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active) or exists(select 1 from application_reviews r where r.application_id=a.id and r.reviewer_id=?)))",Boolean::class.java,application,user,user,user,user)==true)return node
   val award=if(type=="awards")id else runCatching{UUID.fromString(node.path("award_id").asText())}.getOrNull()
   if(award!=null && jdbc.queryForObject("select exists(select 1 from awards where id=? and principal_investigator_id=?)",Boolean::class.java,award,user)==true)return node
   return null
  }
  return node
 }
 fun content(row:Map<String,Any?>):BrandedEmail {
  val n=jdbc.queryForList("select n.*,u.display_name,u.email from notifications n join users u on u.id=n.user_id where n.id=?",row["notification_id"]).firstOrNull()
  if(n==null)throw IllegalStateException("The notification record is no longer available")
  val url=base();val id=n["id"].toString();val type=n["type"].toString();val entity=n["entity_type"]?.toString()?:"";val entityId=n["entity_id"] as? UUID
  val details=mutableListOf<Pair<String,String>>();val body=n["message"].toString().replace("→","to")
  details.add("Recipient" to n["display_name"].toString())
  val invitation=entity=="account_invitations" && entityId!=null
  val token=if(invitation)invitations.actionToken(entityId!!,n["user_id"] as UUID) else null
  val roles=jdbc.queryForList("select distinct r.name from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()) order by r.name",String::class.java,n["user_id"])
  if(type=="ACCOUNT") {
   details.add("Sign-in email" to n["email"].toString());details.add("Assigned roles" to roles.joinToString(", "))
   if(token!=null){val expiry=jdbc.queryForObject("select expires_at from account_invitations where id=?",OffsetDateTime::class.java,entityId);details.add("Setup link expires" to EmailTemplates.timestamp(expiry!!))}
  }
  val roleCodes=jdbc.queryForList("select distinct r.code from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now())",String::class.java,n["user_id"]).toSet()
  val recordNode=if(entityId!=null)scopedRecord(entity,entityId,n["user_id"] as UUID,roleCodes) else null
  val link=if(recordNode!=null)route(entity,entityId) else null
  if(entity=="communication_broadcasts" && entityId!=null && recordNode!=null){val notice=jdbc.queryForList("select b.expires_at,u.display_name author from communication_broadcasts b join users u on u.id=b.created_by where b.id=?",entityId).firstOrNull();if(notice!=null){details.add("Issued by" to notice["author"].toString());notice["expires_at"]?.let{details.add("Notice expires" to EmailTemplates.timestamp(it))}}}

  if(link!=null && entityId!=null) {
   val node=recordNode
   if(node!=null){for((key,label) in listOf("reference" to "Record reference","title" to "Project or record","stage" to "Current stage","status" to "Current status","due_date" to "Due date","deadline_at" to "Submission deadline")){val v=node.path(key).asText("");if(v.isNotBlank() && v!="null")details.add(label to if(key in setOf("stage","status"))v.replace('_',' ').lowercase().replaceFirstChar{it.titlecase()} else if(key in setOf("due_date","deadline_at"))runCatching{if(v.length==10)java.time.LocalDate.parse(v).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")) else EmailTemplates.timestamp(OffsetDateTime.parse(v))}.getOrDefault(v) else v)}
    for((key,label) in listOf("decision_note" to "Decision reason","comments" to "Review findings")){val v=node.path(key).asText("");if(v.isNotBlank()&&v!="null")details.add(label to v)}
    val currency=node.path("currency").asText("").takeIf{it!="null"}?:""
    for((key,label) in listOf("amount" to "Amount","advance_amount" to "Advance amount","amount_advanced" to "Advance amount","amount_accounted" to "Amount accounted","approved_amount" to "Approved amount","requested_amount" to "Requested amount","total_amount" to "Total amount","award_amount" to "Award value")){if(node.path(key).isNumber){val amount=java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).apply{maximumFractionDigits=6}.format(node.path(key).decimalValue());details.add(label to listOf(currency,amount).filter{it.isNotBlank()}.joinToString(" "))}}
    for((key,label) in listOf("retirement_due_date" to "Retirement due","expected_delivery_date" to "Expected delivery","expiry_date" to "Expiry date","scheduled_date" to "Scheduled date")){val v=node.path(key).asText("");if(v.isNotBlank()&&v!="null")details.add(label to runCatching{java.time.LocalDate.parse(v).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"))}.getOrDefault(v))}
    val owner=listOf("owner_user_id","assigned_user_id","researcher_id","principal_investigator_id","prepared_by","requested_by").firstNotNullOfOrNull{runCatching{UUID.fromString(node.path(it).asText())}.getOrNull()}
    if(owner!=null)jdbc.queryForList("select display_name from users where id=?",String::class.java,owner).firstOrNull()?.let{details.add("Responsible officer" to it)}
    for((field,table,label) in listOf(Triple("award_id","awards","Award"),Triple("application_id","applications","Application"))){val value=if(field=="application_id" && entity=="approvals" && node.path("entity_type").asText()=="APPLICATION")node.path("entity_id").asText() else node.path(field).asText();val uid=runCatching{UUID.fromString(value)}.getOrNull();if(uid!=null){val parent=jdbc.queryForList("select reference,title from $table where id=?",uid).firstOrNull();if(parent!=null)details.add(label to "${parent["reference"]} - ${parent["title"]}")}}
   }
  }
  // Reminders use task kinds rather than table names; the task view supplies the current owner and deadline.
  val task=if(entityId!=null)jdbc.queryForList("select t.*,u.display_name owner_name from lifecycle_tasks t left join users u on u.id=t.owner_id where t.id=? limit 1",entityId).firstOrNull()?.takeIf{it["owner_id"]==n["user_id"] || it["owner_id"]==null && it["responsible_role"] in roleCodes && it["responsible_role"]!="RESEARCHER"} else null
  if(task!=null){details.add("Task" to task["title"].toString());details.add("Task status" to task["status"].toString().replace('_',' '));task["due_date"]?.let{details.add("Due date" to it.toString())};details.add("Responsible officer or role" to (task["owner_name"]?.toString()?:task["responsible_role"].toString().replace('_',' ')))}
  details.add("Recorded at" to EmailTemplates.timestamp(n["created_at"]!!))
  val taskRoute=task?.let{route(it["kind"].toString(),it["id"] as UUID)}
  val target=link?:taskRoute
  val secondary=target?.let{"$url/?group=${enc(it["group"]!!)}&workspace=${enc(it["workspace"]!!)}&record=${enc(it["record"]?:entityId?.toString()?:"")}"}
  val next=when {token!=null->"Choose your password using the secure setup button below. Then sign in, review your roles and open your role dashboard. Do not forward this personal setup link."
   invitation->"This setup link has expired or has been replaced. Contact your administrator for a new welcome email."
   type=="ACCOUNT"->"Sign in with your registered email and the password you chose. Review your role guidance and dashboard. If you did not set up this account, contact your administrator immediately."
   type in setOf("REMINDER","MANUAL_REMINDER")->"Review the task, complete any outstanding work, or update its status and supporting evidence. Contact the responsible officer if you need assistance."
   type=="ASSIGNMENT"->"Review the assignment and record your acceptance or decline in My Work."
   type=="APPROVAL"->"Review the supporting evidence and record your decision if this approval is assigned to you."
   type in setOf("ANNOUNCEMENT","NOTICE")->"Read the communication and follow the instructions relevant to your assigned role."
   else->"Open this notification to review the update, then use the related workspace for any work assigned to you."}
  return BrandedEmail("NHRC eGMS | "+n["title"].toString(),if(token!=null)"Welcome to NHRC eGMS" else n["title"].toString(),"Hello ${n["display_name"]},",body,details.distinct(),next,if(token!=null)"Set up my account" else "View this notification",if(token!=null)"$url/?activate=${enc(token)}" else "$url/?notification=$id",url,if(token!=null)"$url/?group=Personal&workspace=Role+Guide" else secondary,if(token!=null)"Read your role guidance" else "Open related workspace",preheader=if(token!=null)"Your institutional account is ready. Set your password securely." else n["title"].toString(),securityNote=if(token!=null)"This setup link is personal, expires after 72 hours and can be used once. NHRC eGMS will never ask you to send your password by email. If you were not expecting an account, contact your administrator." else "This message was sent to your registered NHRC eGMS account. Access and approval authority follow your assigned institutional roles.")
 }
 fun sample(onboarding:Boolean):BrandedEmail {val url=base();return if(onboarding)BrandedEmail("NHRC eGMS | Welcome to your institutional account","Welcome to NHRC eGMS","Hello William,","Your institutional grant management account is ready. Your administrator has assigned the roles listed below. This preview contains demonstration details only.",listOf("Account" to "Named institutional account","Assigned roles" to "Grants Officer","Setup link expires" to "72 hours after issue"),"Use the secure setup link in your actual welcome email to choose a password. Then sign in and review your role guidance.","Set up my account","$url/?activate=demonstration-preview-not-a-valid-token",url,preheader="Your institutional account is ready.",securityNote="Preview only. Actual setup links are personal, single use and expire after 72 hours. Passwords are never emailed.") else BrandedEmail("NHRC eGMS | Grant review requires your attention","Grant review requires your attention","Hello William,","A grant application is ready for its assigned institutional review. This is a demonstration preview, not a real approval request.",listOf("Application" to "NHRC-DEMO-2026-001","Project" to "Demonstration grant application","Current stage" to "Internal review","Deadline" to "Review the deadline recorded in the application","Responsible function" to "Assigned reviewer"),"Review the recorded evidence and complete the task assigned to your role.","View this notification","$url/?notification=00000000-0000-0000-0000-000000000001",url,"$url/?group=Pre-Award&workspace=Internal+Review")}
}
