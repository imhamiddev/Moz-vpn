package com.vauth.foxyvpn.vpn.socks

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds how many local proxy clients are served at once. Every active client occupies blocking relay
 * threads, so the bound protects low-memory devices from thread exhaustion. Unlike a hard reject, an
 * over-limit client waits (suspended, holding no thread) for a slot for a short while, so bursts from a
 * browser or torrent client are smoothed out instead of being reset.
 */
class ClientSlotLimiter(
    capacity: Int = defaultCapacity(),
    private val maxWaiting: Int = capacity * 2,
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    private val semaphore = Semaphore(capacity)
    private val waiting = AtomicInteger(0)

    /** Returns true when a slot was obtained; the caller must then call [release] exactly once. */
    suspend fun acquire(): Boolean {
        if (semaphore.tryAcquire()) return true
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet()
            return false
        }
        try {
            var acquired = false
            // `acquired` is set with no suspension point after acquire(), so a timeout can never
            // leave us holding a permit that the caller does not know about.
            withTimeoutOrNull(waitTimeoutMs) {
                semaphore.acquire()
                acquired = true
            }
            return acquired
        } finally {
            waiting.decrementAndGet()
        }
    }

    fun release() = semaphore.release()

    companion object {
        private const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        private const val LOW_MEMORY_HEAP_MB = 192L

        /** 512 clients normally; 256 on devices with a small Java heap (each client costs ~2 threads). */
        fun defaultCapacity(): Int {
            val heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            return if (heapMb <= LOW_MEMORY_HEAP_MB) 256 else 512
        }
    }
}
