package com.example.queryservice.model

data class SongCompletionStats(
    val songId: String,
    val windowStart: Long,
    val windowEnd: Long,
    val starts: Long,
    val completes: Long,
    val completionRate: Double
)