package com.imhamiddev.mozvpn.vpn.socks

import java.security.MessageDigest
import java.security.SecureRandom

/** Username/password a client must present to the local SOCKS5 / HTTP proxy servers. */
class ProxyCredentials(val username: String, val password: String) {

    fun matches(candidateUser: String, candidatePassword: String): Boolean {
        // Evaluate both comparisons (no short-circuit) and compare in constant time.
        val userOk = MessageDigest.isEqual(username.toByteArray(Charsets.UTF_8), candidateUser.toByteArray(Charsets.UTF_8))
        val passOk = MessageDigest.isEqual(password.toByteArray(Charsets.UTF_8), candidatePassword.toByteArray(Charsets.UTF_8))
        return userOk and passOk
    }

    companion object {
        /** Fresh random credentials for the app's own TUN <-> SOCKS5 link; never persisted or shown. */
        fun random(): ProxyCredentials {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return ProxyCredentials("mozvpn", bytes.joinToString("") { "%02x".format(it) })
        }

        /** User-configured credentials; null (no authentication) when the username is blank. */
        fun fromSettings(username: String, password: String): ProxyCredentials? =
            if (username.isBlank()) null else ProxyCredentials(username, password)
    }
}
