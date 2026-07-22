package com.siheungbootcamp.teamd.infra.external.tmap

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
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

    init {
        server.createContext("/tmap/routes/recognition") { exchange ->
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

    private fun handleTransitSearch(exchange: HttpExchange) {
        val count = requestCount.incrementAndGet()

        when (responseMode) {
            ResponseMode.SUCCESS -> {
                val response = """
                {
                  "summary": {
                    "totalTime": 1920000,
                    "transferCount": 1,
                    "totalFare": 1550,
                    "totalWalkTime": 420000
                  }
                }
                """.trimIndent()
                sendResponse(exchange, 200, response)
            }
            ResponseMode.NO_ROUTE -> {
                sendResponse(exchange, 200, """{"summary":{}}""")
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
