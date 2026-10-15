package org.nhrc.grants.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID
import java.time.OffsetDateTime

data class EventPolicy(val eventCode:String,val inApp:Boolean,val email:Boolean,val recipientRoles:List<String>)
data class SenderPolicy(val roleCode:String,val announcements:Boolean,val notifications:Boolean,val reminders:Boolean)
data class CommunicationConfig(val automaticReminders:Boolean,val lookaheadDays:Int,val repeatDays:Int,val events:List<EventPolicy>,val senders:List<SenderPolicy>,val reason:String)
data class Broadcast(val title:String,val message:String,val category:String,val audienceRoles:List<String>,val expiresAt:OffsetDateTime?,val reason:String)
data class ReminderRequest(val kind:String,val id:UUID,val reason:String)

@Service
class LifecycleCommunications(private val jdbc:JdbcTemplate,private val delivery:GmailDelivery) {
 fun recipients(task:Map<String,Any?>):List<UUID> = jdbc.queryForList("""select distinct u.id from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id
 where u.active and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now())
 and (?::uuid is not null and u.id=?::uuid or ?::uuid is null and r.code=?)""",UUID::class.java,task["owner_id"],task["owner_id"],task["owner_id"],task["responsible_role"])
 fun remind(task:Map<String,Any?>,manual:Boolean,reason:String):Int {
  var count=0
  val policy=jdbc.queryForMap("select * from communication_policies where event_code='REMINDER'")
  if(policy["in_app"]!=true && policy["email"]!=true)return 0
  for(uid in recipients(task)) {
   val allowed=jdbc.queryForObject("select exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()) and ?::jsonb ?? r.code)",Boolean::class.java,uid,policy["recipient_roles"].toString())==true
   if(!allowed)continue
   // Atomic claim prevents duplicate reminders across concurrent scheduled/manual requests.
   val claimed=jdbc.queryForList("""insert into reminder_dispatches(task_type,task_id,user_id) values(?,?,?) on conflict(task_type,task_id,user_id) do update set last_sent_at=now()
    where reminder_dispatches.last_sent_at < now() - case when ? then interval '5 minutes' else (select repeat_days from communication_settings where id=true)*interval '1 day' end returning user_id""",task["kind"],task["id"],uid,manual)
   if(claimed.isEmpty())continue
   jdbc.update("insert into notifications(user_id,type,title,message,entity_type,entity_id) values(?,?,?,?,?,?)",uid,if(manual)"MANUAL_REMINDER" else "REMINDER",("Action required - "+task["title"]).take(255),"${task["workspace"]}: ${task["title"]}. Status: ${task["status"].toString().replace('_',' ')}. Due: ${task["due_date"]?:"No recorded deadline"}. $reason",task["kind"],task["id"])
   count++
  };return count
 }
 @Scheduled(fixedDelayString="\${nhrc.communications.delay-ms:300000}",initialDelayString="\${nhrc.communications.initial-delay-ms:90000}")
 @Transactional fun scheduledReminders() {
  if(jdbc.queryForObject("select automatic_reminders from communication_settings where id=true",Boolean::class.java)!=true)return
  val tasks=jdbc.queryForList("select * from lifecycle_tasks where due_date<=current_date+(select lookahead_days from communication_settings where id=true) or due_date is null and status in ('NOT_STARTED','PENDING','IN_PROGRESS','PREPARING','READY','DRAFT','RETURNED','OUTSTANDING','PARTIALLY_RETIRED')")
  tasks.forEach {remind(it,false,"Please complete the recorded work or update its status and evidence.")}
 }
 @Scheduled(fixedDelayString="\${nhrc.mail.delay-ms:15000}",initialDelayString="\${nhrc.mail.initial-delay-ms:30000}")
 @Transactional fun deliverEmails() {
  jdbc.update("update email_outbox o set status='CANCELLED' from notifications n,communication_policies p where n.id=o.notification_id and p.event_code=case when n.type='MANUAL_REMINDER' then 'REMINDER' when n.type='NOTICE' then 'ANNOUNCEMENT' else n.type end and not p.email and o.status in ('QUEUED','RETRY')")
  val rows=jdbc.queryForList("select * from email_outbox where status in ('QUEUED','RETRY') and next_attempt_at<=now() order by created_at for update skip locked limit 30")
  for(r in rows)try {
   if(!delivery.deliver(r))continue
   jdbc.update("update email_outbox set status='SENT',attempts=attempts+1,sent_at=now(),last_error=null where id=?",r["id"])
  }catch(ex:Exception){jdbc.update("update email_outbox set status=case when attempts>=4 then 'FAILED' else 'RETRY' end,attempts=attempts+1,next_attempt_at=now()+interval '5 minutes',last_error=? where id=?",ex.javaClass.simpleName,r["id"])}
 }
}

@RestController
@RequestMapping("/api/operations/communications")
class CommunicationsController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val mapper:ObjectMapper,private val service:LifecycleCommunications,private val delivery:GmailDelivery) {
 private fun superadmin(){if("SUPERADMIN" !in identity.current().roles)throw ResponseStatusException(HttpStatus.FORBIDDEN,"Superadmin is required")}
 private fun permitted(capability:String):Boolean {val roles=identity.current().roles;return "SUPERADMIN" in roles || jdbc.queryForList("select role_code from communication_sender_permissions where $capability=true",String::class.java).any {it in roles}}
 private fun audit(action:String,note:String,id:UUID=UUID.nameUUIDFromBytes("communications".toByteArray())) {jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('communications',?,?,?,?)",id,identity.current().id,action,note)}
 @GetMapping fun read():Map<String,Any?> {
  val actor=identity.current();val isSuper="SUPERADMIN" in actor.roles
  val broadcasts=jdbc.queryForList("select b.*,u.display_name author from communication_broadcasts b join users u on u.id=b.created_by where b.withdrawn_at is null and (b.expires_at is null or b.expires_at>now()) order by b.created_at desc limit 200").filter {isSuper || mapper.readTree(it["audience_roles"].toString()).any {r->r.asText() in actor.roles}}
  val tasks=jdbc.queryForList("select t.*,u.display_name owner_name from lifecycle_tasks t left join users u on u.id=t.owner_id order by due_date nulls last,title").filter {isSuper || it["owner_id"]==actor.id || it["responsible_role"] in actor.roles && it["responsible_role"]!="RESEARCHER"}
  return mapOf("superadmin" to isSuper,"canAnnounce" to permitted("announcements"),"canNotify" to permitted("notifications"),"canRemind" to permitted("reminders"),"roles" to jdbc.queryForList("select code,name,description from roles order by name"),"broadcasts" to broadcasts,"tasks" to tasks,"settings" to if(isSuper)jdbc.queryForMap("select * from communication_settings where id=true") else null,"policies" to if(isSuper)jdbc.queryForList("select * from communication_policies order by name") else emptyList<Any>(),"senders" to if(isSuper)jdbc.queryForList("select * from communication_sender_permissions order by role_code") else emptyList<Any>(),"emailMode" to delivery.mode(),"outbox" to if(isSuper)jdbc.queryForList("select o.id,o.recipient,o.subject,o.status,o.delivery_mode,o.attempts,o.sent_at,o.last_error,o.created_at from email_outbox o order by created_at desc limit 100") else emptyList<Any>())
 }
 @PutMapping("/settings") @Transactional fun settings(@RequestBody r:CommunicationConfig):Map<String,Boolean> {
  superadmin();require(r.reason.isNotBlank() && r.reason.length<=2000 && r.lookaheadDays in 0..90 && r.repeatDays in 1..30)
  val before=jdbc.queryForObject("select jsonb_build_object('settings',(select to_jsonb(s) from communication_settings s where id=true),'policies',(select jsonb_agg(p) from communication_policies p),'senders',(select jsonb_agg(s) from communication_sender_permissions s))::text",String::class.java)
  val roles=jdbc.queryForList("select code from roles",String::class.java).toSet();val codes=jdbc.queryForList("select event_code from communication_policies",String::class.java).toSet()
  require(r.events.map {it.eventCode}.toSet()==codes && r.events.size==codes.size && r.events.all {e->e.recipientRoles.all {it in roles}} && r.senders.map {it.roleCode}.toSet()==roles && r.senders.size==roles.size)
  jdbc.update("update communication_settings set automatic_reminders=?,lookahead_days=?,repeat_days=?,updated_at=now(),updated_by=? where id=true",r.automaticReminders,r.lookaheadDays,r.repeatDays,identity.current().id)
  r.events.forEach {jdbc.update("update communication_policies set in_app=?,email=?,recipient_roles=?::jsonb where event_code=?",it.inApp,it.email,mapper.writeValueAsString(it.recipientRoles.distinct()),it.eventCode)}
  r.senders.forEach {jdbc.update("update communication_sender_permissions set announcements=?,notifications=?,reminders=? where role_code=?",it.announcements,it.notifications,it.reminders,it.roleCode)}
  jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,before_data,after_data,note) values ('communications',?,?,'COMMUNICATION_POLICY_CHANGED',?::jsonb,?::jsonb,?)",UUID.nameUUIDFromBytes("communications".toByteArray()),identity.current().id,before,mapper.writeValueAsString(r),r.reason);return mapOf("saved" to true)
 }
 @PostMapping("/broadcasts") @Transactional fun broadcast(@RequestBody r:Broadcast):Map<String,Any> {
  if(r.category !in setOf("ANNOUNCEMENT","NOTICE") || !permitted(if(r.category=="ANNOUNCEMENT")"announcements" else "notifications"))throw ResponseStatusException(HttpStatus.FORBIDDEN,"You are not authorised to send this communication")
  require(r.title.isNotBlank() && r.title.length<=255 && r.message.isNotBlank() && r.message.length<=8000 && r.reason.isNotBlank() && r.audienceRoles.isNotEmpty() && (r.expiresAt==null || r.expiresAt.isAfter(OffsetDateTime.now())))
  val allRoles=jdbc.queryForList("select code from roles",String::class.java);require(r.audienceRoles.all {it in allRoles})
  val id=UUID.randomUUID();val audience=mapper.writeValueAsString(r.audienceRoles.distinct())
  jdbc.update("insert into communication_broadcasts(id,title,message,category,audience_roles,created_by,expires_at) values(?,?,?,?,?::jsonb,?,?)",id,r.title,r.message,r.category,audience,identity.current().id,r.expiresAt)
  val count=jdbc.update("""insert into notifications(user_id,type,title,message,entity_type,entity_id) select distinct u.id,?,?,?,'communication_broadcasts',? from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where u.active and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()) and ?::jsonb ?? r.code and exists(select 1 from communication_policies p where p.event_code='ANNOUNCEMENT' and (p.in_app or p.email) and p.recipient_roles ?? r.code)""",r.category,r.title,r.message,id,audience)
  jdbc.update("update communication_broadcasts set recipients=? where id=?",count,id);audit("BROADCAST_SENT",r.reason,id);return mapOf("id" to id,"recipients" to count)
 }
 @PostMapping("/broadcasts/{id}/withdraw") @Transactional fun withdraw(@PathVariable id:UUID,@RequestBody body:Map<String,String>):Map<String,Boolean> {superadmin();require(!body["reason"].isNullOrBlank());jdbc.update("update communication_broadcasts set withdrawn_at=now() where id=?",id);jdbc.update("update notifications set in_app_visible=false where entity_type='communication_broadcasts' and entity_id=?",id);jdbc.update("update email_outbox set status='CANCELLED' where status in ('QUEUED','RETRY') and notification_id in (select id from notifications where entity_type='communication_broadcasts' and entity_id=?)",id);audit("BROADCAST_WITHDRAWN",body["reason"]!!,id);return mapOf("saved" to true)}
 @PostMapping("/remind") @Transactional fun remind(@RequestBody r:ReminderRequest):Map<String,Int> {
  if(!permitted("reminders"))throw ResponseStatusException(HttpStatus.FORBIDDEN,"Reminder permission is required")
  require(r.reason.isNotBlank() && r.reason.length<=2000)
  val task=jdbc.queryForList("select * from lifecycle_tasks where kind=? and id=?",r.kind,r.id).firstOrNull()?:throw ResponseStatusException(HttpStatus.CONFLICT,"This task is complete or no longer exists")
  val actor=identity.current();if("SUPERADMIN" !in actor.roles && task["owner_id"]!=actor.id && !(task["responsible_role"] in actor.roles && task["responsible_role"]!="RESEARCHER"))throw ResponseStatusException(HttpStatus.FORBIDDEN,"This task is outside your assigned work")
  val count=service.remind(task,true,r.reason);audit("MANUAL_REMINDER",r.reason,r.id);return mapOf("recipients" to count)
 }
 @PostMapping("/outbox/{id}/retry") fun retry(@PathVariable id:UUID):Map<String,Boolean> {superadmin();val changed=jdbc.update("update email_outbox set status='QUEUED',attempts=0,next_attempt_at=now(),last_error=null where id=? and status in ('FAILED','RETRY')",id);if(changed==0)throw ResponseStatusException(HttpStatus.CONFLICT,"Only failed or deferred emails can be retried");audit("EMAIL_RETRY","Manual retry",id);return mapOf("queued" to true)}
}
