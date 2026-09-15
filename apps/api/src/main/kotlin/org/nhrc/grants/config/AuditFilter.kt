package org.nhrc.grants.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class AuditFilter(private val jdbc: JdbcTemplate): OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method in setOf("GET","HEAD","OPTIONS") || request.requestURI.startsWith("/actuator")
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,filterChain: FilterChain) {
        var error:String?=null
        try { filterChain.doFilter(request,response) } catch(ex:Exception){ error=ex.message; throw ex } finally {
            try {
                jdbc.update("""insert into audit_events(id,event_type,entity_type,entity_id,action,reason,correlation_id,source_ip,user_agent) values (?,?,?,?,?,?,?,?,?)""",
                    UUID.randomUUID(),"HTTP_REQUEST","HTTP_ENDPOINT",request.requestURI,"HTTP_${request.method}",error,
                    request.getHeader("X-Request-Id"),request.remoteAddr,request.getHeader("User-Agent"))
            } catch(_:Exception) { }
        }
    }
}
