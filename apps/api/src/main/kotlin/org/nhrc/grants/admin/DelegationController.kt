package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.util.UUID

data class DelegationCreate(val delegatorId:UUID,val delegateId:UUID,val scopeCode:String,val validFrom:OffsetDateTime,val validUntil:OffsetDateTime,val reason:String?)
@RestController
@RequestMapping("/api/admin/delegations")
class DelegationController(private val jdbc:JdbcTemplate){
 @GetMapping fun list():List<Map<String,Any?>> = jdbc.queryForList("select d.*,a.display_name delegator,b.display_name delegate from delegations d join users a on a.id=d.delegator_id join users b on b.id=d.delegate_id order by d.valid_until desc limit 300")
 @PostMapping fun create(@RequestBody r:DelegationCreate):Map<String,Any>{ require(r.validUntil.isAfter(r.validFrom)){"validUntil must be after validFrom"}; val id=UUID.randomUUID(); jdbc.update("insert into delegations(id,delegator_id,delegate_id,scope_code,valid_from,valid_until,reason) values (?,?,?,?,?,?,?)",id,r.delegatorId,r.delegateId,r.scopeCode,r.validFrom,r.validUntil,r.reason); return mapOf("id" to id) }
}
