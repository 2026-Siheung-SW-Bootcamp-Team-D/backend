package com.siheungbootcamp.teamd.domain.place

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface PlaceRepository : JpaRepository<Place, Long> {
    fun findByPublicIdAndBoardIdAndDeletedAtIsNull(publicId: String, boardId: Long): Place?
    fun findByPublicIdAndBoardId(publicId: String, boardId: Long): Place?
    fun findByPublicId(publicId: String): Place?

    @Query("""
        SELECT p FROM Place p
        WHERE p.board.id = :boardId
        AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
    """)
    fun findActiveByBoardId(boardId: Long, pageable: Pageable): Page<Place>

    @Query("""
        SELECT p FROM Place p
        WHERE p.board.id = :boardId
        AND p.deletedAt IS NULL
        AND (:category IS NULL OR p.internalCategory = :category)
        ORDER BY
            CASE WHEN :sort = 'COMMENTS' THEN 0 ELSE 1 END,
            CASE WHEN :sort = 'COMMENTS' THEN 0 ELSE p.createdAt END DESC
    """)
    fun findByBoardIdAndCategory(
        boardId: Long,
        category: String?,
        sort: String,
        pageable: Pageable,
    ): Page<Place>

    @Query("""
        SELECT p FROM Place p
        WHERE p.board.id = :boardId
        AND p.deletedAt IS NULL
        AND p.lon >= :minLon
        AND p.lon <= :maxLon
        AND p.lat >= :minLat
        AND p.lat <= :maxLat
        ORDER BY p.createdAt DESC
    """)
    fun findByBoardIdAndBbox(
        boardId: Long,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        pageable: Pageable,
    ): Page<Place>
}
