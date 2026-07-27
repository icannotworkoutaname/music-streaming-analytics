package com.musicstreaming.streamprocessor.config

import com.musicstreaming.streamprocessor.PlayEventTimestampExtractor
import com.musicstreaming.streamprocessor.model.EventType
import com.musicstreaming.streamprocessor.model.PlayEvent
import com.musicstreaming.streamprocessor.model.SongCompletionStats
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.TopologyTestDriver
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerde
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class CompletionRateTopologyTest {

    private lateinit var testDriver: TopologyTestDriver
    private lateinit var inputTopic: TestInputTopic<String, PlayEvent>
    private lateinit var outputTopic: TestOutputTopic<String, SongCompletionStats>

    @BeforeEach
    fun setup() {
        val builder = StreamsBuilder()
        buildCompletionRateTopology(builder, SimpleMeterRegistry())

        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "test-app")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234")
            // 关键:让 TopologyTestDriver 用跟生产环境一样的 extractor 读事件时间
            put(
                StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
                PlayEventTimestampExtractor::class.java.name
            )
        }
        testDriver = TopologyTestDriver(builder.build(), props)

        val playEventSerde = JsonSerde(PlayEvent::class.java).apply {
            configure(mapOf(JsonDeserializer.USE_TYPE_INFO_HEADERS to false), false)
        }
        val statsSerde = JsonSerde(SongCompletionStats::class.java).apply {
            configure(mapOf(JsonDeserializer.USE_TYPE_INFO_HEADERS to false), false)
        }

        inputTopic = testDriver.createInputTopic(
            "play-events",
            Serdes.String().serializer(),
            playEventSerde.serializer()
        )
        outputTopic = testDriver.createOutputTopic(
            "song-completion-stats",
            Serdes.String().deserializer(),
            statsSerde.deserializer()
        )
    }

    @AfterEach
    fun tearDown() {
        testDriver.close()
    }

    @Test
    fun `completion rate is 0_8 for 10 starts and 8 completes`() {
        val base = 1717600000000L
        val windowSizeMs = 5 * 60 * 1000L
        val expectedWindowStart = (base / windowSizeMs) * windowSizeMs

        // 10 次 PLAY_START，同一首歌 song-A
        repeat(10) { i ->
            inputTopic.pipeInput("song-A", playStart("song-A", base + i * 1000))
        }
        // 8 次 PLAY_END，positionMs 达到 durationMs 的 90% 以上，算完成
        repeat(8) { i ->
            inputTopic.pipeInput("song-A", playEnd("song-A", base + i * 1000, 230000, 240000))
        }
        // 推进 streamTime 越过窗口(5分钟) + grace period(1分钟)，
        // 这条落进下一个窗口，只是用来把 stream time 推过去，不影响第一个窗口的断言
        inputTopic.pipeInput("song-A", playStart("song-A", base + 7 * 60 * 1000))

        // 生产拓扑没有 suppress，每次更新都会吐一条记录，
        // 所以按 windowStart 过滤出第一个窗口的所有记录，取最后一条(累积到最终状态)
        val firstWindowResults = outputTopic.readValuesToList()
            .filter { it.windowStart == expectedWindowStart }

        assertThat(firstWindowResults).isNotEmpty()

        val finalState = firstWindowResults.last()
        assertThat(finalState.starts).isEqualTo(10)
        assertThat(finalState.completes).isEqualTo(8)
        assertThat(finalState.completionRate).isEqualTo(0.8)
    }

    private fun playStart(songId: String, timestamp: Long): PlayEvent {
        return PlayEvent(
            eventId = "evt-${songId}-start-$timestamp",
            userId = "test-user",
            songId = songId,
            eventType = EventType.PLAY_START,
            timestamp = timestamp,
            positionMs = 0,
            durationMs = 240000
        )
    }

    private fun playEnd(songId: String, timestamp: Long, positionMs: Long, durationMs: Long): PlayEvent {
        return PlayEvent(
            eventId = "evt-${songId}-end-$timestamp",
            userId = "test-user",
            songId = songId,
            eventType = EventType.PLAY_END,
            timestamp = timestamp,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }
}