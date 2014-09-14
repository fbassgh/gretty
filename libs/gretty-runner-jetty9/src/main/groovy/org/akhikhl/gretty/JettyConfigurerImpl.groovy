/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty
import ch.qos.logback.classic.selector.servlet.ContextDetachingSCL
import ch.qos.logback.classic.selector.servlet.LoggerContextFilter
import org.eclipse.jetty.annotations.AnnotationConfiguration
import org.eclipse.jetty.plus.webapp.EnvConfiguration
import org.eclipse.jetty.plus.webapp.PlusConfiguration
import org.eclipse.jetty.security.HashLoginService
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.server.session.HashSessionManager
import org.eclipse.jetty.util.component.LifeCycle
import org.eclipse.jetty.util.resource.FileResource
import org.eclipse.jetty.util.resource.Resource
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.webapp.*
import org.eclipse.jetty.xml.XmlConfiguration
import org.slf4j.Logger

import javax.servlet.DispatcherType
/**
 *
 * @author akhikhl
 */
class JettyConfigurerImpl implements JettyConfigurer {
  
  protected static boolean isServletApi(String filePath) {
    filePath.matches(/^.*servlet-api.*\.jar$/)
  }

  private Logger log
  private SSOAuthenticatorFactory ssoAuthenticatorFactory
  private HashSessionManager sharedSessionManager

  @Override
  void addLifeCycleListener(server, listener) {
    server.addLifeCycleListener(listener as LifeCycle.Listener)
  }

  @Override
  void applyContextConfigFile(webAppContext, URL contextConfigFile) {
    if(contextConfigFile) {
      log.warn 'Configuring {} with {}', webAppContext.contextPath, contextConfigFile
      XmlConfiguration xmlConfiguration = new XmlConfiguration(contextConfigFile)
      xmlConfiguration.configure(webAppContext)
    }
  }

  @Override
  void applyJettyXml(server, String jettyXml) {
    if(jettyXml != null) {
      log.warn 'Configuring server with {}', jettyXml
      XmlConfiguration xmlConfiguration = new XmlConfiguration(new File(jettyXml).toURI().toURL())
      xmlConfiguration.configure(server)
    }
  }

  @Override
  void configureConnectors(server, Map params) {

    HttpConfiguration http_config = new HttpConfiguration()
    if(params.httpsPort) {
      http_config.setSecureScheme('https')
      http_config.setSecurePort(params.httpsPort)
    }

    Connector httpConn = findHttpConnector(server)

    boolean newHttpConnector = false
    if(params.httpEnabled && !httpConn) {
      newHttpConnector = true
      httpConn = new ServerConnector(server, new HttpConnectionFactory(http_config))
      httpConn.soLingerTime = -1
    }

    if(httpConn) {
      if(!httpConn.host)
        httpConn.host = params.host ?: ServerDefaults.defaultHost

      if(!httpConn.port)
        httpConn.port = params.httpPort ?: ServerDefaults.defaultHttpPort

      if(params.httpIdleTimeout)
        httpConn.idleTimeout = params.httpIdleTimeout

      if(newHttpConnector)
        server.addConnector(httpConn)
    }

    Connector httpsConn = findHttpsConnector(server)

    boolean newHttpsConnector = false
    if(params.httpsEnabled && !httpsConn) {
      newHttpsConnector = true
      HttpConfiguration https_config = new HttpConfiguration(http_config)
      https_config.addCustomizer(new SecureRequestCustomizer())
      httpsConn = new ServerConnector(server,
        new SslConnectionFactory(new SslContextFactory(), 'http/1.1'),
        new HttpConnectionFactory(https_config))
      httpsConn.soLingerTime = -1
    }

    if(httpsConn) {
      if(!httpsConn.host)
        httpsConn.host = params.host ?: ServerDefaults.defaultHost

      if(!httpsConn.port)
        httpsConn.port = params.httpsPort ?: ServerDefaults.defaultHttpsPort

      def sslContextFactory = httpsConn.getConnectionFactories().find { it instanceof SslConnectionFactory }?.getSslContextFactory()
      if(sslContextFactory) {
        if(params.sslKeyStorePath) {
          if(params.sslKeyStorePath.startsWith('classpath:')) {
            String resString = params.sslKeyStorePath - 'classpath:'
            URL url = getClass().getResource(resString)
            if(url == null)
              throw new Exception("Could not resource referenced in sslKeyStorePath: '${resString}'")
            sslContextFactory.setKeyStoreResource(new FileResource(url))
          }
          else
            sslContextFactory.setKeyStorePath(params.sslKeyStorePath)
        }
        if(params.sslKeyStorePassword)
          sslContextFactory.setKeyStorePassword(params.sslKeyStorePassword)
        if(params.sslKeyManagerPassword)
          sslContextFactory.setKeyManagerPassword(params.sslKeyManagerPassword)
        if(params.sslTrustStorePath) {
          if(params.sslTrustStorePath.startsWith('classpath:')) {
            String resString = params.sslTrustStorePath - 'classpath:'
            URL url = getClass().getResource(resString)
            if(url == null)
              throw new Exception("Could not resource referenced in sslTrustStorePath: '${resString}'")
            sslContextFactory.setTrustStoreResource(new FileResource(url))
          }
          else
            sslContextFactory.setTrustStorePath(params.sslTrustStorePath)
        }
        if(params.sslTrustStorePassword)
          sslContextFactory.setTrustStorePassword(params.sslTrustStorePassword)
      }

      if(params.httpsIdleTimeout)
        httpsConn.idleTimeout = params.httpsIdleTimeout

      if(newHttpsConnector)
        server.addConnector(httpsConn)
    }
  }

  @Override
  void configureSecurity(context, String realm, String realmConfigFile, boolean singleSignOn) {
    context.securityHandler.loginService = new HashLoginService(realm, realmConfigFile)
    if(singleSignOn) {
      if(ssoAuthenticatorFactory == null)
        ssoAuthenticatorFactory = new SSOAuthenticatorFactory()
      context.securityHandler.authenticatorFactory = ssoAuthenticatorFactory
    }
  }

  @Override
  void configureSessionManager(server, context, Map serverParams, Map webappParams) {
    HashSessionManager sessionManager
    if(serverParams.singleSignOn) {
      sessionManager = sharedSessionManager
      if(sessionManager == null) {
        sessionManager = sharedSessionManager = new HashSessionManager()
        sessionManager.setMaxInactiveInterval(60 * 30) // 30 minutes
        sessionManager.getSessionCookieConfig().setPath('/')
      }
    } else {
      sessionManager = new HashSessionManager()
      sessionManager.setMaxInactiveInterval(60 * 30) // 30 minutes
    }
    context.getSessionHandler().setSessionManager(sessionManager)
  }

  @Override
  def createResourceCollection(List paths) {
    new ResourceCollection(paths as String[])
  }

  @Override
  def createServer() {
    // fix for issue https://github.com/akhikhl/gretty/issues/24
    org.eclipse.jetty.util.resource.Resource.defaultUseCaches = false
    return new Server()
  }
 
  @Override
  def createWebAppContext(List<String> webappClassPath) {
    JettyWebAppContext context = new JettyWebAppContext()
    context.setWebInfLib(webappClassPath.findAll { it.endsWith('.jar') && !isServletApi(it) }.collect { new File(it) })
    context.setExtraClasspath(webappClassPath.collect { it.endsWith('.jar') ? it : (it.endsWith('/') ? it : it + '/') }.findAll { !isServletApi(it) }.join(';'))
    context.addEventListener(new ContextDetachingSCL())
    context.addFilter(LoggerContextFilter.class, '/*', EnumSet.of(DispatcherType.REQUEST))
    return context
  }

  @Override
  def findHttpConnector(server) {
    server.connectors.find { it.connectionFactories.find { it.protocol.startsWith('HTTP') } && !it.connectionFactories.find { it.protocol.startsWith('SSL') } }
  }

  @Override
  def findHttpsConnector(server) {
    server.connectors.find { it.connectionFactories.find { it.protocol.startsWith('HTTP') } && it.connectionFactories.find { it.protocol.startsWith('SSL') } }
  }

  @Override
  URL findResourceURL(baseResource, String path) {
    Resource res
    if(baseResource instanceof ResourceCollection)
      res = baseResource.findResource(path)
    else
      res = baseResource.addPath(path)
    if(res.exists())
      return res.getURL()
    null
  }

  @Override
  List getConfigurations(Map webappParams) {
    [
      new WebInfConfigurationEx(),
      new WebXmlConfiguration(),
      new MetaInfConfiguration(),
      new FragmentConfiguration(),
      new EnvConfiguration(),
      new PlusConfiguration(),
      new AnnotationConfiguration(),
      new JettyWebXmlConfiguration()
    ]
  }

  @Override
  void setConfigurationsToWebAppContext(webAppContext, List configurations) {
    webAppContext.setConfigurations(configurations as Configuration[])
  }

  @Override
  void setHandlersToServer(server, List handlers) {
    ContextHandlerCollection contexts = new ContextHandlerCollection()
    contexts.setHandlers(handlers as Handler[])
    server.setHandler(contexts)
  }

  @Override
  void setLogger(Logger log) {
    this.log = log
  }
}
