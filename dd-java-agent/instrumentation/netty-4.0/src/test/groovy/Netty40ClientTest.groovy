import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import io.opentracing.tag.Tags
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.asynchttpclient.Dsl.asyncHttpClient

class Netty40ClientTest extends AgentTestRunner {

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      all {
        response.send("Hello World")
      }
    }
  }
  @Shared
  def clientConfig = DefaultAsyncHttpClientConfig.Builder.newInstance().setRequestTimeout(TimeUnit.SECONDS.toMillis(10).toInteger())
  @Shared
  AsyncHttpClient asyncHttpClient = asyncHttpClient(clientConfig)

  def "test server request/response"() {
    setup:
    def responseFuture = runUnderTrace("parent") {
      asyncHttpClient.prepareGet("$server.address").execute()
    }
    def response = responseFuture.get()

    expect:
    response.statusCode == 200
    response.responseBody == "Hello World"

    and:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "netty.client.request"
          resourceName "GET /"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(1)
          errored false
          tags {
            "$Tags.COMPONENT.key" "netty-client"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" "$server.address/"
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_HOST_IPV4.key" "127.0.0.1"
            "$Tags.PEER_PORT.key" server.address.port
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(1) {
          operationName "parent"
          parent()
        }
      }
    }

    and:
    server.lastRequest.headers.get("x-datadog-trace-id") == "${TEST_WRITER.get(0).get(0).traceId}"
    server.lastRequest.headers.get("x-datadog-parent-id") == "${TEST_WRITER.get(0).get(0).spanId}"
  }

  def "test connection failure"() {
    setup:
    def invalidPort = PortUtils.randomOpenPort()

    def responseFuture = runUnderTrace("parent") {
      asyncHttpClient.prepareGet("http://localhost:$invalidPort/").execute()
    }

    when:
    responseFuture.get()

    then:
    def throwable = thrown(ExecutionException)
    throwable.cause instanceof ConnectException

    and:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "parent"
          parent()
        }
        span(1) {
          operationName "netty.connect"
          resourceName "netty.connect"
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT.key" "netty"
            Class errorClass = ConnectException
            try {
              errorClass = Class.forName('io.netty.channel.AbstractChannel$AnnotatedConnectException')
            } catch (ClassNotFoundException e) {
              // Older versions use 'java.net.ConnectException' and do not have 'io.netty.channel.AbstractChannel$AnnotatedConnectException'
            }
            errorTags errorClass, "Connection refused: localhost/127.0.0.1:$invalidPort"
            defaultTags()
          }
        }
      }
    }
  }
}
