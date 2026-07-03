package com.example.streamprocessor

import com.example.streamprocessor.model.PlayEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.streams.processor.TimestampExtractor

class PlayEventTimestampExtractor : TimestampExtractor {
    override fun extract(record: ConsumerRecord<Any, Any>, partitionTime: Long): Long {
        return when (val value = record.value()) {
            is PlayEvent -> value.timestamp
            else -> record.timestamp()  // fallback，理论上不会走到这
        }
    }
}