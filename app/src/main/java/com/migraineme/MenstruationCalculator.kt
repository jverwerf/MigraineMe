package com.migraineme

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Utilities for menstruation cycle calculations and predictions
 */
object MenstruationCalculator {
    
    /**
     * Calculate weighted average cycle length from last 6 cycles
     * More recent cycles have higher weight
     * 
     * @param periods List of periods (must be sorted by date ascending)
     * @return Weighted average cycle length in days, or 28 if insufficient data
     */
    fun calculateWeightedAverage(periods: List<MenstruationPeriod>): Int {
        if (periods.size < 2) return 28 // Default cycle length
        
        // Take last 6 periods
        val last6 = periods.takeLast(6)
        
        // Calculate cycle lengths (days between period starts)
        val cycleLengths = last6.zipWithNext { current, next ->
            ChronoUnit.DAYS.between(current.startDate, next.startDate).toInt()
        }
        
        if (cycleLengths.isEmpty()) return 28
        
        // Weights: Most recent cycle gets highest weight
        // Example with 5 cycles: weights = [1, 2, 3, 4, 5]
        val weights = (1..cycleLengths.size).toList()
        
        // Calculate weighted average
        val weightedSum = cycleLengths.zip(weights) { length, weight ->
            length * weight
        }.sum()
        
        val totalWeight = weights.sum()
        
        return (weightedSum.toDouble() / totalWeight).roundToInt()
    }
    
    /**
     * Calculate simple average cycle length
     * 
     * @param periods List of periods (must be sorted by date ascending)
     * @return Average cycle length in days, or 28 if insufficient data
     */
    fun calculateSimpleAverage(periods: List<MenstruationPeriod>): Int {
        if (periods.size < 2) return 28
        
        val cycleLengths = periods.zipWithNext { current, next ->
            ChronoUnit.DAYS.between(current.startDate, next.startDate).toInt()
        }
        
        if (cycleLengths.isEmpty()) return 28
        
        return (cycleLengths.average()).roundToInt()
    }
    
    /**
     * Predict next period start date
     * 
     * @param lastPeriodDate Date of last period start
     * @param avgCycleLength Average cycle length in days
     * @return Predicted next period start date
     */
    fun predictNextPeriod(lastPeriodDate: LocalDate, avgCycleLength: Int): LocalDate {
        return lastPeriodDate.plusDays(avgCycleLength.toLong())
    }
    
    /**
     * Predict ovulation date (typically 14 days before next period)
     * 
     * @param nextPeriodDate Predicted next period start date
     * @return Predicted ovulation date
     */
    fun predictOvulation(nextPeriodDate: LocalDate): LocalDate {
        return nextPeriodDate.minusDays(14)
    }
    
    /**
     * Calculate cycle day (which day of cycle user is currently on)
     * 
     * @param lastPeriodDate Date of last period start
     * @param currentDate Today's date
     * @return Cycle day number (1-based)
     */
    fun calculateCycleDay(lastPeriodDate: LocalDate, currentDate: LocalDate): Int {
        val daysSinceStart = ChronoUnit.DAYS.between(lastPeriodDate, currentDate).toInt()
        return daysSinceStart + 1 // Day 1 = first day of period
    }
    
    /**
     * Calculate cycle length between two periods
     * 
     * @param previousPeriod Previous period start date
     * @param currentPeriod Current period start date
     * @return Number of days in cycle
     */
    fun calculateCycleLength(previousPeriod: LocalDate, currentPeriod: LocalDate): Int {
        return ChronoUnit.DAYS.between(previousPeriod, currentPeriod).toInt()
    }
    
    /**
     * Determine if cycle is regular (cycle lengths vary by less than 3 days)
     * 
     * @param periods List of periods
     * @return true if cycle is regular, false otherwise
     */
    fun isRegularCycle(periods: List<MenstruationPeriod>): Boolean {
        if (periods.size < 3) return false
        
        val cycleLengths = periods.zipWithNext { current, next ->
            ChronoUnit.DAYS.between(current.startDate, next.startDate).toInt()
        }
        
        val min = cycleLengths.minOrNull() ?: return false
        val max = cycleLengths.maxOrNull() ?: return false
        
        return (max - min) <= 3 // Regular if variation is 3 days or less
    }
}

/**
 * Simple data class for menstruation period
 */
data class MenstruationPeriod(
    val startDate: LocalDate,
    val endDate: LocalDate? = null
)
