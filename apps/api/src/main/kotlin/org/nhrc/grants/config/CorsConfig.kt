package org.nhrc.grants.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsConfig {
 @Bean fun corsFilter():CorsFilter {
  val c=CorsConfiguration(); c.allowedOrigins=listOf("http://localhost:3000"); c.allowedMethods=listOf("GET","POST","PUT","PATCH","DELETE","OPTIONS"); c.allowedHeaders=listOf("*"); c.allowCredentials=true
  val s=UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/**",c); return CorsFilter(s)
 }
}
