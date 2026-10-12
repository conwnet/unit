package net.conw.unit.proxy

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketImpl
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** TLS to the proxy, underneath OkHttp's normal HTTP proxy and CONNECT handling. */
internal class HttpsProxySocketFactory(
    private val host: String,
    private val tls: SSLSocketFactory,
) : SocketFactory() {
    override fun createSocket(): Socket = ProxySocket()

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(local, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress,
        port: Int,
        local: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket().apply {
            bind(InetSocketAddress(local, localPort))
            connect(InetSocketAddress(host, port))
        }

    // The delegate owns the connection; a null SocketImpl makes system TLS use its streams.
    private inner class ProxySocket : Socket(null as SocketImpl?) {
        @Volatile private var delegate = Socket()

        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = endpoint as InetSocketAddress
            val plain = delegate
            plain.connect(address, timeout)
            val secure = tls.createSocket(plain, host, address.port, true) as SSLSocket
            // Give proxy TLS negotiation the same bound as TCP connection establishment.
            val readTimeout = plain.soTimeout
            secure.soTimeout = if (timeout > 0) timeout else readTimeout
            secure.sslParameters =
                secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            delegate = secure
            secure.startHandshake()
            secure.soTimeout = readTimeout
        }

        override fun bind(address: SocketAddress?) = delegate.bind(address)

        override fun getInputStream() = delegate.getInputStream()

        override fun getOutputStream() = delegate.getOutputStream()

        override fun close() = delegate.close()

        override fun shutdownInput() = delegate.shutdownInput()

        override fun shutdownOutput() = delegate.shutdownOutput()

        override fun isConnected() = delegate.isConnected

        override fun isBound() = delegate.isBound

        override fun isClosed() = delegate.isClosed

        override fun isInputShutdown() = delegate.isInputShutdown

        override fun isOutputShutdown() = delegate.isOutputShutdown

        override fun getInetAddress() = delegate.inetAddress

        override fun getLocalAddress() = delegate.localAddress

        override fun getPort() = delegate.port

        override fun getLocalPort() = delegate.localPort

        override fun getRemoteSocketAddress() = delegate.remoteSocketAddress

        override fun getLocalSocketAddress() = delegate.localSocketAddress

        override fun setSoTimeout(value: Int) {
            delegate.soTimeout = value
        }

        override fun getSoTimeout() = delegate.soTimeout

        override fun setTcpNoDelay(value: Boolean) {
            delegate.tcpNoDelay = value
        }

        override fun getTcpNoDelay() = delegate.tcpNoDelay

        override fun setKeepAlive(value: Boolean) {
            delegate.keepAlive = value
        }

        override fun getKeepAlive() = delegate.keepAlive

        override fun setReuseAddress(value: Boolean) {
            delegate.reuseAddress = value
        }

        override fun getReuseAddress() = delegate.reuseAddress

        override fun setReceiveBufferSize(value: Int) {
            delegate.receiveBufferSize = value
        }

        override fun getReceiveBufferSize() = delegate.receiveBufferSize

        override fun setSendBufferSize(value: Int) {
            delegate.sendBufferSize = value
        }

        override fun getSendBufferSize() = delegate.sendBufferSize

        override fun setSoLinger(on: Boolean, value: Int) = delegate.setSoLinger(on, value)

        override fun getSoLinger() = delegate.soLinger

        override fun setTrafficClass(value: Int) {
            delegate.trafficClass = value
        }

        override fun getTrafficClass() = delegate.trafficClass
    }
}
