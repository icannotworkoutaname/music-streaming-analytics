package com.example.ingestionservice

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/events")
open class EventController(
    private val kafkaTemplate: KafkaTemplate<String, PlayEvent>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun ingest(@RequestBody event: PlayEvent): ResponseEntity<Map<String, String>> {
        // 用 songId 做 key，保证同一首歌的事件落到同一分区，方便聚合
        kafkaTemplate.send("play-events", event.songId, event)
        log.info("Ingested event {} for song {}", event.eventId, event.songId)
        return ResponseEntity.accepted().body(mapOf("eventId" to event.eventId))
    }
}