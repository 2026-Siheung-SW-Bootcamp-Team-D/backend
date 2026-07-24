package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.persistence.JsonValue
import jakarta.persistence.*
import java.time.Instant

/**
 * P7 Task 4: 지역 탐색 결과의 canonical 기준점.
 *
 * area_candidate에서 area_suggestion으로 이름을 바꾼 엔티티로,
 * Kakao Local 검색 결과를 저장한다.
 * provider는 공급자(기본 KAKAO), center_distance_m은 참여자 대표 중심으로부터의 거리를 저장한다.
 */
@Entity
@Table(name = "area_suggestion")
class AreaSuggestion(
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
    @Column(name = "provider", nullable = false)
    val provider: String = "KAKAO",
    @Column(name = "center_distance_m", nullable = false)
    val centerDistanceMeters: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
}
