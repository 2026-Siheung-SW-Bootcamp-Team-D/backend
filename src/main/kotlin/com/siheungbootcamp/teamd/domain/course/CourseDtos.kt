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
