/*
 * Gretty
 *
 * Copyright (C) 2013-2014 Andrey Hihlovskiy and contributors.
 *
 * See the file "LICENSE" for copying and usage permission.
 * See the file "CONTRIBUTORS" for complete list of contributors.
 */
package org.akhikhl.gretty

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
class JettyServerStartInfo {

  protected final Logger log

  JettyServerStartInfo() {
    log = LoggerFactory.getLogger(this.getClass())
  }

  def getInfo(server, JettyConfigurer configurer, Map params) {

    def portsInfo = server.getConnectors().collect { it.port }
    portsInfo = (portsInfo.size() == 1 ? 'port ' : 'ports ') + portsInfo.join(', ')
    log.warn '{} started and listening on {}', params.servletContainerDescription, portsInfo

    def httpConn = configurer.findHttpConnector(server)
    def httpsConn = configurer.findHttpsConnector(server)

    List contextInfo = []
  
    def collectContextInfo
    collectContextInfo = { handler ->
      if(handler.respondsTo('getHandlers')) {
        for(def h in handler.getHandlers()) {
          collectContextInfo(h)
        }
      } else {
        if(handler.respondsTo('getDisplayName'))
          log.warn '{} runs at:', (handler.displayName - '/')
        if(handler.respondsTo('getContextPath')) {
          if(httpConn) {
            log.warn '  http://{}:{}{}', (httpConn.host == '0.0.0.0' ? 'localhost' : httpConn.host), httpConn.port, handler.contextPath
            httpConn.with {
              contextInfo.add([ protocol: 'http', host: host, port: port, contextPath: handler.contextPath, baseURI: "http://${host}:${port}${handler.contextPath}" ])
            }
          }
          if(httpsConn) {
            log.warn '  https://{}:{}{}', (httpsConn.host == '0.0.0.0' ? 'localhost' : httpsConn.host), httpsConn.port, handler.contextPath
            httpsConn.with {
              contextInfo.add([ protocol: 'https', host: host, port: port, contextPath: handler.contextPath, baseURI: "https://${host}:${port}${handler.contextPath}" ])
            }
          }
        }
      }
    }

    collectContextInfo(server.handler)

    def serverStartInfo = [ status: 'successfully started' ]

    serverStartInfo.host = httpConn?.host ?: httpsConn?.host

    if(httpConn)
      serverStartInfo.httpPort = httpConn.port

    if(httpsConn)
      serverStartInfo.httpsPort = httpsConn.port

    serverStartInfo.contexts = contextInfo
    serverStartInfo
  }
}

