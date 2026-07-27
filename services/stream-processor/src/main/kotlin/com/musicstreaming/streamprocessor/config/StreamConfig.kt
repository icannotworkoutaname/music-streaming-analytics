package com.musicstreaming.streamprocessor.config

import com.musicstreaming.streamprocessor.model.EventType
import com.musicstreaming.streamprocessor.model.PlayEvent
import com.musicstreaming.streamprocessor.model.PlayStats
import com.musicstreaming.streamprocessor.model.SongCompletionStats
import com.musicstreaming.streamprocessor.model.SongHourlyCount
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Grouped
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.TimeWindows
import org.apache.kafka.streams.state.WindowStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerde
import java.time.Duration

@Configuration
@EnableKafkaStreams
class StreamConfig(private val meterRegistry: MeterRegistry) {
    @Bean
    fun playEventsStream(builder: StreamsBuilder): KStream<String, PlayEvent> {
        return buildCompletionRateTopology(builder, meterRegistry)
    }

    @Bean
    fun topSongsStream(builder: StreamsBuilder): KStream<String, PlayEvent> {
        val playEventSerde = JsonSerde(PlayEvent::class.java).apply {
            configure(mapOf(JsonDeserializer.USE_TYPE_INFO_HEADERS to false), false)
        }

        val stream: KStream<String, PlayEvent> = builder.stream(
            "play-events",
            Consumed.with(Serdes.String(), playEventSerde)
        )

        stream
            .filter { _, event -> event.eventType == EventType.PLAY_START }
            .groupByKey(Grouped.with(Serdes.String(), playEventSerde))
            .windowedBy(
                TimeWindows.ofSizeAndGrace(
                    Duration.ofHours(1),
                    Duration.ofMinutes(5)
                )
            )
            .count(Materialized.`as`("hourly-play-counts"))
            .toStream()
            .map { windowedKey, count ->
                meterRegistry.counter("windows.emitted", "metric", "top_songs").increment()
                val output = SongHourlyCount(
                    songId = windowedKey.key(),
                    windowStart = windowedKey.window().start(),
                    windowEnd = windowedKey.window().end(),
                    count = count
                )
                KeyValue(windowedKey.key(), output)
            }
            .to(
                "song-hourly-counts",
                Produced.with(Serdes.String(), JsonSerde(SongHourlyCount::class.java))
            )

        return stream
    }
}

// 抽成顶层函数，不依赖 Spring，生产代码和测试都调它
fun buildCompletionRateTopology(builder: StreamsBuilder, meterRegistry: MeterRegistry): KStream<String, PlayEvent> {
    val playEventSerde = JsonSerde(PlayEvent::class.java).apply {
        configure(mapOf(JsonDeserializer.USE_TYPE_INFO_HEADERS to false), false)
    }

    val stream: KStream<String, PlayEvent> = builder.stream(
        "play-events",
        Consumed.with(Serdes.String(), playEventSerde)
    )

    val statsSerde = JsonSerde(PlayStats::class.java).apply {
        configure(mapOf(JsonDeserializer.USE_TYPE_INFO_HEADERS to false), false)
    }

    stream
        .groupByKey(Grouped.with(Serdes.String(), playEventSerde))
        .windowedBy(
            TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofMinutes(1))
        )
        .aggregate(
            { PlayStats(0, 0) },
            { _, event, stats ->
                when {
                    event.eventType == EventType.PLAY_START ->
                        stats.copy(starts = stats.starts + 1)
                    event.eventType == EventType.PLAY_END &&
                            event.positionMs >= event.durationMs * 0.9 ->
                        stats.copy(completes = stats.completes + 1)
                    else -> stats
                }
            },
            Materialized.`as`<String, PlayStats, WindowStore<Bytes, ByteArray>>("completion-stats")
                .withKeySerde(Serdes.String())
                .withValueSerde(statsSerde)
        )
        .toStream()
        .map { windowedKey, stats ->
            meterRegistry.counter("windows.emitted", "metric", "completion_rate").increment()
            val rate = if (stats.starts > 0) stats.completes.toDouble() / stats.starts else 0.0
            val output = SongCompletionStats(
                songId = windowedKey.key(),
                windowStart = windowedKey.window().start(),
                windowEnd = windowedKey.window().end(),
                starts = stats.starts,
                completes = stats.completes,
                completionRate = rate
            )
            KeyValue(windowedKey.key(), output)
        }
        .to(
            "song-completion-stats",
            Produced.with(Serdes.String(), JsonSerde(SongCompletionStats::class.java))
        )

    return stream
}