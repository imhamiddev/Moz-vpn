package com.imhamiddev.mozvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.imhamiddev.mozvpn.vpn.socks.ProxyCredentials
import com.imhamiddev.mozvpn.data.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val EXIT_CHECK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

private const val EXIT_CHECK_TIMEOUT_SECONDS = 15L

/**
 * Lets java.net's SOCKS5 client answer the local proxy's username/password challenge, but only for
 * our own loopback SOCKS port; every other request gets no credentials.
 */
private object LoopbackSocksAuth {
    private val byPort = java.util.concurrent.ConcurrentHashMap<Int, ProxyCredentials>()
    @Volatile private var installed = false

    fun register(port: Int, credentials: ProxyCredentials) {
        byPort[port] = credentials
        if (installed) return
        synchronized(this) {
            if (installed) return
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (!requestingProtocol.orEmpty().startsWith("SOCKS", ignoreCase = true)) return null
                    val creds = byPort[requestingPort] ?: return null
                    val host = requestingHost.orEmpty()
                    val loopback = requestingSite?.isLoopbackAddress == true || host == "127.0.0.1" || host == "localhost"
                    if (!loopback) return null
                    return PasswordAuthentication(creds.username, creds.password.toCharArray())
                }
            })
            installed = true
        }
    }
}

data class IpLocationInfo(
    val ip: String,
    val countryCode: String,
)

class ExitCheck {

    suspend fun fetchDirectIpInfo(): Result<IpLocationInfo> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        try {
            runCatching {
                val request = Request.Builder().url(EXIT_CHECK_URL).build()
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) error("Direct IP check request failed: HTTP ${response.code}")
                    parseTrace(response.body?.string().orEmpty())
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    suspend fun fetchProxiedIpInfo(
        socksPort: Int,
        credentials: ProxyCredentials? = null,
    ): Result<IpLocationInfo> = withContext(Dispatchers.IO) {
        if (credentials != null) LoopbackSocksAuth.register(socksPort, credentials)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        try {
            runCatching {
                val request = Request.Builder().url(EXIT_CHECK_URL).build()
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) error("Proxied exit check failed: HTTP ${response.code}")
                    parseTrace(response.body?.string().orEmpty())
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    suspend fun verifyExitCountry(
        socksPort: Int,
        expectedCountryCode: String?,
        credentials: ProxyCredentials? = null,
    ): Result<String> =
        fetchProxiedIpInfo(socksPort, credentials).mapCatching { info ->
            val actualCountry = info.countryCode
            if (expectedCountryCode != null &&
                !expectedCountryCode.equals("REC", ignoreCase = true) &&
                !actualCountry.equals(expectedCountryCode, ignoreCase = true)
            ) {
                error("Exit country mismatch: expected $expectedCountryCode, got $actualCountry")
            }
            actualCountry
        }

    private fun parseTrace(body: String): IpLocationInfo {
        var ip = ""
        var loc = ""
        for (line in body.lineSequence()) {
            if (line.startsWith("ip=")) {
                ip = line.removePrefix("ip=").trim()
            } else if (line.startsWith("loc=")) {
                loc = line.removePrefix("loc=").trim()
            }
        }
        if (ip.isEmpty() && loc.isEmpty()) {
            error("Trace response did not contain IP or location")
        }
        return IpLocationInfo(ip = ip, countryCode = loc)
    }
}
