package com.vauth.foxyvpn.vpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object RelayDispatchers {

    private const val RELAY_THREAD_STACK_BYTES = 256L * 1024L

    private const val IDLE_THREAD_KEEPALIVE_SECONDS = 30L

    private val threadCounter = AtomicLong(0)

    private val threadFactory = ThreadFactory { runnable ->
        Thread(null, runnable, "moz-relay-${threadCounter.incrementAndGet()}", RELAY_THREAD_STACK_BYTES).apply {

            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    // The pool itself is intentionally unbounded: the number of concurrently running relay threads is
    // limited by ClientSlotLimiter (per local proxy server). A hard pool ceiling would make the executor
    // reject tasks, and a rejected coroutine never runs (leaking its client socket).
    private val executor = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        IDLE_THREAD_KEEPALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        threadFactory,
    )

    val relay: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
