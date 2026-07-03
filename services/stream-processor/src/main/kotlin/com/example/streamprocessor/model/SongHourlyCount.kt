package com.example.streamprocessor.model

data class SongHourlyCount(
    val songId: String,
    val windowStart: Long,
    val windowEnd: Long,
    val count: Long
)