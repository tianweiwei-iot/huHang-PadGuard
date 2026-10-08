package com.padguard.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PadGuardServerApplication

fun main(args: Array<String>) {
    runApplication<PadGuardServerApplication>(*args)
}
