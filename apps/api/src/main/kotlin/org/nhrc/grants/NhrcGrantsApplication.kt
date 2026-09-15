package org.nhrc.grants

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class NhrcGrantsApplication

fun main(args: Array<String>) {
    runApplication<NhrcGrantsApplication>(*args)
}
