package com.siheungbootcamp.teamd.domain.course

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CourseDraftRepository : JpaRepository<CourseDraft, Long> {
    fun findByBoardId(boardId: Long): CourseDraft?

    /** PUT 동시 요청 중 하나만 성공하도록 초안 행을 잠그고 읽는다(V4-2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CourseDraft d where d.board.id = :boardId")
    fun findByBoardIdForUpdate(@Param("boardId") boardId: Long): CourseDraft?
}

@Repository
interface CourseRepository : JpaRepository<Course, Long> {
    fun findByBoardIdAndPublicId(boardId: Long, publicId: String): Course?
    fun findTopByBoardIdOrderByVersionDesc(boardId: Long): Course?
}

@Repository
interface CourseStopRepository : JpaRepository<CourseStop, Long> {
    fun findByCourseIdOrderByOrderIndex(courseId: Long): List<CourseStop>

    /** 장소 삭제 가능 여부 판단(T4-3)과 공개 일정의 `updatedAt` 계산에 쓰인다.
     * 오래된 확정 버전도 조회 가능해야 하므로 모든 버전을 대상으로 한다. */
    @Query("select cs from CourseStop cs where cs.place.id = :placeId order by cs.course.version desc")
    fun findByPlaceId(@Param("placeId") placeId: Long): List<CourseStop>
}
