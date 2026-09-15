package org.nhrc.grants.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/platform")
class PlatformController {
    @GetMapping
    fun status() = mapOf(
        "name" to "NHRC Grants Platform",
        "status" to "UP",
        "version" to "0.1.0"
    )
}
