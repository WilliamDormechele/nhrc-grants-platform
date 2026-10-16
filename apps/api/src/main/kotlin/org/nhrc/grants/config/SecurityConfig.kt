package org.nhrc.grants.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.web.access.intercept.AuthorizationFilter

@Configuration
class SecurityConfig(private val environment:Environment,private val mapper:ObjectMapper,private val identity:PlatformIdentity,private val jdbc:JdbcTemplate,private val catalog:org.nhrc.grants.operations.OperationalCatalog) {
 @Bean fun passwordEncoder():PasswordEncoder=BCryptPasswordEncoder(12)
 @Bean fun securityFilterChain(http:HttpSecurity):SecurityFilterChain {
   val mode=environment.getProperty("nhrc.auth.mode","uat")
   http.cors { }
   http.authorizeHttpRequests { auth ->
     auth.requestMatchers("/api/public/**","/actuator/health/**","/api/auth/csrf","/api/auth/login","/error").permitAll()
     if(mode=="development") auth.anyRequest().permitAll()
     else auth.anyRequest().access { supplier,context ->
       val principal=supplier.get()
       val roles=if(mode=="uat" && principal.isAuthenticated && principal.name!="anonymousUser")runCatching { identity.current().roles }.getOrDefault(emptySet()) else principal.authorities.map { it.authority.removePrefix("ROLE_") }.toSet()
       AuthorizationDecision(principal.isAuthenticated && principal.name!="anonymousUser" && PlatformAccess.allowed(context.request.requestURI,context.request.method,roles) && catalog.legacyAllowed(context.request.requestURI,context.request.method,roles))
     }
   }
   http.exceptionHandling { it.authenticationEntryPoint { _,response,_ -> response.status=401;response.contentType="application/json";response.writer.write("{\"message\":\"Please sign in to continue\"}") };it.accessDeniedHandler { _,response,_ -> response.status=403;response.contentType="application/json";response.writer.write("{\"message\":\"Your role does not permit this action\"}") } }
   if(mode=="uat") {
     http.formLogin { it.loginProcessingUrl("/api/auth/login").successHandler { _,response,_ -> response.contentType="application/json";response.writer.write("{\"signedIn\":true}") }.failureHandler { _,response,_ -> response.status=401;response.contentType="application/json";response.writer.write("{\"message\":\"Email or password is incorrect\"}") } }
     http.logout { it.logoutUrl("/api/auth/logout").logoutSuccessHandler { _,response,_ -> response.contentType="application/json";response.writer.write("{\"signedOut\":true}") }.invalidateHttpSession(true).deleteCookies("JSESSIONID") }
     http.addFilterAfter(ActorIdentityFilter(mapper,jdbc,identity),UsernamePasswordAuthenticationFilter::class.java)
     http.addFilterAfter(AuditFilter(jdbc),AuthorizationFilter::class.java)
   } else {
     http.csrf { it.disable() }
     if(mode!="development") http.oauth2ResourceServer { it.jwt { } }
   }
   return http.build()
 }
}
