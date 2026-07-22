package com.siheungbootcamp.teamd.domain.course

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

// 요청 DTO

data class PutCourseDraftRequest(
    @field:Valid @field:Size(min = 1, max = 10) val stops: List<DraftStopRequest>,
)

data class DraftStopRequest(
    @field:NotBlank val placeId: String,
    val orderIndex: Int,
    @field:NotBlank val role: String,
    @field:NotNull val scheduledAt: Instant,
)

data class ConfirmCourseRequest(val draftVersion: Int)

// 응답 DTO

data class CourseDraftResponse(
    val version: Int,
    val stops: List<DraftStopResponse>,
    val legs: List<CourseLegResponse>,
)

data class DraftStopResponse(
    val placeId: String,
    val orderIndex: Int,
    val role: String,
    val scheduledAt: Instant,
    // 초안은 확정과 달리 삭제된 장소를 참조한 채로 남을 수 있다(확정 전까지는 usage checker가
    // 막지 않음). 스톱을 숨기거나 legs 계산에서 빼지 않고, FE가 표시만 구분할 수 있게 플래그만 얹는다.
    val placeDeleted: Boolean = false,
)

data class CourseLegResponse(
    val fromOrder: Int,
    val toOrder: Int,
    val straightDistanceMeters: Int,
    val estimatedWalkMinutes: Int,
    val estimated: Boolean = true,
)

data class ConfirmCourseResponse(
    val courseId: String,
    val version: Int,
    val confirmedAt: Instant,
    val publicUrl: String,
)

data class CourseResponse(
    val courseId: String,
    val version: Int,
    val confirmedAt: Instant,
    val stops: List<CourseStopResponse>,
    val legs: List<CourseLegResponse>,
)

data class CourseStopResponse(
    val orderIndex: Int,
    val role: String,
    val scheduledAt: Instant,
    val placeId: String,
    val name: String,
    val roadAddressName: String?,
    val lon: Double,
    val lat: Double,
)

data class PublicScheduleResponse(
    val boardName: String,
    val date: LocalDate,
    val courseVersion: Int,
    val updatedAt: Instant,
    val stops: List<PublicScheduleStopResponse>,
    val legs: List<CourseLegResponse>,
)

data class PublicScheduleStopResponse(
    val orderIndex: Int,
    val role: String,
    val name: String,
    val roadAddressName: String?,
    val lon: Double,
    val lat: Double,
    val scheduledAt: Instant,
)
