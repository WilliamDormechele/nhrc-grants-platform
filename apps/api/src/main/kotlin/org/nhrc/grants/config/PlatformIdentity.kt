package org.nhrc.grants.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class GrantUser(val id:UUID,val displayName:String,email:String,password:String,val roles:Set<String>):User(email,password,roles.map { org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$it") })

@Service
class PlatformIdentity(private val jdbc:JdbcTemplate):UserDetailsService {
 override fun loadUserByUsername(username:String):UserDetails {
   val rows=jdbc.queryForList("select u.id,u.email,u.display_name,c.password_hash,c.locked_until from users u join uat_credentials c on c.user_id=u.id where lower(u.email)=lower(?) and u.active=true and (c.locked_until is null or c.locked_until<now())",username)
   val row=rows.firstOrNull() ?: throw UsernameNotFoundException("Invalid sign-in")
   val roles=jdbc.queryForList("select distinct r.code from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=? and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now())",String::class.java,row["id"]).toSet()
   if(roles.isEmpty()) throw UsernameNotFoundException("No active institutional role")
   return GrantUser(row["id"] as UUID,row["display_name"].toString(),row["email"].toString(),row["password_hash"].toString(),roles)
 }
 fun current():GrantUser {
   val principal=SecurityContextHolder.getContext().authentication?.principal as? GrantUser ?: throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Please sign in")
   return try { loadUserByUsername(principal.username) as GrantUser } catch(ex:UsernameNotFoundException) {throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Your account no longer has active access")}
 }
}

object PlatformAccess {
 private val business=setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","RESEARCHER","FINANCE_OFFICER","FINANCE_APPROVER","PROCUREMENT_OFFICER","LABORATORY_OFFICER","GOVERNANCE_OFFICER","ADMIN","AUDITOR")
 fun groups(roles:Set<String>):Set<String> {
   if(roles.isEmpty())return emptySet()
  if("SUPERADMIN" in roles)return setOf("Executive","Pre-Award","Award Management","Finance & Accounts","Procurement","Laboratory","Research Governance","Performance","People & Organisation","Personal","Administration","IT & Security")
  val result=mutableSetOf("Personal","People & Organisation")
   if(roles.any { it in business }) result.addAll(listOf("Executive","Award Management","Performance"))
   if(roles.any { it in setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","RESEARCHER","FINANCE_OFFICER","FINANCE_APPROVER","ADMIN","AUDITOR") }) result.add("Pre-Award")
   if(roles.any { it in setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","FINANCE_OFFICER","FINANCE_APPROVER","ADMIN","AUDITOR") }) result.add("Finance & Accounts")
   if(roles.any { it in setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","PROCUREMENT_OFFICER","LABORATORY_OFFICER","ADMIN","AUDITOR") }) result.add("Procurement")
   if(roles.any { it in setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","LABORATORY_OFFICER","ADMIN","AUDITOR") }) result.add("Laboratory")
   if(roles.any { it in setOf("DIRECTOR","GRANTS_OFFICER","POST_AWARD_OFFICER","GOVERNANCE_OFFICER","ADMIN","AUDITOR") }) result.add("Research Governance")
   if(roles.any { it in setOf("ADMIN","AUDITOR") }) result.add("Administration")
   if(roles.any { it in setOf("IT_ADMIN","SUPERADMIN","AUDITOR") }) result.add("IT & Security")
   return result
 }
 fun allowed(path:String,method:String,roles:Set<String>):Boolean {
   if(path=="/api/intelligence/assistant" && method=="POST")return "Executive" in groups(roles)
   if(path.startsWith("/api/auth/") || path.startsWith("/api/operations/")) return true // dataset and record checks are enforced inside the operations controller
   val group=when {
     path.startsWith("/api/superadmin") || path.startsWith("/api/it") || path.startsWith("/api/system") -> "IT & Security"
     path.startsWith("/api/admin/users") || path.startsWith("/api/reference") || path.startsWith("/api/people") -> "People & Organisation"
     path.startsWith("/api/admin") || path.startsWith("/api/configuration") || path.startsWith("/api/organisation") -> "Administration"
     path.startsWith("/api/finance") -> "Finance & Accounts"
     path.startsWith("/api/procurement") -> "Procurement"
     path.startsWith("/api/laboratory") -> "Laboratory"
     path.startsWith("/api/governance") || path.startsWith("/api/contracts") -> "Research Governance"
     path.startsWith("/api/award") -> "Award Management"
     path.startsWith("/api/performance") -> "Performance"
     path.startsWith("/api/opportunit") || path.startsWith("/api/pregrant") || path.startsWith("/api/proposals") || path.startsWith("/api/applications") || path.startsWith("/api/approvals") -> "Pre-Award"
     path.startsWith("/api/personal") || path.startsWith("/api/notifications") || path.startsWith("/api/calendar") -> "Personal"
     else -> "Executive"
   }
   if(group !in groups(roles)) return false
   if(method in setOf("GET","HEAD","OPTIONS")) return true
   if("SUPERADMIN" in roles && group in setOf("Administration","People & Organisation","IT & Security"))return true
   if(method=="POST" && path.matches(Regex("/api/notifications/[a-fA-F0-9-]{36}/read")))return true // the controller enforces ownership; this is not a business mutation
   if("AUDITOR" in roles && roles.size==1) return false
   val controlledGrantAction = path=="/api/applications" || path=="/api/approvals" ||
     path.matches(Regex("/api/applications/[^/]+/(?:outcome|convert-to-award|eligibility|assignments)")) ||
     path.matches(Regex("/api/pregrant/applications/[^/]+/submission")) || path.startsWith("/api/pregrant/quality-checks/") ||
     path.matches(Regex("/api/proposals/[^/]+/reviews"))
   if(controlledGrantAction)return "GRANTS_OFFICER" in roles
   if(path.matches(Regex("/api/pregrant/applications/[^/]+/handover")))return roles.any {it in setOf("GRANTS_OFFICER","POST_AWARD_OFFICER")}
   val editors=when(group) {
     "IT & Security" -> setOf("IT_ADMIN","SUPERADMIN")
     "Administration","People & Organisation" -> setOf("ADMIN","GRANTS_OFFICER")
     "Finance & Accounts" -> setOf("FINANCE_OFFICER","FINANCE_APPROVER")
     "Procurement" -> setOf("PROCUREMENT_OFFICER")
     "Laboratory" -> setOf("LABORATORY_OFFICER")
     "Research Governance" -> setOf("GOVERNANCE_OFFICER","GRANTS_OFFICER")
     "Award Management","Performance" -> setOf("POST_AWARD_OFFICER","GRANTS_OFFICER","RESEARCHER")
     "Pre-Award" -> if(path.matches(Regex("/api/approvals/[^/]+/decision"))) setOf("DIRECTOR","FINANCE_APPROVER") else if(path.contains("/assignments") && !path.endsWith("/response") || path.endsWith("/eligibility") || path.contains("/discovery/")) setOf("DIRECTOR","GRANTS_OFFICER") else if(path.contains("/pregrant/reviews/") || path.endsWith("/budget")) setOf("GRANTS_OFFICER","RESEARCHER","DIRECTOR","FINANCE_OFFICER","FINANCE_APPROVER") else setOf("GRANTS_OFFICER","RESEARCHER","DIRECTOR")
     "Executive" -> setOf("DIRECTOR","GRANTS_OFFICER")
     else -> roles
   }
   return roles.any { it in editors }
 }
}

class ActorIdentityFilter(private val mapper:ObjectMapper,private val jdbc:JdbcTemplate,private val identity:PlatformIdentity):OncePerRequestFilter() {
 override fun shouldNotFilter(request:HttpServletRequest)=!request.requestURI.startsWith("/api/") || request.requestURI.startsWith("/api/auth/")
 override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,chain:FilterChain) {
   val actor=runCatching { identity.current() }.getOrNull()
   if(actor==null) {chain.doFilter(request,response);return}
   val uri=request.requestURI
   fun deny(){response.status=403;response.contentType="application/json";response.writer.write("{\"message\":\"This record is outside your assigned work\"}")}
   if(actor.roles==setOf("RESEARCHER")) {
     val application=Regex("/api/(?:applications|proposals|pregrant/applications|operations/proposals)/([a-fA-F0-9-]{36})(?:/|$)").find(uri)?.groupValues?.get(1)
     if(application!=null && jdbc.queryForObject("select count(*) from applications a where a.id=? and (a.owner_user_id=? or a.lead_researcher_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active=true) or (?=true and exists(select 1 from application_reviews r where r.application_id=a.id and r.reviewer_id=?)))",Long::class.java,UUID.fromString(application),actor.id,actor.id,actor.id,request.method=="GET",actor.id)!=1L){deny();return}
   }
   val assignment=Regex("/api/applications/assignments/([a-fA-F0-9-]{36})/response").find(uri)?.groupValues?.get(1)
   if(assignment!=null && jdbc.queryForObject("select count(*) from application_assignments where id=? and researcher_id=?",Long::class.java,UUID.fromString(assignment),actor.id)!=1L){deny();return}
   val personal=Regex("/api/(?:personal|notifications)/([a-fA-F0-9-]{36})(?:/work)?$").find(uri)?.groupValues?.get(1)
   if(personal!=null && personal!=actor.id.toString()){deny();return}
   if(request.method in setOf("GET","HEAD","OPTIONS") || request.contentType?.startsWith("application/json")!=true){chain.doFilter(request,response);return}
   val bytes=request.inputStream.readNBytes(1_048_577)
   if(bytes.size>1_048_576){response.sendError(413,"Request is too large");return}
   val tree=try { mapper.readTree(bytes) } catch(ex:Exception) {response.sendError(400,"Invalid request");return}
   if(tree is com.fasterxml.jackson.databind.node.ObjectNode) {
     listOf("actorId","actor","createdBy","requestedBy","assignedBy","decidedBy","reviewedBy","recordedBy","uploadedBy","checkedBy","confirmedBy","preparedBy","acceptedBy","reviewerId").forEach { key ->
       if(tree.has(key) && key!="reviewerId") tree.put(key,actor.id.toString())
     }
     if(tree.has("reviewerId") && (uri.contains("/pregrant/reviews/") || uri.contains("/discovery/opportunities/")))tree.put("reviewerId",actor.id.toString())
   }
   val payload=mapper.writeValueAsBytes(tree)
   chain.doFilter(object:HttpServletRequestWrapper(request){
     override fun getContentLength()=payload.size
     override fun getContentLengthLong()=payload.size.toLong()
     override fun getInputStream():ServletInputStream {
       val stream=ByteArrayInputStream(payload)
       return object:ServletInputStream(){override fun read()=stream.read();override fun isFinished()=stream.available()==0;override fun isReady()=true;override fun setReadListener(listener:ReadListener?) {}}
     }
     override fun getReader()=BufferedReader(InputStreamReader(inputStream,Charsets.UTF_8))
   },response)
 }
}

@RestController
@RequestMapping("/api/auth")
class IdentityController(private val identity:PlatformIdentity) {
 @GetMapping("/csrf") fun csrf(request:HttpServletRequest):Map<String,String> {val token=request.getAttribute(CsrfToken::class.java.name) as CsrfToken;return mapOf("token" to token.token,"headerName" to token.headerName)}
 @GetMapping("/me") fun me():Map<String,Any> {val actor=identity.current();return mapOf("id" to actor.id,"name" to actor.displayName,"email" to actor.username,"roles" to actor.roles,"groups" to PlatformAccess.groups(actor.roles))}
}
