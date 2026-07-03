package com.example.streamprocessor.model

data class PlayEvent(
    val eventId: String,
    val userId: String,
    val songId: String,
    val eventType: EventType,
    val timestamp: Long,
    val positionMs: Long,
    val durationMs: Long
)

enum class EventType {
    PLAY_START, PLAY_PROGRESS, PLAY_END, SKIP
}