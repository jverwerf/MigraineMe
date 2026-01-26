package com.migraineme

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nutrition_outbox")
data class NutritionOutboxEntity(
    @PrimaryKey val healthConnectId: String,
    val operation: String, // "UPSERT" or "DELETE"
    val createdAtEpochMs: Long,
    val retryCount: Int = 0
)
