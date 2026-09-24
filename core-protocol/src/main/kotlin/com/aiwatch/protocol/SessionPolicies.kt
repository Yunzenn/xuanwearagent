package com.aiwatch.protocol

import kotlin.random.Random

/** A brief Ready does not restore budget; one uninterrupted stable interval does. */
data class RetryPolicy(
    val maxRetries: Int = 5,
    val stableConnectionMs: Long = 60_000,
    val jitter: () -> Double = { Random.nextDouble() },
) {
    init { require(maxRetries in 0..10 && stableConnectionMs > 0) }
    fun delayMs(attempt: Int): Long {
        require(attempt in 1..maxRetries)
        val ceiling = minOf(15_000L, 1000L shl (attempt - 1))
        return (ceiling * (0.5 + jitter().coerceIn(0.0, 1.0) * 0.5)).toLong()
    }
}

data class ActivationPolicy(val pollIntervalMs: Long = 3000, val maxPolls: Int = 60) {
    init { require(pollIntervalMs > 0 && maxPolls > 0) }
}

/** Executed synchronously on the coordinator actor. Do not retain context or launch work. */
interface InterruptPolicy {
    fun interrupt(context: InterruptContext)
}

interface InterruptContext {
    fun invalidateAndFlushAudio()
    fun sendAbort()
    fun reconnect()
}

/** Only production policy until same-socket stale-audio exclusion is proven on a real server. */
object HardInterruptPolicy : InterruptPolicy {
    override fun interrupt(context: InterruptContext) {
        context.invalidateAndFlushAudio()
        context.sendAbort()
        context.reconnect()
    }
}
