package com.siheungbootcamp.teamd.domain.course

import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.BoardStatus
import com.siheungbootcamp.teamd.domain.board.DepartureStaleNotifier
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.place.Place
import com.siheungbootcamp.teamd.domain.place.PlaceRepository
import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId

/**
 * 코스 초안·확정 코스·공개 일정의 비즈니스 로직을 담당한다(P4).
 *
 * 초안 저장은 If-Match 낙관적 잠금으로 동시 편집 충돌을 막고, 확정은 초안을 다시 검증한 뒤
 * 스냅샷 없이 Place FK만 참조하는 새 코스 버전을 만든다. 공개 일정은 인증 없이 현재 확정
 * 버전만 노출하며 참여자·출발지·토큰·댓글·투표·내부 boardId를 절대 포함하지 않는다.
 */
@Service
@Transactional(readOnly = true)
class CourseService(
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val places: PlaceRepository,
    private val drafts: CourseDraftRepository,
    private val courses: CourseRepository,
    private val courseStops: CourseStopRepository,
    private val checks: AuthorizationChecks,
    private val staleNotifier: DepartureStaleNotifier,
    private val publicTokenGenerator: PublicTokenGenerator,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getDraft(boardId: String, principal: ParticipantPrincipal): CourseDraftResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoard(boardId)
        val boardIdInternal = requireNotNull(board.id)
        val draft = drafts.findByBoardId(boardIdInternal)
        val entries = draft?.let { deserializeStops(it.stopsJson) } ?: emptyList()
        return buildDraftResponse(boardIdInternal, draft?.version ?: 0, entries)
    }

    @Transactional
    fun putDraft(boardId: String, principal: ParticipantPrincipal, ifMatch: String?, request: PutCourseDraftRequest): CourseDraftResponse {
        checks.requireBoard(principal, boardId)
        checks.requireHost(principal)
        val board = findBoard(boardId)
        val boardIdInternal = requireNotNull(board.id)

        if (ifMatch.isNullOrBlank()) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        val existing = drafts.findByBoardIdForUpdate(boardIdInternal)
        val currentVersion = existing?.version ?: 0
        val expectedTag = etag(currentVersion)
        if (normalizeETag(ifMatch) != expectedTag) {
            throw BusinessException(ErrorCode.VERSION_MISMATCH, mapOf("currentETag" to expectedTag))
        }

        val inputs = request.stops.map { CourseStopInput(it.placeId, it.orderIndex, it.role, it.scheduledAt) }
        val validated = validateStops(boardIdInternal, inputs)
        val newEntries = validated.map { (item, _) -> DraftStopEntry(item.placeId, item.orderIndex, item.role, item.scheduledAt.toString()) }
        val newEntriesJson = serializeStops(newEntries)

        val saved = if (existing != null) {
            existing.replace(newEntriesJson)
            existing
        } else {
            try {
                val created = drafts.save(CourseDraft(board = board, version = 1, stopsJson = newEntriesJson))
                drafts.flush()
                created
            } catch (e: DataIntegrityViolationException) {
                // 초안이 아직 없을 때는 잠글 행이 없어 두 요청이 동시에 첫 insert를 시도할 수 있다.
                // course_draft.board_id unique 제약(V1__baseline.sql)에 걸린 쪽은 이미 다른 요청이
                // draft-1을 만들었다는 뜻이므로 412로 변환한다. 원본 예외는 진단용으로 로그만 남긴다.
                logger.debug("동시 초안 첫 insert 경합 감지: boardId={}", boardIdInternal, e)
                val current = drafts.findByBoardId(boardIdInternal)
                val latestTag = etag(current?.version ?: 0)
                throw BusinessException(ErrorCode.VERSION_MISMATCH, mapOf("currentETag" to latestTag))
            }
        }
        drafts.flush()

        return CourseDraftResponse(
            version = saved.version,
            stops = newEntries.map { DraftStopResponse(it.placeId, it.orderIndex, it.role, Instant.parse(it.scheduledAt)) },
            legs = LegEstimator.estimate(validated.map { (item, place) -> LegEstimator.StopPoint(item.orderIndex, place.lon, place.lat) }),
        )
    }

    @Transactional
    fun confirm(boardId: String, principal: ParticipantPrincipal, request: ConfirmCourseRequest, frontendBaseUrl: String): ConfirmCourseResponse {
        checks.requireBoard(principal, boardId)
        checks.requireHost(principal)
        val board = findBoardForUpdate(boardId)
        val boardIdInternal = requireNotNull(board.id)

        val draft = drafts.findByBoardIdForUpdate(boardIdInternal)
        val currentVersion = draft?.version ?: 0
        if (request.draftVersion != currentVersion) throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
        val draftEntries = draft?.let { deserializeStops(it.stopsJson) } ?: emptyList()
        if (draft == null || draftEntries.isEmpty()) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        // 초안 저장 이후 그 사이 장소가 삭제됐을 수 있으므로 확정 시점에 다시 검증한다.
        val inputs = draftEntries.map { CourseStopInput(it.placeId, it.orderIndex, it.role, Instant.parse(it.scheduledAt)) }
        val validated = validateStops(boardIdInternal, inputs)

        val maxVersion = courses.findTopByBoardIdOrderByVersionDesc(boardIdInternal)?.version ?: 0
        val isFirstConfirm = maxVersion == 0
        val newVersion = maxVersion + 1
        val confirmedAt = Instant.now()

        val course = courses.save(Course(board = board, version = newVersion, confirmedAt = confirmedAt))
        for ((item, place) in validated) {
            courseStops.save(CourseStop(course = course, place = place, orderIndex = item.orderIndex, role = item.role, scheduledAt = item.scheduledAt))
        }

        board.confirm()
        if (isFirstConfirm) {
            board.publicToken = uniquePublicToken()
        }

        // P5 전에는 departure_calculation 대상 행이 없어 no-op이다.
        participants.findAllByBoardIdAndActiveTrueOrderById(boardIdInternal).forEach { p ->
            staleNotifier.markStale(requireNotNull(p.id))
        }
        boards.flush()

        return ConfirmCourseResponse(
            courseId = course.publicId,
            version = newVersion,
            confirmedAt = confirmedAt,
            publicUrl = "${frontendBaseUrl.trimEnd('/')}/s/${requireNotNull(board.publicToken)}",
        )
    }

    fun getCurrent(boardId: String, principal: ParticipantPrincipal): CourseResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoard(boardId)
        val course = courses.findTopByBoardIdOrderByVersionDesc(requireNotNull(board.id))
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        return toCourseResponse(course)
    }

    fun getVersion(boardId: String, courseId: String, principal: ParticipantPrincipal): CourseResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoard(boardId)
        val course = courses.findByBoardIdAndPublicId(requireNotNull(board.id), courseId)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        return toCourseResponse(course)
    }

    /** 인증 없이 호출된다. 참여자·출발지·토큰·댓글·투표·내부 boardId를 응답에 절대 포함하지 않는다. */
    fun publicSchedule(publicToken: String): PublicScheduleResponse {
        val board = boards.findByPublicToken(publicToken) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        if (board.status == BoardStatus.CLOSED) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val course = courses.findTopByBoardIdOrderByVersionDesc(requireNotNull(board.id))
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val stopsForCourse = courseStops.findByCourseIdOrderByOrderIndex(requireNotNull(course.id))

        val latestPlaceUpdate = stopsForCourse.maxOfOrNull { it.place.updatedAt } ?: course.updatedAt
        val updatedAt = if (course.updatedAt.isAfter(latestPlaceUpdate)) course.updatedAt else latestPlaceUpdate
        val firstMeeting = stopsForCourse.firstOrNull { it.role == CourseStopRole.FIRST_MEETING.name } ?: stopsForCourse.first()

        return PublicScheduleResponse(
            boardName = board.name,
            date = firstMeeting.scheduledAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
            courseVersion = course.version,
            updatedAt = updatedAt,
            stops = stopsForCourse.map { cs ->
                PublicScheduleStopResponse(cs.orderIndex, cs.role, cs.place.name, cs.place.roadAddressName, cs.place.lon, cs.place.lat, cs.scheduledAt)
            },
            legs = LegEstimator.estimate(stopsForCourse.map { LegEstimator.StopPoint(it.orderIndex, it.place.lon, it.place.lat) }),
        )
    }

    private fun toCourseResponse(course: Course): CourseResponse {
        val stopsForCourse = courseStops.findByCourseIdOrderByOrderIndex(requireNotNull(course.id))
        return CourseResponse(
            courseId = course.publicId,
            version = course.version,
            confirmedAt = course.confirmedAt,
            stops = stopsForCourse.map { cs ->
                CourseStopResponse(cs.orderIndex, cs.role, cs.scheduledAt, cs.place.publicId, cs.place.name, cs.place.roadAddressName, cs.place.lon, cs.place.lat)
            },
            legs = LegEstimator.estimate(stopsForCourse.map { LegEstimator.StopPoint(it.orderIndex, it.place.lon, it.place.lat) }),
        )
    }

    private fun buildDraftResponse(boardIdInternal: Long, version: Int, entries: List<DraftStopEntry>): CourseDraftResponse {
        if (entries.isEmpty()) return CourseDraftResponse(version, emptyList(), emptyList())
        val ordered = entries.sortedBy { it.orderIndex }
        // 삭제 필터 없이 조회한다: 초안은 확정 전까지 usage checker의 보호를 받지 않으므로
        // 저장된 뒤 장소가 삭제될 수 있다. 그 스톱을 숨기지 않고 placeDeleted로만 표시한다.
        val placeById = ordered.associate { entry ->
            entry.placeId to (places.findByPublicIdAndBoardId(entry.placeId, boardIdInternal) ?: throw BusinessException(ErrorCode.INTERNAL_ERROR))
        }
        return CourseDraftResponse(
            version = version,
            stops = ordered.map { entry ->
                val place = placeById.getValue(entry.placeId)
                DraftStopResponse(entry.placeId, entry.orderIndex, entry.role, Instant.parse(entry.scheduledAt), placeDeleted = place.deletedAt != null)
            },
            legs = LegEstimator.estimate(ordered.map { entry -> val p = placeById.getValue(entry.placeId); LegEstimator.StopPoint(entry.orderIndex, p.lon, p.lat) }),
        )
    }

    /** 장소 1~10개, orderIndex 1부터 연속·중복 없음, 1번만 FIRST_MEETING, 시각 역전 없음, 활성 장소만 허용한다. */
    private fun validateStops(boardIdInternal: Long, items: List<CourseStopInput>): List<Pair<CourseStopInput, Place>> {
        if (items.isEmpty() || items.size > 10) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        if (items.map { it.orderIndex }.sorted() != (1..items.size).toList()) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        if (items.any { it.role !in VALID_ROLES }) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        val ordered = items.sortedBy { it.orderIndex }
        if (ordered.first().role != CourseStopRole.FIRST_MEETING.name) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        if (ordered.drop(1).any { it.role == CourseStopRole.FIRST_MEETING.name }) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        for (i in 1 until ordered.size) {
            if (!ordered[i].scheduledAt.isAfter(ordered[i - 1].scheduledAt)) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        return ordered.map { item ->
            val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(item.placeId, boardIdInternal)
                ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)
            item to place
        }
    }

    /** Hibernate 내장 JSON 매퍼가 Kotlin data class를 만들지 못하므로, jsonb 컬럼은 문자열로만
     * 다루고 앱 전역 ObjectMapper(Kotlin 모듈 등록됨)로 여기서 직접 (역)직렬화한다. */
    private fun serializeStops(entries: List<DraftStopEntry>): String = objectMapper.writeValueAsString(entries.toTypedArray())
    private fun deserializeStops(json: String): List<DraftStopEntry> = objectMapper.readValue(json, Array<DraftStopEntry>::class.java).toList()

    private fun uniquePublicToken(): String =
        generateSequence(publicTokenGenerator::generate).first { boards.findByPublicToken(it) == null }

    private fun findBoard(id: String) = boards.findByPublicId(id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    private fun findBoardForUpdate(id: String) = boards.findByPublicIdForUpdate(id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    private fun etag(version: Int) = "draft-$version"
    private fun normalizeETag(raw: String) = raw.trim().removeSurrounding("\"")

    private data class CourseStopInput(val placeId: String, val orderIndex: Int, val role: String, val scheduledAt: Instant)

    companion object {
        private val VALID_ROLES = CourseStopRole.entries.map { it.name }.toSet()
    }
}
