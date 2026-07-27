package com.musicstreaming.ingestionservice

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EventIngestionIT {

    companion object {
        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `posting an event publishes it to kafka`() {
        val event = PlayEvent(
            eventId = "test-1",
            userId = "u1",
            songId = "song-X",
            eventType = EventType.PLAY_START,
            timestamp = System.currentTimeMillis(),
            positionMs = 0,
            durationMs = 240000
        )

        val response = restTemplate.postForEntity("/events", event, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val consumer = createTestConsumer(kafka.bootstrapServers)
        consumer.subscribe(listOf("play-events"))
        val records = consumer.poll(Duration.ofSeconds(10))

        assertThat(records.count()).isEqualTo(1)
        assertThat(records.first().key()).isEqualTo("song-X")

        consumer.close()
    }

    private fun createTestConsumer(bootstrapServers: String): KafkaConsumer<String, ByteArray> {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            put("group.id", "test-consumer-${System.currentTimeMillis()}")
            put("key.deserializer", StringDeserializer::class.java.name)
            put("value.deserializer", ByteArrayDeserializer::class.java.name)
            put("auto.offset.reset", "earliest")
        }
        return KafkaConsumer(props)
    }
}