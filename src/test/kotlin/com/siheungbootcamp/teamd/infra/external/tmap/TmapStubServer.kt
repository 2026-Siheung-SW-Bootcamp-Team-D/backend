package com.siheungbootcamp.teamd.infra.external.tmap

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMAP Transit API를 모의하는 로컬 stub 서버.
 */
class TmapStubServer(port: Int = 0) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val baseUrl: String
        get() {
            val port = (server.address as InetSocketAddress).port
            return "http://127.0.0.1:$port"
        }

    var responseMode: ResponseMode = ResponseMode.SUCCESS
    private val requestCount = AtomicInteger(0)
    private val queuedModes = ArrayDeque<ResponseMode>()

    init {
        server.createContext("/transit/routes/sub") { exchange ->
            handleTransitSearch(exchange)
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

    fun queueResponses(vararg modes: ResponseMode) {
        synchronized(queuedModes) {
            queuedModes.clear()
            modes.forEach(queuedModes::addLast)
        }
    }

    private fun handleTransitSearch(exchange: HttpExchange) {
        val count = requestCount.incrementAndGet()

        val mode = synchronized(queuedModes) {
            if (queuedModes.isEmpty()) responseMode else queuedModes.removeFirst()
        }

        when (mode) {
            ResponseMode.SUCCESS -> {
                // 2026-07-20 실제 키로 검증한 응답 모양(docs/api-validation/results/4_tmap_요약정보_*.json)과
                // 같은 구조를 쓴다. totalTime/totalWalkTime은 이미 초 단위다.
                val response = """
                {
                  "metaData": {
                    "plan": {
                      "itineraries": [
                        {
                          "fare": { "regular": { "totalFare": 1550 } },
                          "totalTime": 1920,
                          "totalWalkTime": 420,
                          "pathType": 1,
                          "transferCount": 1,
                          "legs": [
                            { "mode": "WALK", "sectionTime": 120, "steps": [{ "linestring": "127.0,37.5 127.001,37.501" }] },
                            { "mode": "SUBWAY", "route": "수도권2호선", "start": { "name": "강남" }, "end": { "name": "신도림" }, "sectionTime": 1680, "passShape": { "linestring": "127.001,37.501 126.99,37.505 126.98,37.51" } }
                          ]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
                sendResponse(exchange, 200, response)
            }
            ResponseMode.NO_ROUTE -> {
                sendResponse(exchange, 200, """{"metaData":{"plan":{"itineraries":[]}}}""")
            }
            ResponseMode.SERVER_ERROR -> {
                sendResponse(exchange, 500, """{"error":"server_error"}""")
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
        SUCCESS, NO_ROUTE, SERVER_ERROR
    }
}
