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

    /**
     * 멱등적 좋아요 추가: INSERT ... ON CONFLICT DO NOTHING 사용
     *
     * 동시성 경쟁(race condition) 없이 멱등적이다.
     * 이미 좋아요되어 있으면 무시하고, 없으면 생성한다.
     * 고유 제약 위반으로 인한 예외를 발생시키지 않는다.
     *
     * @param placeId 장소 ID
     * @param participantId 참여자 ID
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO place_like (place_id, participant_id, created_at)
            VALUES (:placeId, :participantId, CURRENT_TIMESTAMP)
            ON CONFLICT (place_id, participant_id) DO NOTHING
        """
    )
    fun insertOrIgnore(
        @Param("placeId") placeId: Long,
        @Param("participantId") participantId: Long
    )

    /**
     * N+1 방지: 한 번의 쿼리로 여러 장소의 좋아요 수를 집계한다.
     * 결과는 [placeId, count] 튜플 배열로 반환된다.
     */
    @Query("""
        SELECT pl.id.placeId, COUNT(pl) FROM PlaceLike pl
        WHERE pl.id.placeId IN :placeIds
        GROUP BY pl.id.placeId
    """)
    fun countLikesByPlaceIds(@Param("placeIds") placeIds: List<Long>): List<Array<Any>>

    /**
     * N+1 방지: 한 번의 쿼리로 특정 참여자가 좋아요한 장소들의 ID를 조회한다.
     */
    @Query("""
        SELECT pl.id.placeId FROM PlaceLike pl
        WHERE pl.id.placeId IN :placeIds AND pl.id.participantId = :participantId
    """)
    fun findLikedPlaceIdsByParticipantId(
        @Param("placeIds") placeIds: List<Long>,
        @Param("participantId") participantId: Long
    ): List<Long>
}
