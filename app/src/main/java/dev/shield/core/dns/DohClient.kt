package dev.shield.core.dns

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * RFC 8484 DNS-over-HTTPS client. The upstream host is resolved only via the configured
 * bootstrap IPs (never via plaintext DNS); sockets are protected from the VPN.
 */
class DohClient(
    val url: String,
    bootstrap: List<String>,
    protect: ((Socket) -> Boolean)?,
) : DnsUpstream {

    private val host = url.toHttpUrlOrNull()?.host ?: ""
    private val bootstrapAddrs: List<InetAddress> = bootstrap.mapNotNull { ip ->
        runCatching { InetAddress.getByName(ip.trim()) }.getOrNull()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname.equals(host, ignoreCase = true) && bootstrapAddrs.isNotEmpty()) return bootstrapAddrs
                // literal IPs are fine, anything else would be a plaintext lookup
                return listOfNotNull(literal(hostname)).ifEmpty {
                    throw IOException("no bootstrap IP for $hostname")
                }
            }
        })
        .apply {
            if (protect != null) socketFactory(ProtectedSocketFactory(protect))
        }
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun resolve(query: ByteArray): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(MEDIA))
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            cont.resumeWithException(IOException("DoH HTTP ${it.code}"))
                            return
                        }
                        val body = it.body?.bytes()
                        if (body == null || body.size < 12) {
                            cont.resumeWithException(IOException("DoH short answer"))
                        } else {
                            cont.resume(body)
                        }
                    }
                }
            })
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class ProtectedSocketFactory(private val protect: (Socket) -> Boolean) : SocketFactory() {
        private fun p(s: Socket): Socket {
            // A fresh java.net.Socket has no file descriptor until it is bound or connected,
            // so protect() would fail. Binding to an ephemeral port creates the descriptor.
            if (!protect(s)) {
                s.bind(null)
                if (!protect(s)) {
                    s.close()
                    throw IOException("VpnService.protect failed")
                }
            }
            return s
        }
        override fun createSocket(): Socket = p(Socket())
        override fun createSocket(host: String?, port: Int): Socket =
            createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(java.net.InetSocketAddress(localHost, localPort))
                connect(java.net.InetSocketAddress(host, port))
            }
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(java.net.InetSocketAddress(localAddress, localPort))
                connect(java.net.InetSocketAddress(address, port))
            }
    }

    companion object {
        private val MEDIA = "application/dns-message".toMediaType()

        private val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun literal(host: String): InetAddress? =
            if (ipv4Re.matches(host) || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull() else null
    }
}

