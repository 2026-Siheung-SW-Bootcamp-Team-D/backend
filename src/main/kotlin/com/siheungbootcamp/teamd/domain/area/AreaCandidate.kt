package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.persistence.JsonValue
import jakarta.persistence.*
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * 지역 탐색 결과의 후보 장소.
 *
 * 각 후보는 Kakao Local 검색 결과를 저장하며,
 * metrics에는 교집합 조각 정보를, reasons에는 검색 기준점 설명을 담는다.
 */
@Entity
@Table(name = "area_candidate")
class AreaCandidate(
    @Column(name = "public_id", nullable = false, unique = true)
    val publicId: String,
    @Column(name = "job_id", nullable = false)
    val jobId: Long,
    @Column(name = "name", nullable = false)
    val name: String,
    @Column(name = "lon", nullable = false)
    val lon: Double,
    @Column(name = "lat", nullable = false)
    val lat: Double,
    @Column(name = "provider_place_id")
    val providerPlaceId: String?,
    @JsonValue
    @Column(name = "metrics", nullable = false, columnDefinition = "jsonb")
    val metricsJson: String,
    @JsonValue
    @Column(name = "reasons", nullable = false, columnDefinition = "jsonb")
    val reasonsJson: String,
    @Column(name = "rank", nullable = false)
    val rank: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
}
