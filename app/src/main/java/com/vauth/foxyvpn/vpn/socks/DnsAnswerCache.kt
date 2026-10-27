package com.vauth.foxyvpn.vpn.socks

/**
 * Small in-memory cache for DNS-over-HTTPS answers, so repeated lookups do not each cost a full HTTPS
 * request through the tunnel. Only complete, successful answers are cached, for at most [MAX_TTL_SECONDS]
 * and never longer than the shortest TTL in the answer. Anything that cannot be parsed is not cached.
 */
internal object DnsAnswerCache {
    private const val MAX_ENTRIES = 512
    private const val MAX_TTL_SECONDS = 300L
    private const val DNS_HEADER_BYTES = 12

    private class Entry(val answer: ByteArray, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
    }

    /** Returns a cached answer rewritten with this query's transaction id, or null. */
    fun get(query: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val key = keyOf(query) ?: return null
        val cached = synchronized(entries) {
            val entry = entries[key] ?: return null
            if (entry.expiresAtMs <= nowMs) {
                entries.remove(key)
                return null
            }
            entry.answer
        }
        return cached.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }
    }

    fun put(query: ByteArray, answer: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        val key = keyOf(query) ?: return
        val ttlSeconds = minimumTtlSeconds(answer) ?: return
        if (ttlSeconds <= 0L) return
        val expiresAt = nowMs + minOf(ttlSeconds, MAX_TTL_SECONDS) * 1_000L
        synchronized(entries) { entries[key] = Entry(answer.copyOf(), expiresAt) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    // Everything after the 2-byte transaction id identifies the question (flags, counts, name, type, EDNS).
    private fun keyOf(query: ByteArray): String? {
        if (query.size <= DNS_HEADER_BYTES) return null
        return String(query, 2, query.size - 2, Charsets.ISO_8859_1)
    }

    /** Shortest TTL among the answer records of a successful, untruncated response; null if not cacheable. */
    private fun minimumTtlSeconds(message: ByteArray): Long? {
        if (message.size <= DNS_HEADER_BYTES) return null
        val flags = ((message[2].toInt() and 0xFF) shl 8) or (message[3].toInt() and 0xFF)
        val isResponse = flags and 0x8000 != 0
        val truncated = flags and 0x0200 != 0
        val rcode = flags and 0x000F
        if (!isResponse || truncated || rcode != 0) return null
        val questions = ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)
        val answers = ((message[6].toInt() and 0xFF) shl 8) or (message[7].toInt() and 0xFF)
        if (answers == 0) return null

        var pos = DNS_HEADER_BYTES
        repeat(questions) {
            pos = skipName(message, pos) ?: return null
            pos += 4
            if (pos > message.size) return null
        }
        var minTtl = Long.MAX_VALUE
        repeat(answers) {
            pos = skipName(message, pos) ?: return null
            if (pos + 10 > message.size) return null
            val ttl = ((message[pos + 4].toLong() and 0xFF) shl 24) or
                ((message[pos + 5].toLong() and 0xFF) shl 16) or
                ((message[pos + 6].toLong() and 0xFF) shl 8) or
                (message[pos + 7].toLong() and 0xFF)
            val rdLength = ((message[pos + 8].toInt() and 0xFF) shl 8) or (message[pos + 9].toInt() and 0xFF)
            pos += 10 + rdLength
            if (pos > message.size) return null
            if (ttl < minTtl) minTtl = ttl
        }
        return minTtl.takeIf { it != Long.MAX_VALUE }
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= message.size) return null
            val length = message[pos].toInt() and 0xFF
            when {
                length == 0 -> return pos + 1
                length and 0xC0 == 0xC0 -> return if (pos + 2 <= message.size) pos + 2 else null
                length and 0xC0 != 0 -> return null
                else -> pos += length + 1
            }
        }
    }
}
