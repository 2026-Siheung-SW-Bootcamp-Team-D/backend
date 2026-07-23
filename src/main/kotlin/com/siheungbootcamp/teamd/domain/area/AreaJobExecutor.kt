package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.job.JobExecutor
import com.siheungbootcamp.teamd.infra.external.kakao.KakaoLocalClient
import com.siheungbootcamp.teamd.infra.external.odsay.OdsayIsochroneClient
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
import java.util.*

/**
 * 지역 탐색 작업 Executor.
 *
 * 3단계 파이프라인:
 * 1. ISOCHRONE: ODsay에서 각 참여자별 도달권 조회 (N회)
 * 2. INTERSECTION: JTS로 교집합 계산 후 상위 3개 조각 (0회)
 * 3. AREA_ANCHOR_COLLECTION: Kakao Local에서 기준점 검색 (최대 9회)
 *
 * 각 단계 끝에 진행 상황을 저장하고, 트랜잭션 밖에서 외부 호출을 수행한다.
 * TMAP은 호출하지 않음 (request count = 0).
 */
@Component
class AreaJobExecutor(
    private val jobRepository: AreaSearchJobRepository,
    private val candidateRepository: AreaCandidateRepository,
    private val participantRepository: ParticipantRepository,
    private val geometryService: GeometryService,
    private val odsayClient: OdsayIsochroneClient,
    private val kakaoClient: KakaoLocalClient,
    private val originCipher: OriginCipher,
    private val mapper: ObjectMapper,
) : JobExecutor {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxRetries = 3
    private val keywords = listOf("역", "맛집", "카페")

    companion object {
        /**
         * Kakao Local API 호출 최대 횟수.
         * executeAnchorPhase에서 각 조각당 1개 키워드만 검색하므로,
         * 상위 3개 조각 × 1 키워드 = 최대 3회 호출.
         * AreaService의 estimatedExternalCalls.kakaoLocal과 일치해야 함.
         */
        const val MAX_KAKAO_CALLS = 3
    }

    override fun processOne(): Boolean {
        val job = loadNextJob() ?: return false
        logger.info("area_job_processing jobId=${job.publicId}")

        try {
            executeJob(job)
            logger.info("area_job_success jobId=${job.publicId}")
        } catch (e: Exception) {
            logger.error("area_job_error jobId=${job.publicId} error=${e.message}", e)
            markFailed(job, ErrorCode.EXTERNAL_UNAVAILABLE.name)
        }
        return true
    }

    @Transactional(readOnly = true)
    private fun loadNextJob(): AreaSearchJob? {
        return jobRepository.findNextPending()
    }

    private fun executeJob(job: AreaSearchJob) {
        markRunning(job)

        try {
            // Phase 1: ISOCHRONE
            val isochroneGeometries = executeIsochronePhase(job)
            if (isochroneGeometries.isEmpty()) {
                markFailed(job, ErrorCode.EXTERNAL_UNAVAILABLE.name)
                return
            }

            // Phase 2: INTERSECTION
            val intersectionPieces = executeIntersectionPhase(job, isochroneGeometries)
            if (intersectionPieces.isEmpty()) {
                markFailed(job, ErrorCode.NO_INTERSECTION.name)
                return
            }

            // Phase 3: AREA_ANCHOR_COLLECTION
            val candidates = executeAnchorPhase(job, intersectionPieces)
            if (candidates.isEmpty()) {
                markFailed(job, ErrorCode.NO_AREA_ANCHOR.name)
                return
            }

            markSucceeded(job, candidates)
        } catch (e: BusinessException) {
            logger.warn("area_job_business_error jobId=${job.publicId} code=${e.errorCode.name}")
            markFailed(job, ErrorCode.EXTERNAL_UNAVAILABLE.name)
        }
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
                throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
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

        updateProgress(job, "ISOCHRONE", "fetched ${geometries.size} isochrones")
        return geometries
    }

    /**
     * Phase 2: INTERSECTION - JTS로 교집합 계산 후 상위 3개 조각
     */
    private fun executeIntersectionPhase(job: AreaSearchJob, geometries: List<Geometry>): List<Geometry> {
        val pieces = geometryService.intersectLargest(geometries, limit = 3)
        logger.info("area_intersection_success jobId=${job.publicId} pieceCount=${pieces.size}")
        updateProgress(job, "INTERSECTION", "found ${pieces.size} pieces")
        return pieces
    }

    /**
     * Phase 3: AREA_ANCHOR_COLLECTION - 각 조각 주변에서 Kakao Local 검색
     *
     * 최대 3개의 후보를 반환한다. 중복 제거 후 기준점 면적(큰순) 및 이름으로 정렬하여 결정적인 순서를 보장한다.
     * rank는 1부터 시작한다.
     *
     * Kakao Local 호출 횟수: 각 조각(최대 3개)당 1개 키워드만 검색 → 최대 3회 (MAX_KAKAO_CALLS 상수와 일치)
     */
    private fun executeAnchorPhase(job: AreaSearchJob, pieces: List<Geometry>): List<AreaCandidate> {
        val jobId = job.id ?: error("job must have id")
        val candidates = mutableListOf<AreaCandidate>()
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

                    val candidate = AreaCandidate(
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
            .sortedWith(compareBy<AreaCandidate> { candidate ->
                val metrics = mapper.readTree(candidate.metricsJson)
                -metrics.path("intersectionAreaKm2").asDouble() // 음수로 내림차순
            }.thenBy { it.name }
            .thenBy { it.providerPlaceId })
            .take(maxCandidates) // 최대 3개만 유지
            .mapIndexed { index, candidate ->
                // rank를 1부터 시작하도록 새로운 객체로 생성
                AreaCandidate(
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
        updateProgress(job, "AREA_ANCHOR_COLLECTION", "found ${sortedCandidates.size} candidates")
        return sortedCandidates
    }

    @Transactional
    private fun markRunning(job: AreaSearchJob) {
        job.markRunning()
        jobRepository.save(job)
    }

    @Transactional
    private fun updateProgress(job: AreaSearchJob, phase: String, details: String) {
        val progress = (mapper.createObjectNode() as ObjectNode).apply {
            put(phase, details)
            put("updatedAt", Instant.now().toString())
        }
        job.updateProgress(phase, progress)
        jobRepository.save(job)
    }

    @Transactional
    private fun markSucceeded(job: AreaSearchJob, candidates: List<AreaCandidate>) {
        // 후보 저장
        candidates.forEach { candidateRepository.save(it) }

        // 결과 JSON 구성
        val result = (mapper.createObjectNode() as ObjectNode).apply {
            val candidatesArray = (mapper.createArrayNode() as ArrayNode)
            candidates.forEach { candidate ->
                candidatesArray.add(
                    (mapper.createObjectNode() as ObjectNode).apply {
                        put("candidateId", candidate.publicId)
                        put("name", candidate.name)
                        put("lon", candidate.lon)
                        put("lat", candidate.lat)
                        set("metrics", mapper.readTree(candidate.metricsJson))
                        set("reasons", mapper.readTree(candidate.reasonsJson))
                        put("rank", candidate.rank)
                    }
                )
            }
            set("candidates", candidatesArray)
        }

        job.markSucceeded(result)
        jobRepository.save(job)
    }

    @Transactional
    private fun markFailed(job: AreaSearchJob, errorCode: String) {
        job.markFailed(errorCode)
        jobRepository.save(job)
    }
}
