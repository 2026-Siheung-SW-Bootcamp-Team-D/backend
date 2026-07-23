package com.siheungbootcamp.teamd.infra.external.kakao

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kakao Local API를 모의하는 로컬 stub 서버.
 *
 * 테스트에서 실제 Kakao API 호출 없이 예상 응답을 반환한다.
 */
class KakaoStubServer(port: Int = 0) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val baseUrl: String
        get() {
            val port = (server.address as InetSocketAddress).port
            return "http://127.0.0.1:$port"
        }

    private val requestCounts = mutableMapOf<String, AtomicInteger>()
    var tmapRequestCount: Int = 0

    init {
        server.createContext("/v2/local/search/keyword.json") { exchange ->
            handleKeywordSearch(exchange)
        }
        server.createContext("/v2/local/search/address.json") { exchange ->
            handleAddressSearch(exchange)
        }
        server.createContext("/v2/local/geo/coord2address.json") { exchange ->
            handleCoord2Address(exchange)
        }
        server.setExecutor(null)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    fun setKeywordResponseMode(mode: ResponseMode) {
        requestCounts["keyword"] = AtomicInteger(0)
        this.keywordMode = mode
    }

    fun setAddressResponseMode(mode: ResponseMode) {
        requestCounts["address"] = AtomicInteger(0)
        this.addressMode = mode
    }

    fun requestCount(endpoint: String): Int = requestCounts[endpoint]?.get() ?: 0

    private var keywordMode: ResponseMode = ResponseMode.SUCCESS
    private var addressMode: ResponseMode = ResponseMode.SUCCESS

    private fun handleKeywordSearch(exchange: HttpExchange) {
        val count = requestCounts.getOrPut("keyword") { AtomicInteger(0) }
        count.incrementAndGet()

        val query = exchange.requestURI.rawQuery.split("&")
            .find { it.startsWith("query=") }?.substring(6) ?: ""

        val successBody = """
        {
          "documents": [
            {
              "id": "123456",
              "place_name": "테스트 장소",
              "category_name": "음식점",
              "address_name": "서울 강남구 테스트동",
              "road_address_name": "서울 강남구 테스트로 123",
              "x": 127.0,
              "y": 37.0,
              "place_url": "https://place.map.kakao.com/123456",
              "distance": 100
            }
          ]
        }
        """.trimIndent()

        when (keywordMode) {
            ResponseMode.SUCCESS -> {
                sendResponse(exchange, 200, successBody)
            }
            ResponseMode.EMPTY -> {
                sendResponse(exchange, 200, """{"documents":[]}""")
            }
            ResponseMode.RATE_LIMIT -> {
                // 처음 2회는 429(Retry-After: 1s), 3번째 요청부터는 200으로 회복한다(V2-5).
                if (count.get() <= 2) {
                    exchange.responseHeaders.set("Retry-After", "1")
                    sendResponse(exchange, 429, """{"error":"rate_limit"}""")
                } else {
                    sendResponse(exchange, 200, successBody)
                }
            }
            ResponseMode.SERVER_ERROR -> {
                sendResponse(exchange, 500, """{"error":"server_error"}""")
            }
            ResponseMode.MALFORMED -> {
                sendResponse(exchange, 200, """{"invalid json""")
            }
        }
    }

    private fun handleAddressSearch(exchange: HttpExchange) {
        val count = requestCounts.getOrPut("address") { AtomicInteger(0) }
        count.incrementAndGet()

        when (addressMode) {
            ResponseMode.SUCCESS -> {
                val response = """
                {
                  "documents": [
                    {
                      "address_name": "서울 강남구 역삼동 858",
                      "road_address_name": "서울 강남구 강남대로 396",
                      "address_type": "ROAD_ADDR",
                      "x": 127.0276,
                      "y": 37.4979
                    }
                  ]
                }
                """.trimIndent()
                sendResponse(exchange, 200, response)
            }
            else -> sendResponse(exchange, 200, """{"documents":[]}""")
        }
    }

    private fun handleCoord2Address(exchange: HttpExchange) {
        val response = """
        {
          "documents": [
            {
              "road_address": {
                "address_name": "서울 강남구 강남대로 396"
              },
              "address": {
                "address_name": "서울 강남구 역삼동 858"
              }
            }
          ]
        }
        """.trimIndent()
        sendResponse(exchange, 200, response)
    }

    private fun sendResponse(exchange: HttpExchange, statusCode: Int, body: String) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(statusCode, body.toByteArray().size.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.responseBody.close()
    }

    enum class ResponseMode {
        SUCCESS, EMPTY, RATE_LIMIT, SERVER_ERROR, MALFORMED
    }
}
