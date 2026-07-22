package com.siheungbootcamp.teamd.domain.vote

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface VoteRepository : JpaRepository<Vote, Long> {
    fun findByPublicId(publicId: String): Vote?

    /**
     * 보드의 열린 투표를 찾는다.
     * 부분 unique 인덱스 `create unique index on vote (board_id) where status = 'OPEN'`로 보호된다.
     */
    @Query("SELECT v FROM Vote v WHERE v.board.id = :boardId AND v.status = 'OPEN'")
    fun findOpenByBoardId(boardId: Long): Vote?

    @Query("""
        SELECT v FROM Vote v
        WHERE v.board.id = :boardId
        AND (:status IS NULL OR v.status = :status)
        ORDER BY v.createdAt DESC
    """)
    fun findByBoardIdAndStatus(boardId: Long, status: VoteStatus?, pageable: Pageable): Page<Vote>
}

@Repository
interface VoteOptionRepository : JpaRepository<VoteOption, Long> {
    @Query("SELECT vo FROM VoteOption vo WHERE vo.vote.id = :voteId")
    fun findByVoteId(voteId: Long): List<VoteOption>

    @Query("SELECT vo FROM VoteOption vo WHERE vo.vote.id = :voteId AND vo.place.id = :placeId")
    fun findByVoteIdAndPlaceId(voteId: Long, placeId: Long): VoteOption?

    @Query("SELECT vo FROM VoteOption vo WHERE vo.place.id = :placeId AND vo.vote.status = 'OPEN'")
    fun findOpenVotesByPlaceId(placeId: Long): List<VoteOption>

    /**
     * N+1 방지: 한 번의 쿼리로 여러 투표의 후보 개수를 집계한다.
     * 결과는 [voteId, count] 튜플 배열로 반환된다.
     */
    @Query("""
        SELECT vo.vote.id, COUNT(vo) FROM VoteOption vo
        WHERE vo.vote.id IN :voteIds
        GROUP BY vo.vote.id
    """)
    fun countByVoteIds(voteIds: List<Long>): List<Array<Any>>
}

@Repository
interface VoteBallotRepository : JpaRepository<VoteBallot, Long> {
    /**
     * 특정 투표에 대한 참여자의 모든 선택을 찾는다.
     */
    @Query("SELECT vb FROM VoteBallot vb WHERE vb.vote.id = :voteId AND vb.participant.id = :participantId")
    fun findByVoteIdAndParticipantId(voteId: Long, participantId: Long): List<VoteBallot>

    /**
     * 투표의 모든 선택을 찾는다.
     */
    @Query("SELECT vb FROM VoteBallot vb WHERE vb.vote.id = :voteId")
    fun findByVoteId(voteId: Long): List<VoteBallot>

    /**
     * 투표 옵션별 선택 수를 집계한다.
     * 결과는 [optionId, count] 튜플 배열로 반환된다.
     */
    @Query("""
        SELECT vb.option.id, COUNT(vb) FROM VoteBallot vb
        WHERE vb.vote.id = :voteId
        GROUP BY vb.option.id
    """)
    fun countByVoteId(voteId: Long): List<Array<Any>>
}
