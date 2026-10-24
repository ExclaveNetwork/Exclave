package io.nekohasekai.sagernet.database

import kotlin.math.roundToInt

/** Fixed-size health-check history with exponentially weighted recent results. */
class GroupHealthCheckHistory private constructor(
    private val checks: List<Boolean>,
) {

    val size: Int
        get() = checks.size

    val isComplete: Boolean
        get() = checks.size == MAX_CHECKS

    fun append(success: Boolean): GroupHealthCheckHistory {
        return GroupHealthCheckHistory((checks + success).takeLast(MAX_CHECKS))
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

    fun encode(): String = checks.joinToString(",") { if (it) "1" else "0" }

    companion object {
        const val MAX_CHECKS = 10
        private const val ALPHA = 0.2
        private const val DECAY = 1.0 - ALPHA

        fun decode(encoded: String?): GroupHealthCheckHistory {
            val checks = encoded.orEmpty()
                .split(',')
                .mapNotNull {
                    when (it.trim()) {
                        "1" -> true
                        "0" -> false
                        else -> null
                    }
                }
                .takeLast(MAX_CHECKS)
            return GroupHealthCheckHistory(checks)
        }
    }
}
