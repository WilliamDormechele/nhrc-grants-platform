package org.nhrc.grants.workbench

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.DateTimeException
import java.util.UUID

typealias GrantRow = Map<String, Any?>
data class GrantActor(val id: UUID, val name: String, val roles: Set<String>, val development: Boolean) {
    fun hasAny(allowed: Set<String>) = roles.any { it in allowed }
    fun requireAny(allowed: Set<String>) {
        if(!hasAny(allowed)) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your assigned role does not permit this action")
    }
}
data class GrantRecordInput(val values: Map<String,Any?>, val version: Long? = null)
data class GrantActionInput(val action: String, val version: Long, val requestId: UUID, val note: String, val evidenceUrl: String? = null, val values: Map<String,Any?> = emptyMap())

@Component
class WorkbenchStore(val jdbc: JdbcTemplate, val mapper: ObjectMapper) {
    private val type = object: TypeReference<LinkedHashMap<String,Any?>>() {}
    fun decode(text: String): GrantRow = mapper.readerFor(type).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text)
    fun encode(value: Any?): String = mapper.writeValueAsString(value)
    fun rows(query: String, vararg parameters: Any?): List<GrantRow> = jdbc.query(
        "select to_jsonb(workbench_row)::text from ($query) workbench_row",
        RowMapper { rs, _ -> decode(rs.getString(1)) }, *parameters
    )
    /** table must come from the server catalogue or a literal within a service. */
    fun one(table: String, id: UUID, lock: Boolean = false): GrantRow = rows(
        "select * from $table where id=?" + if(lock) " for update" else "",id
    ).firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found")
    fun version(row: GrantRow) = (row["record_version"] as? Number)?.toLong() ?: 0L
    fun checkVersion(row: GrantRow, expected: Long?) {
        if(expected == null) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"The record version is required")
        if(version(row) != expected) throw ResponseStatusException(HttpStatus.CONFLICT,"This record has changed. Reload it before saving your changes")
    }
    fun uuid(row: GrantRow, name: String): UUID? = row[name]?.toString()?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
    fun decimal(row: GrantRow, name: String): BigDecimal = row[name]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun owner(key: String, id: UUID): UUID? = rows("select created_by from grant_record_owners where resource_key=? and record_id=?",key,id).firstOrNull()?.let { uuid(it,"created_by") }
    fun registerOwner(key: String, id: UUID, actor: GrantActor) {
        jdbc.update("insert into grant_record_owners(resource_key,record_id,created_by) values (?,?,?) on conflict do nothing",key,id,actor.id)
    }
    fun event(key: String, id: UUID, action: String, actor: GrantActor, before: Any?, after: Any?, note: String? = null) {
        jdbc.update("insert into grant_workspace_events(entity_type,entity_id,action,actor_user_id,previous_value,new_value,note) values (?,?,?,?,?::jsonb,?::jsonb,?)",key,id,action,actor.id,encode(before),encode(after),note)
    }
    fun hash(value: Any?): String {
        fun sorted(v: Any?): Any? = when(v) {
            is Map<*,*> -> v.entries.associate { it.key.toString() to sorted(it.value) }.toSortedMap()
            is List<*> -> v.map { sorted(it) }
            else -> v
        }
        return MessageDigest.getInstance("SHA-256").digest(encode(sorted(value)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun bind(field: GrantField): String = when(field.kind) {
        "lookup" -> "?::uuid"; "date" -> "?::date"; "datetime" -> "?::timestamptz"
        "tags" -> "array(select jsonb_array_elements_text(?::jsonb))"; else -> "?"
    }
    fun parameter(field: GrantField, value: Any?): Any? = if(field.kind=="tags") encode(value ?: emptyList<String>()) else value
    fun wire(row: GrantRow): GrantRow {
        fun convert(value: Any?): Any? = when(value) {
            is BigDecimal -> value.toPlainString()
            is Map<*,*> -> value.entries.associate { it.key.toString() to convert(it.value) }
            is List<*> -> value.map { convert(it) }
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        return convert(row) as GrantRow
    }
}

@Component
class WorkbenchAuth(private val store: WorkbenchStore, private val environment: Environment) {
    fun development() = environment.getProperty("nhrc.auth.mode","development") == "development"
    fun actor(request: HttpServletRequest): GrantActor {
        val dev=development()
        val uat=request.getHeader("X-UAT-User")
        val row: GrantRow = if(dev) {
            if(uat.isNullOrBlank()) throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Select a UAT user before opening this workspace")
            val id=try { UUID.fromString(uat) } catch(_: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid UAT identity") }
            store.rows("select id,display_name from users where id=? and active and email like '%@nhrc.local'",id).firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"The selected UAT account is not active")
        } else {
            if(uat != null) throw ResponseStatusException(HttpStatus.FORBIDDEN,"UAT identity selection is disabled outside development")
            val authentication=SecurityContextHolder.getContext().authentication
            if(authentication==null || !authentication.isAuthenticated || authentication.name=="anonymousUser") throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in before continuing")
            store.rows("select id,display_name from users where external_subject=? and active",authentication.name).firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your signed-in account has not been provisioned for NHRC Grants")
        }
        val id=UUID.fromString(row["id"].toString())
        val roles=store.rows("""select distinct r.code from user_roles ur join roles r on r.id=ur.role_id
            join organisation_units ou on ou.id=ur.scope_id
            where ur.user_id=? and ur.scope_type='INSTITUTION' and ou.code='NHRC'
            and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>now())""",id)
            .map { it["code"].toString() }.toSet()
        return GrantActor(id,row["display_name"].toString(),roles,dev)
    }
    fun identity(request: HttpServletRequest): GrantRow {
        val accounts=if(development()) store.rows("select id,display_name from users where active and email like '%@nhrc.local' order by display_name") else emptyList()
        val actor=try { actor(request) } catch(ex: ResponseStatusException) { if(development() && ex.statusCode.value()==401) null else throw ex }
        return mapOf("development" to development(),"uatUsers" to accounts,"actor" to actor)
    }
    fun canRead(resource: GrantResource, row: GrantRow, actor: GrantActor): Boolean {
        if(!actor.hasAny(resource.readers)) return false
        if(resource.key=="support") return actor.id==store.uuid(row,"requester_id") || actor.hasAny(setOf("ADMIN"))
        if(resource.key=="applications" && !actor.hasAny(resource.readers - WorkbenchCatalogue.researchRoles)) {
            if(actor.id in listOf(store.uuid(row,"lead_researcher_id"),store.uuid(row,"owner_user_id"))) return true
            return store.rows("select id from application_reviews where application_id=? and reviewer_id=?",store.uuid(row,"id"),actor.id).isNotEmpty()
        }
        if(resource.awardColumn != null && !actor.hasAny(resource.readers - WorkbenchCatalogue.researchRoles)) {
            val awardId=store.uuid(row,resource.awardColumn) ?: return false
            return store.uuid(store.one("awards",awardId),"principal_investigator_id")==actor.id
        }
        return true
    }
    fun requireRead(resource: GrantResource, row: GrantRow, actor: GrantActor) {
        if(!canRead(resource,row,actor)) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You do not have access to this record")
    }
    fun requireWrite(resource: GrantResource, row: GrantRow?, actor: GrantActor) {
        actor.requireAny(resource.writers)
        if(row != null) requireRead(resource,row,actor)
        if(resource.key=="researchers" && !actor.hasAny(WorkbenchCatalogue.profileWriters)) {
            if(row==null || store.uuid(row,"user_id")!=actor.id) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You may edit only your own researcher profile")
        }
    }
}

@RestControllerAdvice(basePackages=["org.nhrc.grants.workbench"])
class WorkbenchErrors {
    @ExceptionHandler(IllegalArgumentException::class,DateTimeException::class)
    fun invalid(ex: RuntimeException): ResponseEntity<GrantRow> = ResponseEntity.badRequest().body(mapOf("message" to (ex.message ?: "Check the supplied values")))
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<GrantRow> = ResponseEntity.status(409).body(mapOf("message" to "A reference is already used or a linked record is invalid. Check the form and reload the register"))
    @ExceptionHandler(EmptyResultDataAccessException::class)
    fun missing(): ResponseEntity<GrantRow> = ResponseEntity.status(404).body(mapOf("message" to "Record not found"))
    @ExceptionHandler(ResponseStatusException::class)
    fun status(ex: ResponseStatusException): ResponseEntity<GrantRow> = ResponseEntity.status(ex.statusCode).body(mapOf("message" to (ex.reason ?: "The request could not be completed")))
}
