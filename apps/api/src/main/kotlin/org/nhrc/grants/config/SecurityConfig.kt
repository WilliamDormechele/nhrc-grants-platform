package org.nhrc.grants.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(private val environment: Environment) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val mode = environment.getProperty("nhrc.auth.mode", "development")
        http.csrf { it.disable() }
        http.authorizeHttpRequests { auth ->
            auth.requestMatchers("/api/public/**", "/actuator/health/**").permitAll()
            if (mode == "development") auth.anyRequest().permitAll() else auth.anyRequest().authenticated()
        }
        if (mode != "development") http.oauth2ResourceServer { it.jwt { } }
        return http.build()
    }
}
