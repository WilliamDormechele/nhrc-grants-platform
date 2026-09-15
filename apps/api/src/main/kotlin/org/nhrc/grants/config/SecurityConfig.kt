package org.nhrc.grants.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(
    @Value("\${nhrc.auth.mode:development}") private val authMode: String
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
        http.authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/api/public/**").permitAll()
            if (authMode == "development") {
                it.anyRequest().permitAll()
            } else {
                it.anyRequest().authenticated()
            }
        }
        if (authMode != "development") {
            http.oauth2ResourceServer { it.jwt(withDefaults()) }
        }
        return http.build()
    }
}
