package com.siheungbootcamp.teamd.infra.external.kakao

import tools.jackson.databind.ObjectMapper
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.external.ExternalApiClient
import org.springframework.stereotype.Component

/**
 * Kakao Local REST API의 검색, 주소, 좌표→주소 기능을 제공한다.
 *
 * - searchKeyword: 키워드 검색 (최대 5개 결과)
 * - searchAddress: 주소 검색
 * - coord2Address: 좌표→주소 변환
 *
 * providerPlaceUrl은 허용 도메인(place.map.kakao.com)만 저장하고,
 * 다른 도메인은 저장하지 않는다(null).
 */
@Component
class KakaoLocalClient(
    private val properties: KakaoLocalProperties,
    private val externalApiClient: ExternalApiClient,
    private val mapper: ObjectMapper,
) {
    private val allowedUrlHosts = setOf("place.map.kakao.com")

    data class PlaceCandidate(
        val providerPlaceId: String,
        val name: String,
        val category: String,
        val internalCategory: String,
        val addressName: String,
        val roadAddressName: String,
        val lon: Double,
        val lat: Double,
        val providerPlaceUrl: String?,
        val distanceMeters: Int?,
    )

    data class AddressCandidate(
        val addressName: String,
        val roadAddressName: String?,
        val addressType: String,
        val lon: Double,
        val lat: Double,
    )

    data class CoordinateAddress(
        val roadAddressName: String?,
        val addressName: String?,
    )

    fun searchKeyword(query: String, lon: Double? = null, lat: Double? = null, radius: Int? = null): List<PlaceCandidate> {
        val params = mutableListOf("query=${urlEncode(query)}", "size=5")
        if (lon != null && lat != null) {
            params.add("x=$lon")
            params.add("y=$lat")
        }
        if (radius != null && lon != null && lat != null) {
            params.add("radius=$radius")
        }

        val url = "${properties.baseUrl}/v2/local/search/keyword.json?${params.joinToString("&")}"
        val response = externalApiClient.get(url, authHeaders())

        return try {
            val root = mapper.readTree(response)
            root.path("documents").iterator().asSequence().map { doc ->
                PlaceCandidate(
                    providerPlaceId = doc.path("id").asText(),
                    name = doc.path("place_name").asText(),
                    category = doc.path("category_name").asText(),
                    internalCategory = KakaoCategoryMapper.map(doc.path("category_name").asText()),
                    addressName = doc.path("address_name").asText(),
                    roadAddressName = doc.path("road_address_name").asText(),
                    lon = doc.path("x").asDouble(),
                    lat = doc.path("y").asDouble(),
                    providerPlaceUrl = validateUrl(doc.path("place_url").asText()),
                    distanceMeters = if (doc.has("distance")) doc.path("distance").asInt() else null,
                )
            }.take(5).toList()
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }

    fun searchAddress(query: String): List<AddressCandidate> {
        val url = "${properties.baseUrl}/v2/local/search/address.json?query=${urlEncode(query)}&size=5"
        val response = externalApiClient.get(url, authHeaders())

        return try {
            val root = mapper.readTree(response)
            root.path("documents").iterator().asSequence().map { doc ->
                AddressCandidate(
                    addressName = doc.path("address_name").asText(),
                    roadAddressName = doc.path("road_address_name").asText(),
                    addressType = doc.path("address_type").asText(),
                    lon = doc.path("x").asDouble(),
                    lat = doc.path("y").asDouble(),
                )
            }.take(5).toList()
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }

    fun coord2Address(lon: Double, lat: Double): CoordinateAddress {
        val url = "${properties.baseUrl}/v2/local/geo/coord2address.json?x=$lon&y=$lat"
        val response = externalApiClient.get(url, authHeaders())

        return try {
            val root = mapper.readTree(response)
            val documents = root.path("documents")
            if (documents.isEmpty) {
                return CoordinateAddress(null, null)
            }
            val doc = documents[0]
            CoordinateAddress(
                roadAddressName = if (doc.has("road_address") && !doc.path("road_address").isNull) {
                    doc.path("road_address").path("address_name").asText()
                } else null,
                addressName = if (doc.has("address") && !doc.path("address").isNull) {
                    doc.path("address").path("address_name").asText()
                } else null,
            )
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }

    private fun authHeaders(): Map<String, String> {
        return mapOf("Authorization" to "KakaoAK ${properties.restKey}")
    }

    private fun validateUrl(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val host = java.net.URL(url).host
            if (allowedUrlHosts.contains(host)) url else null
        } catch (e: Exception) {
            null
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}
