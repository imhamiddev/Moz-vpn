package com.vauth.foxyvpn.vpn

import com.vauth.foxyvpn.data.model.ProxyCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object ServerPicker {

    private const val MAX_PARALLEL_PROBES = 16

    /**
     * Picks a server from the city that answers fastest. One random server per city is probed in
     * parallel (at most [MAX_PARALLEL_PROBES] cities) and the quickest reachable one wins. If no probe
     * succeeds the choice falls back to a random candidate, so the user is never left without a server.
     */
    suspend fun pickLowestLatency(candidates: List<ProxyCandidate>): ProxyCandidate? {
        if (candidates.isEmpty()) return null
        val probes = candidates
            .groupBy { it.cityCode }
            .values
            .map { it.random() }
            .shuffled()
            .take(MAX_PARALLEL_PROBES)
        val results = coroutineScope {
            probes.map { candidate ->
                async { candidate to PingUtil.measureTcpLatencyMs(candidate.host, candidate.port) }
            }.awaitAll()
        }
        return results
            .mapNotNull { (candidate, latency) -> latency?.let { candidate to it } }
            .minByOrNull { it.second }
            ?.first
            ?: candidates.random()
    }
}
