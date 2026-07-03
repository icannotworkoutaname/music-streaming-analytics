package com.example.streamprocessor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StreamProcessorApplication

fun main(args: Array<String>) {
	runApplication<StreamProcessorApplication>(*args)
}
