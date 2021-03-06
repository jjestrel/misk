package misk.web

import com.google.inject.Provides
import misk.MiskServiceModule
import misk.environment.Environment
import misk.environment.EnvironmentModule
import misk.inject.KAbstractModule
import misk.security.ssl.CertStoreConfig
import misk.security.ssl.SslLoader
import misk.web.proxy.WebProxyEntry
import misk.web.resources.StaticResourceEntry
import javax.inject.Singleton

/**
 * A module that starts an embedded Jetty web server configured for testing. The server supports
 * both plaintext and TLS.
 */
class WebTestingModule(
  private val webConfig: WebConfig = WebConfig(
      port = 0,
      idle_timeout = 500000,
      host = "127.0.0.1",
      ssl = WebSslConfig(
          port = 0,
          cert_store = CertStoreConfig(
              resource = "classpath:/ssl/server_cert_key_combo.pem",
              passphrase = "serverpassword",
              format = SslLoader.FORMAT_PEM
          ),
          mutual_auth = WebSslConfig.MutualAuth.NONE))
) : KAbstractModule() {
  override fun configure() {
    install(EnvironmentModule(Environment.TESTING))
    install(MiskServiceModule())
    install(MiskWebModule())
  }

  @Provides
  @Singleton
  fun provideWebConfig() = webConfig
}
