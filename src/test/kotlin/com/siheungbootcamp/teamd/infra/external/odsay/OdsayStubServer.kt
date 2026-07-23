package com.siheungbootcamp.teamd.infra.external.odsay

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * ODsay Isochrone API를 모의하는 로컬 stub 서버.
 */
class OdsayStubServer(port: Int = 0) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val baseUrl: String
        get() {
            val port = (server.address as InetSocketAddress).port
            return "http://127.0.0.1:$port"
        }

    var responseMode: ResponseMode = ResponseMode.SUCCESS_POLYGON
    private val requestCount = AtomicInteger(0)

    init {
        server.createContext("/v1/api/searchPubTransIsochrone") { exchange ->
            handleIsochroneSearch(exchange)
        }
        server.setExecutor(null)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    fun requestCount(): Int = requestCount.get()
    fun resetCount() {
        requestCount.set(0)
    }

    private fun handleIsochroneSearch(exchange: HttpExchange) {
        val count = requestCount.incrementAndGet()

        when (responseMode) {
            ResponseMode.SUCCESS_POLYGON -> {
                // 단순 폴리곤 응답: 경기도 시흥시 근처 영역
                val response = """
                {
                  "result": {
                    "feature": [
                      {
                        "geometry": {
                          "type": "Polygon",
                          "coordinates": [[[127.0, 37.3], [127.2, 37.3], [127.2, 37.5], [127.0, 37.5], [127.0, 37.3]]]
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
                sendResponse(exchange, 200, response)
            }
            ResponseMode.SUCCESS_MULTIPOLYGON -> {
                // MultiPolygon 응답: 여러 폴리곤 조각
                val response = """
                {
                  "result": {
                    "feature": [
                      {
                        "geometry": {
                          "type": "MultiPolygon",
                          "coordinates": [[[[127.0, 37.3], [127.1, 37.3], [127.1, 37.4], [127.0, 37.4], [127.0, 37.3]]], [[[127.15, 37.35], [127.25, 37.35], [127.25, 37.45], [127.15, 37.45], [127.15, 37.35]]]]
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
                sendResponse(exchange, 200, response)
            }
            ResponseMode.TOO_MANY_REQUESTS -> {
                // 429 응답: 1회차는 429, 재시도에서 성공 응답 가능
                if (count <= 1) {
                    exchange.responseHeaders.set("Retry-After", "1")
                    sendResponse(exchange, 429, """{"error":"rate_limited"}""")
                } else {
                    val response = """
                    {
                      "result": {
                        "feature": [
                          {
                            "geometry": {
                              "type": "Polygon",
                              "coordinates": [[[127.0, 37.3], [127.2, 37.3], [127.2, 37.5], [127.0, 37.5], [127.0, 37.3]]]
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                    sendResponse(exchange, 200, response)
                }
            }
            ResponseMode.SERVER_ERROR -> {
                sendResponse(exchange, 500, """{"error":"server_error"}""")
            }
            ResponseMode.MALFORMED -> {
                // 응답 계약 위반: feature가 없거나 geometry가 잘못됨
                sendResponse(exchange, 200, """{"result": {}}""")
            }
        }
    }

    private fun sendResponse(exchange: HttpExchange, statusCode: Int, body: String) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(statusCode, body.toByteArray().size.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.responseBody.close()
    }

    enum class ResponseMode {
        SUCCESS_POLYGON,
        SUCCESS_MULTIPOLYGON,
        TOO_MANY_REQUESTS,
        SERVER_ERROR,
        MALFORMED
    }
}
