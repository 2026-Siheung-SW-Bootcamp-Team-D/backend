package com.siheungbootcamp.teamd.domain.board

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BoardRepository : JpaRepository<Board, Long> {
    fun findByPublicId(publicId: String): Board?
    fun findByInviteCode(inviteCode: String): Board?
}

interface ParticipantRepository : JpaRepository<Participant, Long> {
    fun countByBoardId(boardId: Long): Long
    fun findAllByBoardIdAndActiveTrueOrderById(boardId: Long): List<Participant>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Participant p join fetch p.board where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Participant?
}
