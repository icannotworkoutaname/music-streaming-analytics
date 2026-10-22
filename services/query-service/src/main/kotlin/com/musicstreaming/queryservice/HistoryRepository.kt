package com.musicstreaming.queryservice

import com.musicstreaming.queryservice.model.SongCompletionStats
import com.musicstreaming.queryservice.model.SongHourlyCount
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class HistoryRepository(private val jdbc: JdbcTemplate) {

    // Kafka Streams 对同一窗口会多次输出中间结果，所以用 upsert 保留最后一次
    fun upsertCompletionStats(stats: SongCompletionStats) {
        jdbc.update(
            """
            INSERT INTO song_completion_stats
                (song_id, window_start, window_end, starts, completes, completion_rate, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (song_id, window_start) DO UPDATE SET
                window_end      = EXCLUDED.window_end,
                starts          = EXCLUDED.starts,
                completes       = EXCLUDED.completes,
                completion_rate = EXCLUDED.completion_rate,
                updated_at      = now()
            """.trimIndent(),
            stats.songId, stats.windowStart, stats.windowEnd,
            stats.starts, stats.completes, stats.completionRate
        )
    }

    fun upsertHourlyCount(count: SongHourlyCount) {
        jdbc.update(
            """
            INSERT INTO song_hourly_counts
                (song_id, window_start, window_end, play_count, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (song_id, window_start) DO UPDATE SET
                window_end = EXCLUDED.window_end,
                play_count = EXCLUDED.play_count,
                updated_at = now()
            """.trimIndent(),
            count.songId, count.windowStart, count.windowEnd, count.count
        )
    }

    fun findCompletionStats(songId: String, fromMs: Long, toMs: Long): List<SongCompletionStats> =
        jdbc.query(
            """
            SELECT song_id, window_start, window_end, starts, completes, completion_rate
            FROM song_completion_stats
            WHERE song_id = ? AND window_start >= ? AND window_start < ?
            ORDER BY window_start
            """.trimIndent(),
            { rs, _ ->
                SongCompletionStats(
                    songId = rs.getString("song_id"),
                    windowStart = rs.getLong("window_start"),
                    windowEnd = rs.getLong("window_end"),
                    starts = rs.getLong("starts"),
                    completes = rs.getLong("completes"),
                    completionRate = rs.getDouble("completion_rate")
                )
            },
            songId, fromMs, toMs
        )

    fun findHourlyCounts(songId: String, fromMs: Long, toMs: Long): List<SongHourlyCount> =
        jdbc.query(
            """
            SELECT song_id, window_start, window_end, play_count
            FROM song_hourly_counts
            WHERE song_id = ? AND window_start >= ? AND window_start < ?
            ORDER BY window_start
            """.trimIndent(),
            { rs, _ ->
                SongHourlyCount(
                    songId = rs.getString("song_id"),
                    windowStart = rs.getLong("window_start"),
                    windowEnd = rs.getLong("window_end"),
                    count = rs.getLong("play_count")
                )
            },
            songId, fromMs, toMs
        )
}
