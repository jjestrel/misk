package misk.web.jetty

import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.AbstractIdleService
import com.google.inject.Key
import misk.DependentService
import misk.inject.toKey
import misk.logging.getLogger
import misk.security.ssl.CipherSuites
import misk.security.ssl.SslLoader
import misk.security.ssl.TlsProtocols
import misk.web.ForConscrypt
import misk.web.WebConfig
import misk.web.WebSslConfig
import okhttp3.HttpUrl
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.util.ssl.SslContextFactory
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.security.Provider as SecurityProvider

private val logger = getLogger<JettyService>()

@Singleton
class JettyService @Inject internal constructor(
  private val sslLoader: SslLoader,
  private val webActionsServlet: WebActionsServlet,
  private val webConfig: WebConfig,
  @ForConscrypt private val conscryptProvider: Provider<SecurityProvider>
) : AbstractIdleService(), DependentService {
  private val server = Server()

  val httpServerUrl: HttpUrl get() = server.httpUrl!!
  val httpsServerUrl: HttpUrl? get() = server.httpsUrl

  override val consumedKeys = setOf<Key<*>>(SecurityProvider::class.toKey(ForConscrypt::class))
  override val producedKeys = setOf<Key<*>>()

  override fun startUp() {
    val stopwatch = Stopwatch.createStarted()
    logger.info("Starting Jetty")

    val httpConfig = HttpConfiguration()
    httpConfig.sendServerVersion = false
    if (webConfig.ssl != null) {
      httpConfig.securePort = webConfig.ssl.port
    }

    // TODO(mmihic): Allow require running only on HTTPS?
    val httpConnector = ServerConnector(server, HttpConnectionFactory(httpConfig))
    httpConnector.port = webConfig.port
    httpConnector.idleTimeout = webConfig.idle_timeout
    httpConnector.reuseAddress = true
    webConfig.host?.let { httpConnector.host = it }
    server.addConnector(httpConnector)

    if (webConfig.ssl != null) {
      val sslContextFactory = SslContextFactory()
      sslContextFactory.provider = conscryptProvider.get().name
      sslContextFactory.keyStore = sslLoader.loadCertStore(webConfig.ssl.cert_store)!!.keyStore
      sslContextFactory.setKeyStorePassword(webConfig.ssl.cert_store.passphrase)
      webConfig.ssl.trust_store?.let {
        sslContextFactory.trustStore = sslLoader.loadTrustStore(it)!!.keyStore
      }
      when (webConfig.ssl.mutual_auth) {
        WebSslConfig.MutualAuth.REQUIRED -> sslContextFactory.needClientAuth = true
        WebSslConfig.MutualAuth.DESIRED -> sslContextFactory.wantClientAuth = true
        WebSslConfig.MutualAuth.NONE -> {
          // Neither needed nor wanted
        }
      }

      // By default, Jetty excludes a number of common cipher suites. This default set is too
      // restrictive. Clear the set of excluded suites and define the suites to include below.
      sslContextFactory.setExcludeCipherSuites()
      sslContextFactory.setIncludeProtocols(*TlsProtocols.safe)
      sslContextFactory.setIncludeCipherSuites(*CipherSuites.safe)

      val httpsConfig = HttpConfiguration(httpConfig)
      httpsConfig.addCustomizer(SecureRequestCustomizer())

      val alpn = ALPNServerConnectionFactory("h2", "http/1.1")
      alpn.defaultProtocol = "http/1.1"
      val ssl = SslConnectionFactory(sslContextFactory, alpn.protocol)
      val http2 = HTTP2ServerConnectionFactory(httpsConfig)
      val http1 = HttpConnectionFactory(httpsConfig)
      val httpsConnector = ServerConnector(server, ssl, alpn, http2, http1)
      httpsConnector.port = webConfig.ssl.port
      httpsConnector.idleTimeout = webConfig.idle_timeout
      httpsConnector.reuseAddress = true
      webConfig.host?.let { httpsConnector.host = it }
      server.addConnector(httpsConnector)
    }

    // TODO(mmihic): Force security handler?
    val servletContextHandler = ServletContextHandler()
    servletContextHandler.addServlet(ServletHolder(webActionsServlet), "/*")
    server.addManaged(servletContextHandler)
    server.handler = servletContextHandler

    server.start()

    logger.info {
      if (webConfig.ssl != null)
        "Started Jetty in $stopwatch on port ${webConfig.port}/${webConfig.ssl.port}"
      else
        "Started Jetty in $stopwatch on port ${webConfig.port}"
    }
  }

  override fun shutDown() {
    val stopwatch = Stopwatch.createStarted()
    logger.info("Stopping Jetty")

    server.stop()

    logger.info { "Stopped Jetty in $stopwatch" }
  }
}

private val Server.httpUrl: HttpUrl?
  get() {
    return connectors
        .mapNotNull { it as? NetworkConnector }
        .firstOrNull { it.defaultConnectionFactory is HttpConnectionFactory }
        ?.toHttpUrl()
  }

private val Server.httpsUrl: HttpUrl?
  get() {
    return connectors
        .mapNotNull { it as? NetworkConnector }
        .firstOrNull { it.defaultConnectionFactory is SslConnectionFactory }
        ?.toHttpUrl()
  }

private fun NetworkConnector.toHttpUrl(): HttpUrl {
  val context = server.getChildHandlerByClass(ContextHandler::class.java)
  val protocol = defaultConnectionFactory.protocol
  val scheme = if (protocol.startsWith("SSL-") || protocol == "SSL") "https" else "http"

  val virtualHosts = context?.virtualHosts ?: arrayOf<String>()
  val explicitHost = if (virtualHosts.isEmpty()) host else virtualHosts[0]

  return HttpUrl.Builder()
      .scheme(scheme)
      .host(explicitHost ?: InetAddress.getLocalHost().hostAddress)
      .port(localPort)
      .build()
}

