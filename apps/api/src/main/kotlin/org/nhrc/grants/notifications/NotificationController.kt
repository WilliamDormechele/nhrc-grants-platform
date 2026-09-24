package org.nhrc.grants.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val jdbc:JdbcTemplate){
 @GetMapping("/{userId}") fun list(@PathVariable userId:UUID,@RequestParam(defaultValue="false") unreadOnly:Boolean):List<Map<String,Any?>> = if(unreadOnly) jdbc.queryForList("select * from notifications where user_id=? and read_at is null order by created_at desc limit 300",userId) else jdbc.queryForList("select * from notifications where user_id=? order by created_at desc limit 300",userId)
 @PostMapping("/{id}/read") fun read(@PathVariable id:UUID):Map<String,Any>{jdbc.update("update notifications set read_at=coalesce(read_at,now()) where id=?",id);return mapOf("id" to id,"read" to true)}
}
