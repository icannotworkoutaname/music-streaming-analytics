package com.example.queryservice

import com.example.queryservice.model.SongCompletionStats
import com.example.queryservice.model.TopSongEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class QueryController(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry
) {
    private val mapper = jacksonObjectMapper()

    @GetMapping("/songs/{songId}/completion")
    fun getCompletion(@PathVariable songId: String): ResponseEntity<SongCompletionStats> {
        meterRegistry.counter("queries.executed", "type", "completion_rate").increment()
        val json = redisTemplate.opsForValue().get("completion:$songId")
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapper.readValue<SongCompletionStats>(json))
    }

    @GetMapping("/songs/top")
    fun getTopSongs(@RequestParam(defaultValue = "10") n: Int): List<TopSongEntry> {
        meterRegistry.counter("queries.executed", "type", "top_songs").increment()
        val results = redisTemplate.opsForZSet()
            .reverseRangeWithScores("hourly-top-songs", 0, (n - 1).toLong())
            ?: emptySet()
        return results.map { TopSongEntry((it.value ?: "").toString(), (it.score ?: 0).toLong()) }
    }
}