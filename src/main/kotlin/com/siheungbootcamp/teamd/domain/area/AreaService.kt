package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.*

/**
 * 지역 탐색 서비스.
 *
 * POST 요청을 처리하고 작업을 생성한다.
 * 같은 보드의 활성 작업이 있으면 재사용한다.
 */
@Service
@Transactional
class AreaService(
    private val areaJobRepository: AreaSearchJobRepository,
    private val areaCandidateRepository: AreaCandidateRepository,
    private val boardRepository: BoardRepository,
    private val participantRepository: ParticipantRepository,
    private val mapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val allowedDurations = setOf(30, 45, 60)

    /**
     * 지역 탐색 작업을 생성하거나 활성 작업을 재사용한다.
     *
     * 검증 순서:
     * 1. 요청자가 해당 보드의 참여자인지 확인
     * 2. durationMin 유효성 검사
     * 3. 활성 참여자 2명 이상 확인
     * 4. 모든 대상의 출발지(originCiphertext) 존재 확인
     * 5. 같은 보드의 활성 작업이 있으면 재사용
     * 6. snapshot에 participantIds만 저장
     */
    fun createAreaSearchJob(boardId: String, participantId: Long, request: CreateAreaSearchJobRequest): CreateAreaSearchJobResponse {
        // 1. 보드 존재 및 참여자 권한 확인
        val board = boardRepository.findByPublicId(boardId) ?: run {
            logger.warn("area_search_board_not_found boardId=$boardId")
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }
        val boardIdLong = requireNotNull(board.id)

        val requester = participantRepository.findById(participantId).orElse(null) ?: run {
            logger.warn("area_search_requester_not_found participantId=$participantId")
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        if (requester.board.id != boardIdLong) {
            logger.warn("area_search_cross_board_access boardId=$boardIdLong participantId=$participantId")
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        // 2. durationMin 검증
        if (!allowedDurations.contains(request.durationMin)) {
            logger.warn("area_search_invalid_duration durationMin=${request.durationMin}")
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // 3. 활성 참여자 2명 이상 확인
        val activeParticipants = participantRepository.findAllByBoardIdAndActiveTrueOrderById(boardIdLong)
        if (activeParticipants.size < 2) {
            logger.warn("area_search_insufficient_participants boardId=$boardIdLong count=${activeParticipants.size}")
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // 4. 모든 참여자의 출발지 확인
        val withOrigin = activeParticipants.filter { it.originCiphertext != null }
        if (withOrigin.size != activeParticipants.size) {
            logger.warn("area_search_missing_origin boardId=$boardIdLong missing=${activeParticipants.size - withOrigin.size}")
            throw BusinessException(ErrorCode.ORIGIN_REQUIRED)
        }

        // 5. 같은 보드의 활성 작업 확인
        val existingJob = areaJobRepository.findActiveByBoardId(boardIdLong)
        if (existingJob != null) {
            logger.info("area_search_reusing_job jobId=${existingJob.publicId} boardId=$boardIdLong")
            return buildResponse(existingJob)
        }

        // 6. 새 작업 생성: snapshot에 participantIds만 저장
        val participantIds = withOrigin.map { it.id ?: error("participant must have id") }
        val snapshot = (mapper.createObjectNode() as ObjectNode).apply {
            set("participantIds", mapper.createArrayNode().apply {
                participantIds.forEach { add(it) }
            })
        }

        val jobId = "area_${UUID.randomUUID()}"
        val job = AreaSearchJob(
            publicId = jobId,
            boardId = boardIdLong,
            durationMin = request.durationMin,
            snapshotJson = mapper.writeValueAsString(snapshot),
        )
        areaJobRepository.save(job)
        logger.info("area_search_created jobId=$jobId boardId=$boardIdLong participantCount=${participantIds.size} durationMin=${request.durationMin}")

        return buildResponse(job)
    }

    private fun buildResponse(job: AreaSearchJob): CreateAreaSearchJobResponse {
        val snapshot = mapper.readTree(job.snapshotJson)
        val participantCount = snapshot.path("participantIds").size()
        return CreateAreaSearchJobResponse(
            jobId = job.publicId,
            status = job.status,
            estimatedExternalCalls = EstimatedExternalCalls(
                odsay = participantCount,  // 참여자당 1회
                kakaoLocal = AreaJobExecutor.MAX_KAKAO_CALLS,  // 상위 3개 조각 × 1개 키워드 = 최대 3회
                tmapTransit = 0,            // TMAP 호출 안 함
            ),
        )
    }

    /**
     * 작업 ID로 조회한다.
     */
    @Transactional(readOnly = true)
    fun getAreaSearchJob(boardId: String, participantId: Long, jobId: String): GetAreaSearchJobResponse {
        // 보드 조회 및 내부 ID 해결
        val board = boardRepository.findByPublicId(boardId) ?: run {
            logger.warn("area_search_board_not_found boardId=$boardId")
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }
        val boardIdLong = requireNotNull(board.id)

        val job = areaJobRepository.findByPublicId(jobId) ?: run {
            logger.warn("area_search_job_not_found jobId=$jobId")
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        if (job.boardId != boardIdLong) {
            logger.warn("area_search_cross_board_access boardId=$boardIdLong jobId=$jobId")
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        val requester = participantRepository.findById(participantId).orElse(null) ?: run {
            logger.warn("area_search_requester_not_found participantId=$participantId")
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        if (requester.board.id != boardIdLong) {
            logger.warn("area_search_requester_cross_board participantId=$participantId boardId=$boardIdLong")
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        val result = if (job.status == "SUCCEEDED") {
            val candidates = areaCandidateRepository.findByJobIdOrderByRankAsc(job.id ?: error("job must have id"))
            AreaSearchResult(
                candidates = candidates.map { candidate ->
                    AreaCandidateResponse(
                        candidateId = candidate.publicId,
                        name = candidate.name,
                        lon = candidate.lon,
                        lat = candidate.lat,
                        metrics = mapper.readTree(candidate.metricsJson),
                        reasons = mapper.readTree(candidate.reasonsJson),
                        rank = candidate.rank,
                    )
                },
            )
        } else {
            null
        }

        return GetAreaSearchJobResponse(
            jobId = job.publicId,
            status = job.status,
            durationMin = job.durationMin,
            result = result,
            errorCode = job.errorCode,
        )
    }
}
