package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.job.JobExecutor
import com.siheungbootcamp.teamd.infra.external.kakao.KakaoLocalClient
import com.siheungbootcamp.teamd.infra.external.odsay.OdsayIsochroneClient
import jakarta.annotation.PostConstruct
import org.locationtech.jts.geom.Geometry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * 지역 탐색 작업 Executor.
 *
 * 3단계 파이프라인:
 * 1. ISOCHRONE: ODsay에서 각 참여자별 도달권 조회 (N회)
 * 2. INTERSECTION: JTS로 교집합 계산 후 상위 3개 조각 (0회)
 * 3. AREA_ANCHOR_COLLECTION: Kakao Local에서 기준점 검색 (최대 9회)
 *
 * 흐름:
 * 1. stateWriter의 짧은 트랜잭션으로 다음 작업을 원자적으로 조회하고 RUNNING으로 표시
 * 2. 트랜잭션 밖에서 외부 API 호출 (ISOCHRONE, INTERSECTION, AREA_ANCHOR_COLLECTION)
 * 3. 결과에 맞는 markSucceeded/markFailed가 각각 짧은 쓰기 트랜잭션(stateWriter 호출)으로 저장
 *
 * 각 단계 끝에 진행 상황을 저장하고, 트랜잭션 밖에서 외부 호출을 수행한다.
 * TMAP은 호출하지 않음 (request count = 0).
 */
@Component
class AreaJobExecutor(
    private val jobRepository: AreaSearchJobRepository,
    private val candidateRepository: AreaSuggestionRepository,
    private val participantRepository: ParticipantRepository,
    private val geometryService: GeometryService,
    private val odsayClient: OdsayIsochroneClient,
    private val kakaoClient: KakaoLocalClient,
    private val originCipher: OriginCipher,
    private val mapper: ObjectMapper,
    private val stateWriter: AreaJobStateWriter,
) : JobExecutor {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxRetries = 3
    private val keywords = listOf("역", "맛집", "카페")
    private val staleJobThresholdMinutes = 30L

    companion object {
        /**
         * Kakao Local API 호출 최대 횟수.
         * executeAnchorPhase에서 각 조각당 1개 키워드만 검색하므로,
         * 상위 3개 조각 × 1 키워드 = 최대 3회 호출.
         * AreaService의 estimatedExternalCalls.kakaoLocal과 일치해야 함.
         */
        const val MAX_KAKAO_CALLS = 3
    }

    /**
     * 애플리케이션 시작 시 crashed RUNNING 작업들을 복구한다.
     */
    @PostConstruct
    fun recoverStaleJobs() {
        val staleBefore = Instant.now().minus(staleJobThresholdMinutes, ChronoUnit.MINUTES)
        stateWriter.recoverStaleRunningJobs(staleBefore)
    }

    override fun processOne(): Boolean {
        val job = stateWriter.claimNextJob() ?: return false
        logger.info("area_job_processing jobId=${job.publicId}")

        try {
            executeJob(job)
            logger.info("area_job_success jobId=${job.publicId}")
        } catch (e: BusinessException) {
            logger.warn("area_job_business_error jobId=${job.publicId} code=${e.errorCode.name}")
            stateWriter.markFailed(job, e.errorCode.name)
        } catch (e: Exception) {
            logger.error("area_job_error jobId=${job.publicId} error=${e.message}", e)
            stateWriter.markFailed(job, ErrorCode.EXTERNAL_UNAVAILABLE.name)
        }
        return true
    }

    private fun executeJob(job: AreaSearchJob) {
        // Phase 1: ISOCHRONE
        val isochroneGeometries = executeIsochronePhase(job)
        if (isochroneGeometries.isEmpty()) {
            stateWriter.markFailed(job, ErrorCode.EXTERNAL_UNAVAILABLE.name)
            return
        }

        // Phase 2: INTERSECTION (nullable - empty/null is allowed)
        val intersection = executeIntersectionPhase(job, isochroneGeometries)

        // Phase 3: AREA_ANCHOR_COLLECTION with participant center
        val computationResult = executeAnchorPhaseWithCenter(job, isochroneGeometries, intersection)

        // Save success even if intersection is null or anchors are empty
        stateWriter.markSucceededWithNewFormat(job, computationResult)
    }

    /**
     * Phase 1: ISOCHRONE - ODsay에서 각 참여자의 도달권 조회
     */
    private fun executeIsochronePhase(job: AreaSearchJob): List<Geometry> {
        val snapshot = mapper.readTree(job.snapshotJson)
        val participantIds = mutableListOf<Long>()
        for (node in snapshot.path("participantIds")) {
            participantIds.add(node.asLong())
        }
        val geometries = mutableListOf<Geometry>()

        for (pId in participantIds) {
            val participant = participantRepository.findById(pId).orElse(null) ?: run {
                logger.warn("area_isochrone_participant_not_found jobId=${job.publicId} participantId=$pId")
                throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
            }

            val originCiphertext = participant.originCiphertext ?: run {
                logger.warn("area_isochrone_no_origin jobId=${job.publicId} participantId=$pId")
                throw BusinessException(ErrorCode.ORIGIN_REQUIRED)
            }

            // 출발지 복호화 (메모리에서만 사용, 저장하지 않음)
            val (lon, lat) = try {
                val decrypted = originCipher.decrypt(originCiphertext)
                val buffer = ByteBuffer.wrap(decrypted)
                Pair(buffer.double, buffer.double)
            } catch (e: Exception) {
                logger.warn("area_isochrone_decrypt_error jobId=${job.publicId} participantId=$pId")
                throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
            }

            // ODsay 호출 (재시도 포함)
            for (attempt in 1..maxRetries) {
                try {
                    val geometry = odsayClient.fetch(lon, lat, job.durationMin)
                    geometries.add(geometry)
                    logger.info("area_isochrone_success jobId=${job.publicId} participantId=$pId")
                    break
                } catch (e: Exception) {
                    logger.warn("area_isochrone_retry jobId=${job.publicId} participantId=$pId attempt=$attempt maxRetries=$maxRetries error=${e.message}")
                    if (attempt == maxRetries) {
                        throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
                    }
                    Thread.sleep(1000L * attempt) // 1s, 2s 대기
                }
            }
        }

        stateWriter.updateProgress(job, "ISOCHRONE", "fetched ${geometries.size} isochrones")
        return geometries
    }

    /**
     * Phase 2: INTERSECTION - JTS로 교집합 계산 (참고값, nullable)
     *
     * Task 5에서는 교집합이 없어도 성공으로 처리한다.
     * 교집합 결과는 참고 정보일 뿐이며, 기준점 검색은 참여자 중심점을 기준으로 한다.
     */
    private fun executeIntersectionPhase(job: AreaSearchJob, geometries: List<Geometry>): Geometry? {
        if (geometries.isEmpty()) return null
        if (geometries.size == 1) return geometries[0]

        // 모든 도형의 교집합 계산
        val normalized = geometries.map { it.buffer(0.0) }
        val intersection = normalized.reduce { acc, geom -> acc.intersection(geom) }

        if (intersection.isEmpty) {
            logger.info("area_intersection_empty jobId=${job.publicId}")
            stateWriter.updateProgress(job, "INTERSECTION", "no common area")
            return null
        }

        logger.info("area_intersection_success jobId=${job.publicId}")
        stateWriter.updateProgress(job, "INTERSECTION", "found common area")
        return intersection
    }

    /**
     * Phase 3: AREA_ANCHOR_COLLECTION - 각 조각 주변에서 Kakao Local 검색
     *
     * 최대 3개의 후보를 반환한다. 중복 제거 후 기준점 면적(큰순) 및 이름으로 정렬하여 결정적인 순서를 보장한다.
     * rank는 1부터 시작한다.
     *
     * Kakao Local 호출 횟수: 각 조각(최대 3개)당 1개 키워드만 검색 → 최대 3회 (MAX_KAKAO_CALLS 상수와 일치)
     */
    private fun executeAnchorPhase(job: AreaSearchJob, pieces: List<Geometry>): List<AreaSuggestion> {
        val jobId = job.id ?: error("job must have id")
        val candidates = mutableListOf<AreaSuggestion>()
        val seenPlaceIds = mutableSetOf<String>()
        val maxCandidates = 3

        for ((pieceIndex, piece) in pieces.withIndex()) {
            val centroid = piece.centroid
            val centerLon = centroid.coordinate.x
            val centerLat = centroid.coordinate.y

            // 각 조각당 1개 키워드만 검색 (공통 도달 영역 내 기준점 찾기)
            // 첫 번째 키워드("역")를 사용하여 주요 교통 허브 탐색
            val keyword = keywords[0]
            try {
                val searchResults = kakaoClient.searchKeyword(keyword, centerLon, centerLat, radius = 2000)

                for (result in searchResults) {
                    // 중복 제거
                    if (seenPlaceIds.contains(result.providerPlaceId)) continue

                    // 조각 내부 필터링
                    if (!geometryService.contains(piece, result.lon, result.lat)) continue

                    // 후보 추가
                    seenPlaceIds.add(result.providerPlaceId)
                    val intersectionAreaKm2 = geometryService.intersectionAreaKm2(piece)
                    val metrics = (mapper.createObjectNode() as ObjectNode).apply {
                        put("pieceIndex", pieceIndex)
                        put("intersectionAreaKm2", intersectionAreaKm2)
                    }
                    val reasons = mapper.createArrayNode().add("공통 도달 영역 안의 탐색 기준점")

                    val candidate = AreaSuggestion(
                        publicId = "candidate_${UUID.randomUUID()}",
                        jobId = jobId,
                        name = result.name,
                        lon = result.lon,
                        lat = result.lat,
                        providerPlaceId = result.providerPlaceId,
                        metricsJson = mapper.writeValueAsString(metrics),
                        reasonsJson = mapper.writeValueAsString(reasons),
                        rank = 0, // 임시값, 정렬 후 재할당
                    )
                    candidates.add(candidate)
                }
            } catch (e: Exception) {
                logger.warn("area_anchor_search_error jobId=${job.publicId} keyword=$keyword pieceIndex=$pieceIndex error=${e.message}")
                throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
            }
        }

        // 결정적인 정렬: 기준점 면적(큰순), 그다음 이름 사전순, providerPlaceId로 완전한 결정성 보장
        val sortedCandidates = candidates
            .sortedWith(compareBy<AreaSuggestion> { candidate ->
                val metrics = mapper.readTree(candidate.metricsJson)
                -metrics.path("intersectionAreaKm2").asDouble() // 음수로 내림차순
            }.thenBy { it.name }
            .thenBy { it.providerPlaceId })
            .take(maxCandidates) // 최대 3개만 유지
            .mapIndexed { index, candidate ->
                // rank를 1부터 시작하도록 새로운 객체로 생성
                AreaSuggestion(
                    publicId = candidate.publicId,
                    jobId = candidate.jobId,
                    name = candidate.name,
                    lon = candidate.lon,
                    lat = candidate.lat,
                    providerPlaceId = candidate.providerPlaceId,
                    metricsJson = candidate.metricsJson,
                    reasonsJson = candidate.reasonsJson,
                    rank = index + 1,
                )
            }

        logger.info("area_anchor_success jobId=${job.publicId} candidateCount=${sortedCandidates.size}")
        stateWriter.updateProgress(job, "AREA_ANCHOR_COLLECTION", "found ${sortedCandidates.size} candidates")
        return sortedCandidates
    }

    /**
     * Phase 3 (New): AREA_ANCHOR_COLLECTION with participant center
     *
     * 참여자들의 출발지 중심점을 계산하고, 그 주변에서 교통 기준점을 검색한다.
     *
     * 흐름:
     * 1. 참여자 출발지 복호화 및 중심점 계산 (산술 평균)
     * 2. 중심점 반경 20km 내에서 3개 키워드 검색 (지하철역, 기차역, 시외버스터미널)
     * 3. 중심점까지의 거리 순서로 정렬 (최대 3개)
     * 4. 공통 영역은 참고값이며 필터링에 사용하지 않음
     */
    private fun executeAnchorPhaseWithCenter(
        job: AreaSearchJob,
        isochroneGeometries: List<Geometry>,
        commonArea: Geometry?,
    ): AreaComputationResult {
        val jobId = job.id ?: error("job must have id")
        val snapshot = mapper.readTree(job.snapshotJson)
        val participantIds = mutableListOf<Long>()
        for (node in snapshot.path("participantIds")) {
            participantIds.add(node.asLong())
        }

        // 1. 출발지 복호화 및 중심점 계산
        val origins = mutableListOf<Pair<Double, Double>>()
        for (pId in participantIds) {
            val participant = participantRepository.findById(pId).orElse(null) ?: continue
            val originCiphertext = participant.originCiphertext ?: continue
            try {
                val decrypted = originCipher.decrypt(originCiphertext)
                val buffer = java.nio.ByteBuffer.wrap(decrypted)
                val lon = buffer.double
                val lat = buffer.double
                origins.add(Pair(lon, lat))
            } catch (e: Exception) {
                logger.warn("area_anchor_decrypt_error jobId=${jobId} participantId=$pId")
            }
        }

        if (origins.isEmpty()) {
            logger.warn("area_anchor_no_origins jobId=${jobId}")
            return AreaComputationResult(
                isochrones = buildAnonymousIsochrones(isochroneGeometries),
                commonArea = commonArea?.let { mapper.readTree(it.toString()) },
                participantCenter = null,
                anchors = emptyList()
            )
        }

        val centerLon = origins.map { it.first }.average()
        val centerLat = origins.map { it.second }.average()
        val center = ParticipantCenterDto(centerLon, centerLat)

        // 2. 교통 기준점 검색 (3개 키워드, 반경 20km)
        val anchorQueries = listOf("지하철역", "기차역", "시외버스터미널")
        val anchors = mutableListOf<AreaAnchorDto>()
        val seenPlaceIds = mutableSetOf<String>()

        for (query in anchorQueries) {
            try {
                val results = kakaoClient.searchKeyword(query, centerLon, centerLat, radius = 20_000, size = 15)
                for (result in results) {
                    if (seenPlaceIds.contains(result.providerPlaceId)) continue
                    seenPlaceIds.add(result.providerPlaceId)

                    val distanceMeters = GeometryService.haversineDistanceMeters(centerLon, centerLat, result.lon, result.lat)
                    anchors.add(
                        AreaAnchorDto(
                            anchorId = "anchor_${result.providerPlaceId}",
                            name = result.name,
                            lon = result.lon,
                            lat = result.lat,
                            centerDistanceMeters = distanceMeters,
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("area_anchor_search_error jobId=${jobId} query=$query error=${e.message}")
            }
        }

        // 3. 거리 순으로 정렬하고 최대 3개 유지
        val sortedAnchors = anchors
            .sortedWith(compareBy<AreaAnchorDto> { it.centerDistanceMeters }
                .thenBy { it.name }
                .thenBy { it.anchorId })
            .take(3)

        stateWriter.updateProgress(job, "AREA_ANCHOR_COLLECTION", "found ${sortedAnchors.size} anchors")

        return AreaComputationResult(
            isochrones = buildAnonymousIsochrones(isochroneGeometries),
            commonArea = commonArea?.let { mapper.readTree(it.toString()) },
            participantCenter = center,
            anchors = sortedAnchors,
        )
    }

    private fun buildAnonymousIsochrones(geometries: List<Geometry>): List<AnonymousIsochroneDto> {
        return geometries.mapIndexed { index, geometry ->
            AnonymousIsochroneDto(
                areaId = "area_${index + 1}",
                geometry = mapper.readTree(geometry.toString()),
            )
        }
    }
}
