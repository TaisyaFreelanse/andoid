package com.automation.agent.network

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * Creates sockets through SOCKS5 proxy with username/password auth.
 * Java's built-in SOCKS + Authenticator doesn't work on Android, so we implement the handshake manually.
 */
object Socks5SocketFactory {
    private const val TAG = "Socks5SocketFactory"

    /** Verbose trace for remote debugging (no passwords). */
    fun interface Trace {
        fun line(msg: String)
    }

    /**
     * Connect to targetHost:targetPort through SOCKS5 proxy.
     */
    fun connectThroughSocks5(
        proxyHost: String,
        proxyPort: Int,
        proxyUser: String,
        proxyPass: String,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 30000,
        trace: Trace? = null
    ): Socket {
        fun t(msg: String) {
            trace?.line(msg)
            Log.i(TAG, msg)
        }
        t("SOCKS5 connect: proxy=$proxyHost:$proxyPort -> $targetHost:$targetPort (userLen=${proxyUser.length} passLen=${proxyPass.length})")
        val socket = Socket()
        socket.soTimeout = readTimeoutMs
        socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
        t("SOCKS5: TCP to proxy OK")
        try {
            val out: OutputStream = socket.getOutputStream()
            val inp: InputStream = socket.getInputStream()

            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()
            t("SOCKS5: sent greeting VER5 methods [none, userpass]")

            val methodResp = ByteArray(2)
            readFully(inp, methodResp)
            if (methodResp[0] != 0x05.toByte()) throw Exception("SOCKS5: invalid version ${methodResp[0]}")
            val method = methodResp[1].toInt() and 0xFF
            t("SOCKS5: server chose method=0x${method.toString(16)}")
            if (method == 0xFF) throw Exception("SOCKS5: no acceptable methods")

            if (method == 0x02) {
                val userBytes = proxyUser.toByteArray(Charsets.UTF_8)
                val passBytes = proxyPass.toByteArray(Charsets.UTF_8)
                if (userBytes.size > 255 || passBytes.size > 255) throw Exception("SOCKS5: username/password too long")
                val auth = ByteArray(3 + userBytes.size + passBytes.size)
                auth[0] = 0x01
                auth[1] = userBytes.size.toByte()
                System.arraycopy(userBytes, 0, auth, 2, userBytes.size)
                auth[2 + userBytes.size] = passBytes.size.toByte()
                System.arraycopy(passBytes, 0, auth, 3 + userBytes.size, passBytes.size)
                out.write(auth)
                out.flush()
                t("SOCKS5: sent RFC1929 user/pass")

                val authResp = ByteArray(2)
                readFully(inp, authResp)
                if (authResp[0] != 0x01.toByte()) throw Exception("SOCKS5 auth: invalid version")
                if (authResp[1] != 0x00.toByte()) {
                    throw Exception("SOCKS5 auth failed status=0x${(authResp[1].toInt() and 0xFF).toString(16)}")
                }
                t("SOCKS5: auth OK")
            } else if (method == 0x00) {
                t("SOCKS5: no auth required by server")
            } else {
                throw Exception("SOCKS5: unsupported method 0x${method.toString(16)}")
            }

            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            if (hostBytes.size > 255) throw Exception("SOCKS5: host too long")
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = (targetPort shr 8).toByte()
            req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            out.write(req)
            out.flush()
            t("SOCKS5: sent CONNECT domain=$targetHost port=$targetPort")

            val rep = ByteArray(4)
            readFully(inp, rep)
            if (rep[0] != 0x05.toByte()) throw Exception("SOCKS5 connect: invalid version")
            if (rep[1] != 0x00.toByte()) {
                val err = rep[1].toInt() and 0xFF
                val errName = when (err) {
                    1 -> "general failure"
                    2 -> "not allowed"
                    3 -> "network unreachable"
                    4 -> "host unreachable"
                    5 -> "connection refused"
                    6 -> "TTL expired"
                    7 -> "command not supported"
                    8 -> "address type not supported"
                    else -> "code $err"
                }
                throw Exception("SOCKS5 CONNECT failed: $errName (0x${err.toString(16)})")
            }
            val atyp = rep[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> readFully(inp, ByteArray(6))
                0x03 -> { val len = inp.read() and 0xFF; readFully(inp, ByteArray(len + 2)) }
                0x04 -> readFully(inp, ByteArray(18))
                else -> throw Exception("SOCKS5: unknown address type $atyp")
            }
            t("SOCKS5: CONNECT tunnel ready (ATYP=$atyp)")
            return socket
        } catch (e: Exception) {
            t("SOCKS5: FAIL ${e.javaClass.simpleName}: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun readFully(inp: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = inp.read(b, n, b.size - n)
            if (r <= 0) throw Exception("SOCKS5: connection closed (read $n/${b.size})")
            n += r
        }
    }

    /**
     * Fetch URL via SOCKS5 proxy with auth. For HTTPS, connects then does SSL handshake.
     */
    fun fetchHttpsViaSocks5(
        proxyHost: String,
        proxyPort: Int,
        proxyUser: String,
        proxyPass: String,
        url: String,
        trace: Trace? = null
    ): String? {
        fun t(msg: String) {
            trace?.line(msg)
            Log.i(TAG, msg)
        }
        return try {
            val u = java.net.URL(url)
            val host = u.host
            val port = if (u.port > 0) u.port else 443
            t("fetchHttps: URL host=$host port=$port path=${u.path} query=${u.query != null}")
            val socket = connectThroughSocks5(
                proxyHost, proxyPort, proxyUser, proxyPass, host, port,
                trace = trace
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
            sslSocket.use {
                t("TLS: starting handshake...")
                it.startHandshake()
                t("TLS: handshake OK cipher=${it.session?.cipherSuite}")
                val path = u.path.ifEmpty { "/" }
                val query = if (u.query != null) "?${u.query}" else ""
                val request = "GET $path$query HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                it.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                it.getOutputStream().flush()
                t("HTTP: sent GET $path$query")
                val response = it.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                val firstLineEnd = response.indexOf('\r')
                val statusLine = if (firstLineEnd > 0) response.substring(0, firstLineEnd) else response.take(80)
                t("HTTP: statusLine=[$statusLine] totalChars=${response.length}")
                val bodyStart = response.indexOf("\r\n\r\n")
                val body = if (bodyStart >= 0) response.substring(bodyStart + 4).trim() else response.trim()
                val preview = body.take(300).replace("\n", "\\n")
                t("HTTP: body preview (300): $preview")
                body
            }
        } catch (e: Exception) {
            val stack = e.stackTraceToString().take(600)
            t("fetchHttps FAILED: ${e.javaClass.simpleName}: ${e.message}")
            t("fetchHttps stack: $stack")
            Log.e(TAG, "fetchHttpsViaSocks5 failed: ${e.message}", e)
            null
        }
    }
}
