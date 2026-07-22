package com.siheungbootcamp.teamd.domain.comment

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CommentRepository : JpaRepository<PlaceComment, Long> {
    fun findByPublicId(publicId: String): PlaceComment?

    fun findByPublicIdAndDeletedAtIsNull(publicId: String): PlaceComment?

    @Query("""
        SELECT c FROM PlaceComment c
        JOIN FETCH c.author
        WHERE c.place.id = :placeId
        AND c.deletedAt IS NULL
        ORDER BY c.createdAt DESC
    """)
    fun findByPlaceIdAndNotDeleted(placeId: Long, pageable: Pageable): Page<PlaceComment>

    @Query("""
        SELECT COUNT(c) FROM PlaceComment c
        WHERE c.place.id = :placeId
        AND c.deletedAt IS NULL
    """)
    fun countByPlaceIdAndNotDeleted(placeId: Long): Long

    /**
     * N+1 방지: 한 번의 쿼리로 여러 장소의 댓글 수를 집계한다.
     * 결과는 [placeId, count] 튜플 배열로 반환된다.
     */
    @Query("""
        SELECT c.place.id, COUNT(c) FROM PlaceComment c
        WHERE c.place.id IN :placeIds
        AND c.deletedAt IS NULL
        GROUP BY c.place.id
    """)
    fun countCommentsByPlaceIds(placeIds: List<Long>): List<Array<Any>>
}
