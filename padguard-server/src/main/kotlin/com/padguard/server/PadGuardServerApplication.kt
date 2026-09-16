package com.padguard.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PadGuardServerApplication

fun main(args: Array<String>) {
    runApplication<PadGuardServerApplication>(*args)
}
