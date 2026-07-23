package com.siheungbootcamp.teamd.domain.place

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PlaceRepository : JpaRepository<Place, Long> {
    fun findByPublicIdAndBoardIdAndDeletedAtIsNull(publicId: String, boardId: Long): Place?
    fun findByPublicIdAndBoardId(publicId: String, boardId: Long): Place?
    fun findByPublicId(publicId: String): Place?

    // category와 bbox는 명세상 동시에 적용 가능한 독립 필터다(둘 다 null이면 무시).
    // sort=COMMENTS는 place_comment가 아직 P3에서 채워지지 않아(P2 시점엔 commentCount 항상 0)
    // RECENT와 동일하게 createdAt DESC로 둔다. 실제 댓글 수 기준 정렬은 P3에서 재구현해야 한다.
    @Query("""
        SELECT p FROM Place p
        WHERE p.board.id = :boardId
        AND p.deletedAt IS NULL
        AND (:category IS NULL OR p.internalCategory = :category)
        AND (:minLon IS NULL OR (p.lon >= :minLon AND p.lon <= :maxLon AND p.lat >= :minLat AND p.lat <= :maxLat))
        ORDER BY p.createdAt DESC
    """)
    fun findByBoardIdFiltered(
        boardId: Long,
        category: String?,
        minLon: Double?,
        minLat: Double?,
        maxLon: Double?,
        maxLat: Double?,
        pageable: Pageable,
    ): Page<Place>
}

@Repository
interface PlaceLikeRepository : JpaRepository<PlaceLike, PlaceLikeId> {
    @Query("select exists(select 1 from PlaceLike pl where pl.id.placeId = :placeId and pl.id.participantId = :participantId)")
    fun existsByPlaceIdAndParticipantId(@Param("placeId") placeId: Long, @Param("participantId") participantId: Long): Boolean

    @Query("select count(pl) from PlaceLike pl where pl.id.placeId = :placeId")
    fun countByPlaceId(@Param("placeId") placeId: Long): Long

    @Modifying
    @Query("delete from PlaceLike pl where pl.id.placeId = :placeId and pl.id.participantId = :participantId")
    fun deleteByPlaceIdAndParticipantId(@Param("placeId") placeId: Long, @Param("participantId") participantId: Long): Long
}
