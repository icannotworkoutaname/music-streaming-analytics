package com.musicstreaming.queryservice

import com.musicstreaming.queryservice.model.SongCompletionStats
import com.musicstreaming.queryservice.model.SongHourlyCount
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class StatsConsumer(private val redisTemplate: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    @KafkaListener(
        topics = ["song-completion-stats"],
        properties = ["spring.json.value.default.type=com.musicstreaming.queryservice.model.SongCompletionStats"]
    )
    fun handleCompletionStats(stats: SongCompletionStats) {
        val key = "completion:${stats.songId}"
        redisTemplate.opsForValue().set(key, mapper.writeValueAsString(stats))
        log.info("Updated completion stats for ${stats.songId}: rate=${stats.completionRate}")
    }

    @KafkaListener(
        topics = ["song-hourly-counts"],
        properties = ["spring.json.value.default.type=com.musicstreaming.queryservice.model.SongHourlyCount"]
    )
    fun handleHourlyCount(count: SongHourlyCount) {
        redisTemplate.opsForZSet().add("hourly-top-songs", count.songId, count.count.toDouble())
        log.info("Updated hourly count for ${count.songId}: count=${count.count}")
    }
}