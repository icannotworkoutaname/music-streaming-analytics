package com.musicstreaming.queryservice

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.musicstreaming.queryservice.model.SongCompletionStats
import com.redis.testcontainers.RedisContainer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Properties

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueryServiceIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))

        @Container
        @ServiceConnection
        @JvmStatic
        val redis = RedisContainer(DockerImageName.parse("redis:7"))
    }

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private val mapper = jacksonObjectMapper()
    private lateinit var producer: KafkaProducer<String, String>

    @AfterEach
    fun tearDown() {
        if (::producer.isInitialized) producer.close()
    }

    @Test
    fun `completion stats from kafka are queryable via api`() {
        val stats = SongCompletionStats(
            songId = "song-X",
            windowStart = 1717600000000L,
            windowEnd = 1717600300000L,
            starts = 10,
            completes = 8,
            completionRate = 0.8
        )
        producer = createTestProducer(kafka.bootstrapServers)
        producer.send(
            ProducerRecord("song-completion-stats", stats.songId, mapper.writeValueAsString(stats))
        ).get()

        // consumer 是异步的，用 Awaitility 轮询直到 API 能查到，而不是 Thread.sleep 硬等
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val response = restTemplate.getForEntity(
                "/api/songs/${stats.songId}/completion", SongCompletionStats::class.java
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).isEqualTo(stats)
        }
    }

    private fun createTestProducer(bootstrapServers: String): KafkaProducer<String, String> {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            put("key.serializer", StringSerializer::class.java.name)
            put("value.serializer", StringSerializer::class.java.name)
        }
        return KafkaProducer(props)
    }
}
