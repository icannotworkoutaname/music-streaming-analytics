package com.musicstreaming.queryservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class QueryServiceApplication

fun main(args: Array<String>) {
    runApplication<QueryServiceApplication>(*args)
}