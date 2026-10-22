package com.musicstreaming.queryservice

import com.musicstreaming.queryservice.model.SongCompletionStats
import com.musicstreaming.queryservice.model.SongHourlyCount
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class StatsConsumer(
    private val redisTemplate: StringRedisTemplate,
    private val history: HistoryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    // 先写 PostgreSQL 再写 Redis：两边都是幂等写，PostgreSQL 失败时异常让 listener 重试，
    // 不会出现 Redis 已更新而历史表缺记录的情况
    @KafkaListener(
        topics = ["song-completion-stats"],
        properties = ["spring.json.value.default.type=com.musicstreaming.queryservice.model.SongCompletionStats"]
    )
    fun handleCompletionStats(stats: SongCompletionStats) {
        history.upsertCompletionStats(stats)
        val key = "completion:${stats.songId}"
        redisTemplate.opsForValue().set(key, mapper.writeValueAsString(stats))
        log.info("Updated completion stats for ${stats.songId}: rate=${stats.completionRate}")
    }

    @KafkaListener(
        topics = ["song-hourly-counts"],
        properties = ["spring.json.value.default.type=com.musicstreaming.queryservice.model.SongHourlyCount"]
    )
    fun handleHourlyCount(count: SongHourlyCount) {
        history.upsertHourlyCount(count)
        redisTemplate.opsForZSet().add("hourly-top-songs", count.songId, count.count.toDouble())
        log.info("Updated hourly count for ${count.songId}: count=${count.count}")
    }
}