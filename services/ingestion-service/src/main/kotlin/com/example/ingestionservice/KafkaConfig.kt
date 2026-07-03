package com.example.ingestionservice

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
open class KafkaConfig {
    @Bean
    open fun playEventsTopic(): NewTopic =
        TopicBuilder.name("play-events")
            .partitions(6)
            .replicas(1)
            .build()
}