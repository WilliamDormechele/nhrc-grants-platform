package org.nhrc.grants.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val jdbc:JdbcTemplate,private val identity:org.nhrc.grants.config.PlatformIdentity,private val emails:NotificationEmails){
 @GetMapping("/unread-count") fun count():Map<String,Long> = mapOf("unread" to (jdbc.queryForObject("select count(*) from notifications where user_id=? and in_app_visible and read_at is null",Long::class.java,identity.current().id)?:0L))
 @GetMapping("/inbox") fun inbox(@RequestParam(required=false) focus:UUID?,@RequestParam(defaultValue="false") unreadOnly:Boolean):Map<String,Any> {
  val user=identity.current().id
  val rows=jdbc.queryForList("select id,title,message,type,read_at,created_at,entity_type,entity_id from notifications where user_id=? and in_app_visible and (?=false or read_at is null) order by created_at desc limit 100",user,unreadOnly).toMutableList()
  if(focus!=null && rows.none{it["id"]==focus})rows.addAll(0,jdbc.queryForList("select id,title,message,type,read_at,created_at,entity_type,entity_id from notifications where user_id=? and id=? and in_app_visible",user,focus))
  return mapOf("unread" to count()["unread"]!!,"notifications" to rows.map {it.toMutableMap().apply {val route=emails.route(it["entity_type"]?.toString()?:"",it["entity_id"] as? UUID,user);if(route!=null)put("route",route)}})
 }
 @GetMapping("/{userId}") fun list(@PathVariable userId:UUID,@RequestParam(defaultValue="false") unreadOnly:Boolean):List<Map<String,Any?>> {
  if(userId!=identity.current().id)throw ResponseStatusException(HttpStatus.FORBIDDEN,"This inbox belongs to another account")
  return if(unreadOnly)jdbc.queryForList("select * from notifications where in_app_visible=true and user_id=? and read_at is null order by created_at desc limit 300",userId) else jdbc.queryForList("select * from notifications where in_app_visible=true and user_id=? order by created_at desc limit 300",userId)
 }
 @PostMapping("/{id}/read") fun read(@PathVariable id:UUID):Map<String,Any>{val changed=jdbc.update("update notifications set read_at=coalesce(read_at,now()) where id=? and user_id=? and in_app_visible",id,identity.current().id);if(changed==0)throw ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found");return mapOf("id" to id,"read" to true)}
}
