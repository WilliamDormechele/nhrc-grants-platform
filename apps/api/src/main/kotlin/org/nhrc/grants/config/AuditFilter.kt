package org.nhrc.grants.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class AuditFilter(private val jdbc: JdbcTemplate): OncePerRequestFilter() {
    private val logger=LoggerFactory.getLogger(javaClass)
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method in setOf("GET","HEAD","OPTIONS") || request.requestURI.startsWith("/actuator")
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,filterChain: FilterChain) {
        var error:String?=null
        val correlation=request.getHeader("X-Request-Id")?.take(120) ?: UUID.randomUUID().toString()
        try { filterChain.doFilter(request,response) } catch(ex:Exception){ error=ex.javaClass.simpleName; throw ex } finally {
            try {
                jdbc.update("""insert into audit_events(id,actor_subject,event_type,entity_type,entity_id,action,reason,correlation_id,source_ip,user_agent)
                    values (?,?,?,?,?,?,?,?,?::inet,?)""",
                    UUID.randomUUID(),org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.name?.take(255) ?: "Unknown actor",
                    "HTTP_REQUEST","HTTP_ENDPOINT",request.requestURI.take(255),"HTTP_${request.method}",
                    "HTTP status ${response.status}"+(error?.let { "; $it" } ?: ""),
                    correlation,request.remoteAddr,request.getHeader("User-Agent"))
            } catch(ex:Exception) { logger.error("Failed to record mutation audit event. Correlation={}",correlation,ex) }
        }
    }
}
