package org.nhrc.grants.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/public")
class PlatformController(@Value("\${nhrc.environment:development}") private val environment:String) {
 @GetMapping("/status") fun status()=mapOf("service" to "NHRC Grants Platform API","status" to "UP","environment" to environment,"time" to OffsetDateTime.now().toString(),"version" to "0.2.0-development")
}
