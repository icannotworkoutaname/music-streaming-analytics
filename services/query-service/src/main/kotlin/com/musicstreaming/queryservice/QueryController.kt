package com.musicstreaming.queryservice

import com.musicstreaming.queryservice.model.SongCompletionStats
import com.musicstreaming.queryservice.model.SongHourlyCount
import com.musicstreaming.queryservice.model.TopSongEntry
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
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api")
class QueryController(
    private val redisTemplate: StringRedisTemplate,
    private val history: HistoryRepository,
    private val meterRegistry: MeterRegistry
) {
    private val mapper = jacksonObjectMapper()

    companion object {
        private val DEFAULT_LOOKBACK: Duration = Duration.ofHours(24)
        private const val MAX_LOOKBACK_HOURS = 24 * 30L
    }

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

    @GetMapping("/songs/{songId}/completion/history")
    fun getCompletionHistory(
        @PathVariable songId: String,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?
    ): List<SongCompletionStats> {
        meterRegistry.counter("queries.executed", "type", "completion_history").increment()
        val (fromMs, toMs) = resolveRange(from, to)
        return history.findCompletionStats(songId, fromMs, toMs)
    }

    @GetMapping("/songs/{songId}/plays/history")
    fun getPlayHistory(
        @PathVariable songId: String,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?
    ): List<SongHourlyCount> {
        meterRegistry.counter("queries.executed", "type", "play_history").increment()
        val (fromMs, toMs) = resolveRange(from, to)
        return history.findHourlyCounts(songId, fromMs, toMs)
    }

    // from/to 是毫秒时间戳（和事件里的 timestamp 一致）；缺省取最近 24 小时
    private fun resolveRange(from: Long?, to: Long?): Pair<Long, Long> {
        val toMs = to ?: Instant.now().toEpochMilli()
        val fromMs = from ?: (toMs - DEFAULT_LOOKBACK.toMillis())
        if (fromMs >= toMs) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'")
        }
        if (toMs - fromMs > Duration.ofHours(MAX_LOOKBACK_HOURS).toMillis()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "range must not exceed $MAX_LOOKBACK_HOURS hours")
        }
        return fromMs to toMs
    }
}