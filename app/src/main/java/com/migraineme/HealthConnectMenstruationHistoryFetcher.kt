package com.migraineme

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Fetches historical menstruation data from Health Connect
 */
object HealthConnectMenstruationHistoryFetcher {

    data class HistoricalData(
        val periods: List<PeriodInfo>,
        val suggestedLastDate: LocalDate?,
        val suggestedAvgCycle: Int?
    )

    data class PeriodInfo(
        val startDate: LocalDate,
        val endDate: LocalDate?
    )

    /**
     * Fetch last 6 months of period data from Health Connect
     * and calculate suggested values
     */
    suspend fun fetchHistoricalData(context: Context): HistoricalData {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("MenstruationHistory", "Starting historical data fetch...")

                val client = HealthConnectClient.getOrCreate(context)

                // Fetch last 6 months of data
                val now = Instant.now()
                val sixMonthsAgo = now.minus(180, ChronoUnit.DAYS)

                val request = ReadRecordsRequest(
                    recordType = MenstruationPeriodRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sixMonthsAgo, now)
                )

                val response = client.readRecords(request)
                val records = response.records

                Log.d("MenstruationHistory", "Found ${records.size} period records")

                if (records.isEmpty()) {
                    return@withContext HistoricalData(
                        periods = emptyList(),
                        suggestedLastDate = null,
                        suggestedAvgCycle = null
                    )
                }

                // Convert to PeriodInfo
                val periods = records.map { record ->
                    PeriodInfo(
                        startDate = record.startTime.toLocalDate(),
                        endDate = record.endTime.toLocalDate()
                    )
                }.sortedByDescending { it.startDate }

                Log.d("MenstruationHistory", "Periods found:")
                periods.forEach { period ->
                    Log.d("MenstruationHistory", "  - ${period.startDate}")
                }

                // Most recent period
                val lastDate = periods.firstOrNull()?.startDate

                // Calculate average cycle length from last 6 periods
                val avgCycle = if (periods.size >= 2) {
                    calculateWeightedAverage(periods.take(6))
                } else {
                    null
                }

                Log.d("MenstruationHistory", "Suggested last date: $lastDate")
                Log.d("MenstruationHistory", "Suggested avg cycle: $avgCycle days")

                HistoricalData(
                    periods = periods,
                    suggestedLastDate = lastDate,
                    suggestedAvgCycle = avgCycle
                )

            } catch (e: Exception) {
                Log.e("MenstruationHistory", "Failed to fetch historical data: ${e.message}", e)
                HistoricalData(
                    periods = emptyList(),
                    suggestedLastDate = null,
                    suggestedAvgCycle = null
                )
            }
        }
    }

    /**
     * Calculate weighted average cycle length
     * Same logic as MenstruationCalculator
     */
    private fun calculateWeightedAverage(periods: List<PeriodInfo>): Int? {
        if (periods.size < 2) return null

        val cycleLengths = mutableListOf<Int>()

        for (i in 0 until periods.size - 1) {
            val current = periods[i].startDate
            val next = periods[i + 1].startDate
            val days = ChronoUnit.DAYS.between(next, current).toInt()

            if (days in 14..60) {  // Reasonable cycle length
                cycleLengths.add(days)
            }
        }

        if (cycleLengths.isEmpty()) return null

        // Weighted average: more recent cycles weigh more
        val weights = listOf(0.30, 0.25, 0.20, 0.15, 0.10)
        var weightedSum = 0.0
        var totalWeight = 0.0

        cycleLengths.take(5).forEachIndexed { index, length ->
            val weight = weights.getOrNull(index) ?: 0.0
            weightedSum += length * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) {
            (weightedSum / totalWeight).toInt()
        } else {
            cycleLengths.average().toInt()
        }
    }
}

private fun Instant.toLocalDate(): LocalDate {
    return this.atZone(ZoneOffset.UTC).toLocalDate()
}