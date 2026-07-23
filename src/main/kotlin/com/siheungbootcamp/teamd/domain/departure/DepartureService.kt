package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.course.CourseRepository
import com.siheungbootcamp.teamd.domain.course.CourseStopRepository
import com.siheungbootcamp.teamd.domain.course.CourseStopRole
import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 개인 출발 안내 계산 요청과 결과 조회를 담당한다.
 */
@Service
@Transactional(readOnly = true)
class DepartureService(
    private val departureRepository: DepartureCalculationRepository,
    private val courseRepository: CourseRepository,
    private val courseStopRepository: CourseStopRepository,
    private val participantRepository: ParticipantRepository,
    private val checks: AuthorizationChecks,
) {

    data class CalculationRequestResult(val isNewRequest: Boolean, val status: String, val courseVersion: Int)
    data class DepartureGuideResult(
        val status: String, val courseVersion: Int?, val firstMeeting: FirstMeetingInfo?,
        val transit: TransitInfo?, val recommendedDepartureAt: String?,
        val calculatedAt: String?, val basis: String?
    )
    data class FirstMeetingInfo(val placeId: String, val name: String, val scheduledAt: String)
    data class TransitInfo(val totalSeconds: Int, val transferCount: Int, val fare: FareInfo, val totalWalkSeconds: Int)
    data class FareInfo(val amount: Int, val currency: String = "KRW")

    @Transactional(readOnly = false)
    fun requestCalculation(boardId: String, principal: ParticipantPrincipal): CalculationRequestResult {
        checks.requireBoard(principal, boardId)
        val participant = participantRepository.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }

        if (participant.originCiphertext == null) throw BusinessException(ErrorCode.ORIGIN_REQUIRED)

        val boardIdInternal = requireNotNull(participant.board.id)
        val currentCourse = courseRepository.findTopByBoardIdOrderByVersionDesc(boardIdInternal)
            ?: throw BusinessException(ErrorCode.RESOURCE_CONFLICT)

        val courseStops = courseStopRepository.findByCourseIdOrderByOrderIndex(requireNotNull(currentCourse.id))
        if (courseStops.find { it.role == CourseStopRole.FIRST_MEETING.name } == null && courseStops.isEmpty()) {
            throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
        }

        val courseIdInternal = requireNotNull(currentCourse.id)
        val participantIdInternal = requireNotNull(participant.id)
        val existing = departureRepository.findByParticipantIdAndCourseId(participantIdInternal, courseIdInternal)

        if (existing != null) {
            return when (existing.status) {
                DepartureCalculation.Status.CALCULATING.name, DepartureCalculation.Status.READY.name -> {
                    CalculationRequestResult(false, existing.status, currentCourse.version)
                }
                else -> {
                    // STALE/UNAVAILABLE/FAILED: (participant_id, course_id) 유일 제약 때문에 새 행을 만들 수 없다.
                    // 기존 행을 재사용해 CALCULATING으로 되돌리고 이전 결과 필드를 비운다.
                    existing.resetToCalculating()
                    departureRepository.save(existing)
                    CalculationRequestResult(true, DepartureCalculation.Status.CALCULATING.name, currentCourse.version)
                }
            }
        }

        val new = DepartureCalculation(participant, currentCourse, DepartureCalculation.Status.CALCULATING.name)
        departureRepository.save(new)
        return CalculationRequestResult(true, DepartureCalculation.Status.CALCULATING.name, currentCourse.version)
    }

    fun getDepartureGuide(boardId: String, principal: ParticipantPrincipal): DepartureGuideResult {
        checks.requireBoard(principal, boardId)
        val participant = participantRepository.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }

        val boardIdInternal = requireNotNull(participant.board.id)
        val currentCourse = courseRepository.findTopByBoardIdOrderByVersionDesc(boardIdInternal)
            ?: return DepartureGuideResult("NOT_REQUESTED", null, null, null, null, null, null)

        val courseIdInternal = requireNotNull(currentCourse.id)
        val participantIdInternal = requireNotNull(participant.id)
        val calculation = departureRepository.findByParticipantIdAndCourseId(participantIdInternal, courseIdInternal)
            ?: return DepartureGuideResult("NOT_REQUESTED", currentCourse.version, null, null, null, null, null)

        return if (calculation.status == DepartureCalculation.Status.READY.name) {
            val courseStops = courseStopRepository.findByCourseIdOrderByOrderIndex(courseIdInternal)
            val firstMeeting = courseStops.find { it.role == CourseStopRole.FIRST_MEETING.name }
                ?: courseStops.firstOrNull()

            val firstMeetingInfo = firstMeeting?.let {
                FirstMeetingInfo(it.place.publicId, it.place.name, it.scheduledAt.toString())
            }

            val totalSeconds = calculation.totalSeconds
            val transferCount = calculation.transferCount
            val fareAmount = calculation.fareAmount
            val totalWalkSeconds = calculation.totalWalkSeconds
            val transitInfo = if (totalSeconds != null && transferCount != null &&
                fareAmount != null && totalWalkSeconds != null) {
                TransitInfo(totalSeconds, transferCount, FareInfo(fareAmount), totalWalkSeconds)
            } else null

            DepartureGuideResult(calculation.status, currentCourse.version, firstMeetingInfo, transitInfo,
                calculation.recommendedDepartureAt?.toString(), calculation.calculatedAt?.toString(), "CURRENT_TIMETABLE")
        } else {
            DepartureGuideResult(calculation.status, currentCourse.version, null, null, null, null, null)
        }
    }
}
