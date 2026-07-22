package com.siheungbootcamp.teamd.domain.comment

import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.domain.place.Place
import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/**
 * 장소에 대한 참여자의 의견·정보·경고를 기록한다.
 *
 * 댓글은 작성자 또는 호스트가 삭제할 수 있고 soft delete를 지원한다.
 * 본문은 공백 제거 후 1~500자의 일반 텍스트다. 서버는 HTML 렌더링을 수행하지 않는다.
 */
@Entity
@Table(name = "place_comment")
class PlaceComment(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.COMMENT),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "place_id") val place: Place,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id") val author: Participant,
    @Column(nullable = false) var body: String,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) : BaseEntity() {
    init {
        // Trim whitespace from body and validate length
        body = body.trim()
        require(body.length in 1..500) { "Comment body must be 1-500 characters after trimming" }
    }

    fun softDelete() {
        deletedAt = Instant.now()
    }

    fun updateBody(newBody: String) {
        body = newBody.trim()
        require(body.length in 1..500) { "Comment body must be 1-500 characters after trimming" }
    }
}
