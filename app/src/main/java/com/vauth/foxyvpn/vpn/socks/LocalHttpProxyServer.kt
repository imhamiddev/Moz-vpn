package com.vauth.foxyvpn.vpn.socks

import android.net.InetAddresses
import android.os.Build
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.vpn.RelayDispatchers
import com.vauth.foxyvpn.vpn.tun.HevSocks5TunnelConfig
import com.vauth.foxyvpn.vpn.upstream.UpstreamConnectRejectedException
import com.vauth.foxyvpn.vpn.upstream.UpstreamHealthTracker
import com.vauth.foxyvpn.vpn.upstream.UpstreamSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI

private const val TAG = "LocalHttpProxyServer"

private const val SESSION_WAIT_TIMEOUT_MS = 4_000L
private const val SESSION_WAIT_POLL_INTERVAL_MS = 100L
private const val OPEN_STREAM_TIMEOUT_MS = 30_000L
private const val HALF_CLOSE_DRAIN_TIMEOUT_MS = 2 * 60_000L
private const val ACCEPT_BACKLOG = 256
private const val ACCEPT_ERROR_BACKOFF_MS = 100L
private const val RELAY_BUFFER_BYTES = 32 * 1024
private const val MAX_HEADER_SIZE_BYTES = 64 * 1024
private const val UNREACHABLE_TARGET_TTL_MS = 30_000L
private const val UNREACHABLE_TARGET_TTL_CAP_MS = 10 * 60_000L
private const val REFUSALS_BEFORE_TREATING_AS_POLICY = 3
private const val UNREACHABLE_TARGET_CACHE_CAP = 256

class LocalHttpProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val sessionProvider: () -> UpstreamSession?,
    private val credentials: ProxyCredentials? = null,
    private val onSessionUnhealthy: () -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.w(
            TAG,
            "unhandled exception in an HTTP proxy connection coroutine",
            throwable,
        )
    }
    private val scope = CoroutineScope(RelayDispatchers.relay + SupervisorJob() + exceptionHandler)
    private val connectionSlots = ClientSlotLimiter()
    private val health = UpstreamHealthTracker()
    @Volatile private var unauthenticatedSession: UpstreamSession? = null

    private class RefusalRecord {
        var strikes: Int = 0
        var expiresAt: Long = 0L
    }

    private val unreachableTargets = object : LinkedHashMap<String, RefusalRecord>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RefusalRecord>): Boolean =
            size > UNREACHABLE_TARGET_CACHE_CAP
    }

    fun start() {
        val server = ServerSocket()
        runCatching { server.reuseAddress = true }
        server.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), ACCEPT_BACKLOG)
        serverSocket = server
        AppLogger.i(TAG, "started local HTTP proxy on $bindAddress:$port")

        scope.launch {
            while (!server.isClosed) {
                val accepted = runCatching { server.accept() }
                val client = accepted.getOrNull()
                if (client == null) {
                    if (server.isClosed) break
                    AppLogger.d(TAG, "accept() failed on HTTP listening socket: ${accepted.exceptionOrNull()?.message}")
                    delay(ACCEPT_ERROR_BACKOFF_MS)
                    continue
                }

                runCatching { client.tcpNoDelay = true }
                scope.launch {
                    var acquired = false
                    try {
                        acquired = connectionSlots.acquire()
                        if (!acquired) {
                            AppLogger.w(TAG, "rejecting HTTP proxy client: connection limit reached and no slot freed up in time")
                            return@launch
                        }
                        handleClient(client)
                    } finally {
                        if (acquired) connectionSlots.release() else runCatching { client.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope.cancel()
        synchronized(unreachableTargets) { unreachableTargets.clear() }
        health.reset()
        unauthenticatedSession = null
        AppLogger.i(TAG, "stopped local HTTP proxy on $bindAddress:$port")
    }

    private suspend fun handleClient(client: Socket) {
        try {
            handleClientOrThrow(client)
        } catch (e: EOFException) {
            AppLogger.d(TAG, "HTTP proxy client disconnected before completing its request (EOF)")
        } catch (e: SocketException) {
            AppLogger.d(TAG, "HTTP proxy client socket closed: ${e.message}")
        } catch (e: IOException) {
            AppLogger.d(TAG, "I/O error handling HTTP proxy client: ${e.message}")
        }
    }

    private suspend fun handleClientOrThrow(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            // Buffered: readLine() consumes one byte at a time, which would be one syscall per byte on a raw
            // socket stream. The same buffered stream is used for the later relay, so no data is lost.
            val input = java.io.BufferedInputStream(client.getInputStream(), 8 * 1024)
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            if (requestLine.isBlank()) return

            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                sendHttpError(output, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            val uriStr = parts[1]
            val httpVersion = if (parts.size >= 3) parts[2] else "HTTP/1.1"

            if (method == "CONNECT") {
                handleConnect(client, input, output, uriStr, httpVersion)
            } else {
                handleForward(client, input, output, method, uriStr, httpVersion)
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return InetAddresses.isNumericAddress(host)
        }
        if (host.contains(':')) {
            return host.all { it == ':' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun isLocalNetwork(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (!isIpLiteral(host)) return false
        val inet = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        val raw = inet.address
        if (inet.isAnyLocalAddress || inet.isLoopbackAddress || inet.isLinkLocalAddress || inet.isSiteLocalAddress || inet.isMulticastAddress) return true
        if (raw.size == 16) return (raw[0].toInt() and 0xFE) == 0xFC
        if (raw.size == 4) return raw.all { it.toInt() and 0xFF == 0xFF }
        return false
    }

    private suspend fun handleConnect(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        targetAuthority: String,
        httpVersion: String,
    ) {
        val connectHeaders = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            connectHeaders.add(line)
        }
        if (!isAuthorized(connectHeaders)) {
            sendProxyAuthRequired(output)
            return
        }

        val targetHost: String
        val targetPort: Int
        if (targetAuthority.startsWith("[")) {
            targetHost = targetAuthority.substringAfter("[").substringBefore("]")
            targetPort = targetAuthority.substringAfter("]:", "443").toIntOrNull() ?: 443
        } else if (targetAuthority.contains(":")) {
            targetHost = targetAuthority.substringBeforeLast(":")
            targetPort = targetAuthority.substringAfterLast(":").toIntOrNull() ?: 443
        } else {
            targetHost = targetAuthority
            targetPort = 443
        }

        if (HevSocks5TunnelConfig.isFakeDnsAddress(targetHost)) {
            sendHttpError(output, 404, "Not Found", "Synthetic DNS address with no live hostname mapping")
            return
        }

        if (isLocalNetwork(targetHost)) {
            sendHttpError(output, 403, "Forbidden", "Local network destinations are not carried by the tunnel")
            return
        }

        val targetKey = "$targetHost:$targetPort"
        if (isKnownUnreachable(targetKey)) {
            AppLogger.d(TAG, "refusing $targetKey: the edge refused this destination moments ago")
            sendHttpError(output, 502, "Bad Gateway", "Destination recently refused by upstream edge")
            return
        }

        val session = awaitUsableSession()
        if (session == null) {
            sendHttpError(output, 502, "Bad Gateway", "No VPN session available")
            return
        }

        if (session === unauthenticatedSession) {
            sendHttpError(output, 502, "Bad Gateway", "Proxy session unauthenticated")
            return
        }

        val tunnelResult = runCatching {
            withTimeoutOrNull(OPEN_STREAM_TIMEOUT_MS) { session.openStream(targetHost, targetPort) }
        }
        val tunneled = tunnelResult.getOrNull()

        if (tunneled == null) {
            val cause = tunnelResult.exceptionOrNull()
            if (shouldRememberRefusal(cause)) rememberUnreachable(targetKey, targetPort)
            when (health.observeFailure(targetKey, cause)) {
                UpstreamHealthTracker.Verdict.TARGET_FAILURE -> Unit
                UpstreamHealthTracker.Verdict.SESSION_UNHEALTHY -> {
                    AppLogger.w(
                        TAG,
                        "upstream session looks unhealthy: multiple destinations failed; asking for redial",
                    )
                    runCatching { onSessionUnhealthy() }
                }
                UpstreamHealthTracker.Verdict.SESSION_UNAUTHENTICATED -> {
                    val alreadyLatched = session === unauthenticatedSession
                    unauthenticatedSession = session
                    if (!alreadyLatched) {
                        AppLogger.w(
                            TAG,
                            "the edge rejected this session's proxy pass (${cause?.message}); asking for a redial",
                        )
                    }
                    runCatching { onSessionUnhealthy() }
                }
            }
            sendHttpError(output, 504, "Gateway Timeout", "Failed to connect to $targetKey: ${cause?.message ?: "timeout"}")
            return
        }

        health.observeSuccess()
        forgetUnreachable(targetKey)
        if (unauthenticatedSession != null && session !== unauthenticatedSession) {
            unauthenticatedSession = null
        }

        val response = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: MozVPN\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()

        socket.soTimeout = 0
        relayBidirectional(socket, input, output, tunneled)
    }

    private suspend fun handleForward(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        method: String,
        uriStr: String,
        httpVersion: String,
    ) {
        val headers = mutableListOf<String>()
        var hostHeader: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers.add(line)
            if (line.startsWith("Host:", ignoreCase = true)) {
                hostHeader = line.substringAfter(":").trim()
            }
        }

        if (!isAuthorized(headers)) {
            sendProxyAuthRequired(output)
            return
        }

        val parsedUri = runCatching { URI(uriStr) }.getOrNull()
        val targetHost = parsedUri?.host ?: hostHeader?.substringBefore(":") ?: ""
        val targetPort = if (parsedUri?.port != null && parsedUri.port != -1) {
            parsedUri.port
        } else if (hostHeader != null && hostHeader.contains(":")) {
            hostHeader.substringAfter(":").toIntOrNull() ?: 80
        } else {
            80
        }

        if (targetHost.isBlank()) {
            sendHttpError(output, 400, "Bad Request", "Missing destination host")
            return
        }

        if (HevSocks5TunnelConfig.isFakeDnsAddress(targetHost)) {
            sendHttpError(output, 404, "Not Found", "Synthetic DNS address with no live hostname mapping")
            return
        }

        if (isLocalNetwork(targetHost)) {
            sendHttpError(output, 403, "Forbidden", "Local network destinations are not carried by the tunnel")
            return
        }

        val targetKey = "$targetHost:$targetPort"
        if (isKnownUnreachable(targetKey)) {
            AppLogger.d(TAG, "refusing $targetKey: the edge refused this destination moments ago")
            sendHttpError(output, 502, "Bad Gateway", "Destination recently refused by upstream edge")
            return
        }

        val session = awaitUsableSession()
        if (session == null) {
            sendHttpError(output, 502, "Bad Gateway", "No VPN session available")
            return
        }

        if (session === unauthenticatedSession) {
            sendHttpError(output, 502, "Bad Gateway", "Proxy session unauthenticated")
            return
        }

        val tunnelResult = runCatching {
            withTimeoutOrNull(OPEN_STREAM_TIMEOUT_MS) { session.openStream(targetHost, targetPort) }
        }
        val tunneled = tunnelResult.getOrNull()

        if (tunneled == null) {
            val cause = tunnelResult.exceptionOrNull()
            if (shouldRememberRefusal(cause)) rememberUnreachable(targetKey, targetPort)
            when (health.observeFailure(targetKey, cause)) {
                UpstreamHealthTracker.Verdict.TARGET_FAILURE -> Unit
                UpstreamHealthTracker.Verdict.SESSION_UNHEALTHY -> {
                    AppLogger.w(
                        TAG,
                        "upstream session looks unhealthy: multiple destinations failed; asking for redial",
                    )
                    runCatching { onSessionUnhealthy() }
                }
                UpstreamHealthTracker.Verdict.SESSION_UNAUTHENTICATED -> {
                    val alreadyLatched = session === unauthenticatedSession
                    unauthenticatedSession = session
                    if (!alreadyLatched) {
                        AppLogger.w(
                            TAG,
                            "the edge rejected this session's proxy pass (${cause?.message}); asking for a redial",
                        )
                    }
                    runCatching { onSessionUnhealthy() }
                }
            }
            sendHttpError(output, 504, "Gateway Timeout", "Failed to connect to $targetKey: ${cause?.message ?: "timeout"}")
            return
        }

        health.observeSuccess()
        forgetUnreachable(targetKey)
        if (unauthenticatedSession != null && session !== unauthenticatedSession) {
            unauthenticatedSession = null
        }

        val path = if (parsedUri?.rawPath.isNullOrBlank()) "/" else parsedUri?.rawPath
        val query = if (parsedUri?.rawQuery.isNullOrBlank()) "" else "?${parsedUri?.rawQuery}"
        val rewrittenRequestLine = "$method $path$query $httpVersion\r\n"

        val upstreamOut = tunneled.output
        upstreamOut.write(rewrittenRequestLine.toByteArray(Charsets.US_ASCII))
        // Only the first request of a connection is routed (to this destination). Force one request per
        // connection so a keep-alive client cannot send a later request for another host down this tunnel,
        // except for protocol upgrades (e.g. WebSocket), which need their Connection header intact.
        val isUpgrade = headers.any { it.startsWith("Upgrade:", ignoreCase = true) }
        for (h in headers) {
            // Proxy-Authorization is meant for this proxy only; never leak it to the origin server.
            if (h.startsWith("Proxy-Connection:", ignoreCase = true) ||
                h.startsWith("Proxy-Authorization:", ignoreCase = true)
            ) {
                continue
            }
            if (!isUpgrade &&
                (h.startsWith("Connection:", ignoreCase = true) || h.startsWith("Keep-Alive:", ignoreCase = true))
            ) {
                continue
            }
            upstreamOut.write("$h\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (!isUpgrade) upstreamOut.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
        upstreamOut.write("\r\n".toByteArray(Charsets.US_ASCII))
        upstreamOut.flush()

        socket.soTimeout = 0
        relayBidirectional(socket, input, output, tunneled)
    }

    private suspend fun relayBidirectional(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        tunneled: com.vauth.foxyvpn.vpn.upstream.TunneledStream,
    ) {
        val clientToUpstream = scope.launch {
            runCatching { input.copyTo(tunneled.output, RELAY_BUFFER_BYTES) }
            runCatching { tunneled.output.close() }
        }

        runCatching { tunneled.input.copyTo(output, RELAY_BUFFER_BYTES) }
        runCatching { socket.shutdownOutput() }

        if (clientToUpstream.isActive) {
            val drained = withTimeoutOrNull(HALF_CLOSE_DRAIN_TIMEOUT_MS) { clientToUpstream.join() }
            if (drained == null) {
                AppLogger.d(TAG, "closing half-closed HTTP tunnel after drain timeout")
                runCatching { socket.close() }
                runCatching { tunneled.close() }
                clientToUpstream.join()
            }
        }
        tunneled.close()
    }

    private suspend fun awaitUsableSession(): UpstreamSession? {
        val immediate = sessionProvider()
        if (immediate != null && immediate.isConnected) return immediate

        val deadline = System.currentTimeMillis() + SESSION_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(SESSION_WAIT_POLL_INTERVAL_MS)
            val session = sessionProvider()
            if (session != null && session.isConnected) return session
        }
        return null
    }

    private fun isAuthorized(headers: List<String>): Boolean {
        val required = credentials ?: return true
        val value = headers.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return false
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(value.substring(6).trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        if (separator < 0) return false
        return required.matches(decoded.substring(0, separator), decoded.substring(separator + 1))
    }

    private fun sendProxyAuthRequired(output: OutputStream) {
        runCatching {
            val response = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                "Proxy-Authenticate: Basic realm=\"MozVPN\"\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        AppLogger.w(TAG, "rejected an HTTP proxy client without valid credentials")
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            } else if (b != '\r'.code) {
                baos.write(b)
                if (baos.size() > MAX_HEADER_SIZE_BYTES) error("HTTP header too long")
            }
        }
        return baos.toString("US-ASCII")
    }

    private fun sendHttpError(output: OutputStream, statusCode: Int, statusText: String, body: String = "") {
        runCatching {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
            if (bodyBytes.isNotEmpty()) {
                output.write(bodyBytes)
            }
            output.flush()
        }
    }

    private fun shouldRememberRefusal(cause: Throwable?): Boolean {
        val status = (cause as? UpstreamConnectRejectedException)?.statusCode ?: return false
        return status in UpstreamHealthTracker.TARGET_UNREACHABLE_STATUS_CODES
    }

    private fun isKnownUnreachable(key: String): Boolean = synchronized(unreachableTargets) {
        val record = unreachableTargets[key] ?: return false
        return System.currentTimeMillis() < record.expiresAt
    }

    private fun rememberUnreachable(key: String, targetPort: Int): Int = synchronized(unreachableTargets) {
        val record = unreachableTargets.getOrPut(key) { RefusalRecord() }
        record.strikes += 1
        val backoffMs = (UNREACHABLE_TARGET_TTL_MS shl (record.strikes - 1).coerceAtMost(16))
            .coerceAtMost(UNREACHABLE_TARGET_TTL_CAP_MS)
        record.expiresAt = System.currentTimeMillis() + backoffMs
        if (record.strikes == REFUSALS_BEFORE_TREATING_AS_POLICY) {
            AppLogger.d(
                TAG,
                "the edge has refused $key ${record.strikes} times in a row; further attempts will be refused on-device with backoff",
            )
        }
        return record.strikes
    }

    private fun forgetUnreachable(key: String) {
        synchronized(unreachableTargets) {
            if (unreachableTargets.remove(key) != null) {
                AppLogger.d(TAG, "$key succeeded; clearing its refusal backoff")
            }
        }
    }
}
