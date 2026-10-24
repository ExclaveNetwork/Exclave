package io.nekohasekai.sagernet.database

import kotlin.math.roundToInt

/** Fixed-size health-check history with exponentially weighted recent results. */
class GroupHealthCheckHistory private constructor(
    private val checks: List<Boolean>,
    val consecutiveFailures: Long,
) {

    val size: Int
        get() = checks.size

    val isComplete: Boolean
        get() = checks.size == MAX_CHECKS

    fun append(success: Boolean): GroupHealthCheckHistory {
        val nextFailureCount = if (success) {
            0L
        } else if (consecutiveFailures >= Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            consecutiveFailures.coerceAtLeast(0L) + 1L
        }
        return GroupHealthCheckHistory((checks + success).takeLast(MAX_CHECKS), nextFailureCount)
    }

    fun successPercent(): Int? {
        if (checks.isEmpty()) return null

        var weightedSuccess = 0.0
        var totalWeight = 0.0
        var weight = 1.0
        for (success in checks.asReversed()) {
            if (success) weightedSuccess += weight
            totalWeight += weight
            weight *= DECAY
        }
        return ((weightedSuccess / totalWeight) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun encode(): String = "$ENCODING_PREFIX:${checks.joinToString(",") { if (it) "1" else "0" }}:$consecutiveFailures"

    companion object {
        const val MAX_CHECKS = 10
        private const val ENCODING_PREFIX = "v2"
        private const val ALPHA = 0.2
        private const val DECAY = 1.0 - ALPHA

        fun decode(encoded: String?): GroupHealthCheckHistory {
            val value = encoded.orEmpty()
            val parts = value.split(':', limit = 3)
            val isNewFormat = parts.size == 3 && parts[0] == ENCODING_PREFIX
            val checksSource = if (isNewFormat) parts[1] else value
            val checks = checksSource
                .split(',')
                .mapNotNull {
                    when (it.trim()) {
                        "1" -> true
                        "0" -> false
                        else -> null
                    }
                }
                .takeLast(MAX_CHECKS)
            val trailingFailures = checks.asReversed().takeWhile { !it }.size.toLong()
            val consecutiveFailures = if (isNewFormat) {
                (parts[2].toLongOrNull() ?: trailingFailures).coerceAtLeast(trailingFailures)
            } else {
                trailingFailures
            }
            return GroupHealthCheckHistory(checks, consecutiveFailures)
        }
    }
}
