package com.siheungbootcamp.teamd.domain.course

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4 코스 계약 테스트 (V4-1~V4-14)
 *
 * 엔드포인트 27-31, 34: 코스 초안 조회·저장, 코스 확정, 확정 코스 조회, 공개 일정 조회.
 * 낙관적 잠금(If-Match/ETag), 검증 규칙, 버전 보존, 공개 토큰, 장소 사용 체크, 민감정보 부재를 검증한다.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
    "app.legacy-api-enabled=true",
])
class P4CourseContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `V4-1 순서 저장 후 버전 충돌을 거쳐 확정하고 공개 조회까지 성공한다`() {
        val host = createBoard("E2E 코스 보드", "호스트")
        val meetingPlace = createPlace(host, "만남 장소", 126.97, 37.55)
        val cafePlace = createPlace(host, "카페", 126.98, 37.56)

        val initialETag = getDraftETag(host)
        assertEquals("\"draft-0\"", initialETag)

        val now = Instant.now().plus(1, ChronoUnit.HOURS)
        val putBody = mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(host.token)
            header("If-Match", initialETag)
            contentType = MediaType.APPLICATION_JSON
            content = stopsRequest(
                stop(meetingPlace.placeId, 1, "FIRST_MEETING", now),
                stop(cafePlace.placeId, 2, "CAFE", now.plus(1, ChronoUnit.HOURS)),
            )
        }.andExpect { status { isOk() } }.andReturn()

        assertEquals("\"draft-1\"", putBody.response.getHeader("ETag"))
        val putJson = objectMapper.readTree(putBody.response.contentAsString)
        assertEquals(1, putJson.path("version").asInt())
        assertEquals(1, putJson.path("legs").size())

        // 이미 낡은 ETag(draft-0)로 재시도하면 412 + 최신 ETag
        mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(host.token)
            header("If-Match", initialETag)
            contentType = MediaType.APPLICATION_JSON
            content = stopsRequest(stop(meetingPlace.placeId, 1, "FIRST_MEETING", now))
        }.andExpect {
            status { isEqualTo(412) }
            jsonPath("$.error.code") { value("VERSION_MISMATCH") }
            header { string("ETag", "\"draft-1\"") }
        }

        // 확정
        val confirmBody = mockMvc.post("/api/v1/boards/${host.boardId}/courses") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"draftVersion":1}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val confirmJson = objectMapper.readTree(confirmBody)
        assertEquals(1, confirmJson.path("version").asInt())
        val publicUrl = confirmJson.path("publicUrl").asText()
        assertTrue(publicUrl.startsWith("https://example.app/s/pub_"))
        val publicToken = publicUrl.substringAfterLast("/")

        // 공개 조회
        val publicBody = mockMvc.get("/api/v1/public/schedules/$publicToken") {}
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val publicJson = objectMapper.readTree(publicBody)
        assertEquals("E2E 코스 보드", publicJson.path("boardName").asText())
        assertEquals(1, publicJson.path("courseVersion").asInt())
        assertEquals(2, publicJson.path("stops").size())
        assertEquals(1, publicJson.path("legs").size())
    }

    @Test
    fun `V4-2 같은 ETag로 동시 PUT하면 하나만 성공하고 하나는 412를 반환한다`() {
        val host = createBoard("동시 편집 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val etag = getDraftETag(host)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)
        val body = stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))

        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            executor.submit<Int> {
                readyLatch.countDown()
                startLatch.await()
                mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
                    bearer(host.token)
                    header("If-Match", etag)
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn().response.status
            }
        }
        readyLatch.await()
        startLatch.countDown()
        val statusCodes = futures.map { it.get() }
        executor.shutdown()

        assertEquals(1, statusCodes.count { it == 200 }, "동시 PUT 중 정확히 하나만 200이어야 함: $statusCodes")
        assertEquals(1, statusCodes.count { it == 412 }, "동시 PUT 중 정확히 하나만 412여야 함: $statusCodes")
    }

    @Test
    fun `V4-3 If-Match 헤더 없는 PUT은 400 INVALID_ARGUMENT를 반환한다`() {
        val host = createBoard("If-Match 누락 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V4-4a orderIndex가 연속되지 않으면 400을 반환한다`() {
        val host = createBoard("orderIndex 검증 보드", "호스트")
        val p1 = createPlace(host, "장소1", 126.97, 37.55)
        val p2 = createPlace(host, "장소2", 126.98, 37.56)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(
            stop(p1.placeId, 1, "FIRST_MEETING", now),
            stop(p2.placeId, 3, "CAFE", now.plus(1, ChronoUnit.HOURS)),
        )).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V4-4b orderIndex가 중복되면 400을 반환한다`() {
        val host = createBoard("orderIndex 중복 보드", "호스트")
        val p1 = createPlace(host, "장소1", 126.97, 37.55)
        val p2 = createPlace(host, "장소2", 126.98, 37.56)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(
            stop(p1.placeId, 1, "FIRST_MEETING", now),
            stop(p2.placeId, 1, "CAFE", now.plus(1, ChronoUnit.HOURS)),
        )).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V4-4c FIRST_MEETING이 2개 이상이면 400을 반환한다`() {
        val host = createBoard("FIRST_MEETING 중복 보드", "호스트")
        val p1 = createPlace(host, "장소1", 126.97, 37.55)
        val p2 = createPlace(host, "장소2", 126.98, 37.56)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(
            stop(p1.placeId, 1, "FIRST_MEETING", now),
            stop(p2.placeId, 2, "FIRST_MEETING", now.plus(1, ChronoUnit.HOURS)),
        )).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V4-4d scheduledAt이 직전 장소보다 늦지 않으면 400을 반환한다`() {
        val host = createBoard("시각 역전 보드", "호스트")
        val p1 = createPlace(host, "장소1", 126.97, 37.55)
        val p2 = createPlace(host, "장소2", 126.98, 37.56)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(
            stop(p1.placeId, 1, "FIRST_MEETING", now),
            stop(p2.placeId, 2, "CAFE", now.minus(1, ChronoUnit.MINUTES)),
        )).andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V4-4e 삭제된 장소를 참조하면 400을 반환한다`() {
        val host = createBoard("삭제 장소 참조 보드", "호스트")
        val p1 = createPlace(host, "장소1", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${p1.placeId}") { bearer(host.token) }
            .andExpect { status { isNoContent() } }

        putDraft(host, getDraftETag(host), stopsRequest(stop(p1.placeId, 1, "FIRST_MEETING", now)))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
            }
    }

    @Test
    fun `저장된 초안의 장소가 나중에 삭제되면 GET 응답에서 placeDeleted가 true다`() {
        val host = createBoard("초안 참조 장소 삭제 후 조회 보드", "호스트")
        val meetingPlace = createPlace(host, "만남 장소", 126.97, 37.55)
        val cafePlace = createPlace(host, "카페", 126.98, 37.56)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(
            stop(meetingPlace.placeId, 1, "FIRST_MEETING", now),
            stop(cafePlace.placeId, 2, "CAFE", now.plus(1, ChronoUnit.HOURS)),
        )).andExpect { status { isOk() } }

        // 저장 이후에 카페만 삭제한다. 초안은 usage checker의 보호를 받지 않으므로 삭제가 허용된다.
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${cafePlace.placeId}") { bearer(host.token) }
            .andExpect { status { isNoContent() } }

        val body = mockMvc.get("/api/v1/boards/${host.boardId}/course-draft") { bearer(host.token) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        val stops = json.path("stops")

        assertEquals(2, stops.size(), "삭제된 장소를 참조한 스톱도 숨기지 않고 그대로 노출해야 함")
        assertFalse(stops[0].path("placeDeleted").asBoolean(), "삭제되지 않은 첫 스톱은 placeDeleted=false")
        assertTrue(stops[1].path("placeDeleted").asBoolean(), "삭제된 장소를 참조한 스톱은 placeDeleted=true")
        assertEquals(1, json.path("legs").size(), "삭제된 스톱이 있어도 legs는 전체 스톱 기준으로 계산되어야 함")
    }

    @Test
    fun `V4-4f 장소가 11개면 400을 반환한다`() {
        val host = createBoard("11개 장소 보드", "호스트")
        val now = Instant.now().plus(1, ChronoUnit.HOURS)
        val places = (1..11).map { i -> createPlace(host, "장소$i", 126.90 + i * 0.001, 37.50 + i * 0.001) }
        val stopsJson = places.mapIndexed { index, place ->
            stop(place.placeId, index + 1, if (index == 0) "FIRST_MEETING" else "ETC", now.plus(index.toLong(), ChronoUnit.HOURS))
        }

        putDraft(host, getDraftETag(host), stopsRequest(*stopsJson.toTypedArray()))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
            }
    }

    @Test
    fun `V4-6 확정할 때마다 이전 버전이 보존된다`() {
        val host = createBoard("버전 보존 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        val etag1 = getDraftETag(host)
        putDraft(host, etag1, stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirm1 = confirmCourse(host, 1)
        val course1Id = objectMapper.readTree(confirm1).path("courseId").asText()

        val etag2 = getDraftETag(host)
        putDraft(host, etag2, stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now.plus(1, ChronoUnit.HOURS)))).andExpect { status { isOk() } }
        val confirm2 = confirmCourse(host, 2)
        val course2Id = objectMapper.readTree(confirm2).path("courseId").asText()

        val courseCount = jdbcClient.sql("select count(*) from course where board_id = (select id from board where public_id = :boardId)")
            .param("boardId", host.boardId).query(Int::class.java).single()
        assertEquals(2, courseCount)

        mockMvc.get("/api/v1/boards/${host.boardId}/courses/$course1Id") { bearer(host.token) }
            .andExpect { status { isOk() }; jsonPath("$.version") { value(1) } }
        mockMvc.get("/api/v1/boards/${host.boardId}/courses/$course2Id") { bearer(host.token) }
            .andExpect { status { isOk() }; jsonPath("$.version") { value(2) } }
        mockMvc.get("/api/v1/boards/${host.boardId}/courses/current") { bearer(host.token) }
            .andExpect { status { isOk() }; jsonPath("$.version") { value(2) } }
    }

    @Test
    fun `V4-7 확정하면 보드 상태가 CONFIRMED로 바뀐다`() {
        val host = createBoard("보드 상태 전이 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        confirmCourse(host, 1)

        mockMvc.get("/api/v1/boards/${host.boardId}") { bearer(host.token) }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("CONFIRMED") } }
    }

    @Test
    fun `V4-8 최초 확정에서만 공개 토큰이 생성되고 재확정에도 동일하다`() {
        val host = createBoard("공개 토큰 유지 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirm1 = objectMapper.readTree(confirmCourse(host, 1))
        val publicUrl1 = confirm1.path("publicUrl").asText()

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now.plus(1, ChronoUnit.HOURS)))).andExpect { status { isOk() } }
        val confirm2 = objectMapper.readTree(confirmCourse(host, 2))
        val publicUrl2 = confirm2.path("publicUrl").asText()

        assertEquals(publicUrl1, publicUrl2, "재확정해도 공개 URL(토큰)이 동일해야 함")
    }

    @Test
    fun `V4-9 draftVersion이 현재 초안과 다르면 409 RESOURCE_CONFLICT를 반환한다`() {
        val host = createBoard("draftVersion 불일치 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }

        mockMvc.post("/api/v1/boards/${host.boardId}/courses") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"draftVersion":99}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RESOURCE_CONFLICT") }
        }
    }

    @Test
    fun `V4-10 확정 코스가 참조하는 장소 삭제는 409 PLACE_IN_USE와 상세정보를 반환한다`() {
        val host = createBoard("코스 장소 사용 체크 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirmJson = objectMapper.readTree(confirmCourse(host, 1))
        val courseId = confirmJson.path("courseId").asText()

        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}") { bearer(host.token) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.error.code") { value("PLACE_IN_USE") }
                jsonPath("$.error.details.courseId") { value(courseId) }
                jsonPath("$.error.details.orderIndex") { value(1) }
            }
    }

    @Test
    fun `V4-11 공개 일정 응답에는 참여자·출발지·토큰·댓글·투표·boardId 키가 없다`() {
        val host = createBoard("공개 응답 민감정보 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirmJson = objectMapper.readTree(confirmCourse(host, 1))
        val publicToken = confirmJson.path("publicUrl").asText().substringAfterLast("/")

        val body = mockMvc.get("/api/v1/public/schedules/$publicToken") {}
            .andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertFalse(body.contains("participant", ignoreCase = true), "participant 키가 없어야 함")
        assertFalse(body.contains("origin", ignoreCase = true), "origin 키가 없어야 함")
        assertFalse(body.contains("token", ignoreCase = true), "token 키가 없어야 함")
        assertFalse(body.contains("\"boardId\""), "boardId 키가 없어야 함")
        assertFalse(body.contains("comment", ignoreCase = true), "comment 키가 없어야 함")
        assertFalse(body.contains("vote", ignoreCase = true), "vote 키가 없어야 함")
    }

    @Test
    fun `V4-12 CLOSED 보드의 공개 일정 조회는 404를 반환한다`() {
        val host = createBoard("종료된 보드 공개 조회", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirmJson = objectMapper.readTree(confirmCourse(host, 1))
        val publicToken = confirmJson.path("publicUrl").asText().substringAfterLast("/")

        mockMvc.patch("/api/v1/boards/${host.boardId}") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"CLOSED"}"""
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/v1/public/schedules/$publicToken") {}
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error.code") { value("RESOURCE_NOT_FOUND") }
            }
    }

    @Test
    fun `V4-13 공개 일정 API는 인증 헤더 없이 200을 반환한다`() {
        val host = createBoard("인증 없는 공개 조회 보드", "호스트")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        putDraft(host, getDraftETag(host), stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))).andExpect { status { isOk() } }
        val confirmJson = objectMapper.readTree(confirmCourse(host, 1))
        val publicToken = confirmJson.path("publicUrl").asText().substringAfterLast("/")

        // bearer()를 호출하지 않아 Authorization 헤더 없이 요청한다.
        mockMvc.get("/api/v1/public/schedules/$publicToken") {}
            .andExpect { status { isOk() } }
    }

    @Test
    fun `엔드포인트 27 - 초안이 없으면 200과 함께 빈 초안을 반환한다`() {
        val host = createBoard("빈 초안 보드", "호스트")
        val body = mockMvc.get("/api/v1/boards/${host.boardId}/course-draft") { bearer(host.token) }
            .andExpect { status { isOk() } }.andReturn()

        assertEquals("\"draft-0\"", body.response.getHeader("ETag"))
        val json = objectMapper.readTree(body.response.contentAsString)
        assertEquals(0, json.path("version").asInt())
        assertEquals(0, json.path("stops").size())
    }

    @Test
    fun `엔드포인트 28 - 호스트가 아니면 코스 초안 저장이 403을 반환한다`() {
        val host = createBoard("호스트 전용 저장 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        val place = createPlace(host, "장소", 126.97, 37.55)
        val now = Instant.now().plus(1, ChronoUnit.HOURS)

        mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(member)
            header("If-Match", "\"draft-0\"")
            contentType = MediaType.APPLICATION_JSON
            content = stopsRequest(stop(place.placeId, 1, "FIRST_MEETING", now))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `엔드포인트 30 - 확정 코스가 없으면 404를 반환한다`() {
        val host = createBoard("확정 코스 없음 보드", "호스트")
        mockMvc.get("/api/v1/boards/${host.boardId}/courses/current") { bearer(host.token) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error.code") { value("RESOURCE_NOT_FOUND") }
            }
    }

    // Helper methods

    private fun createBoard(name: String, nickname: String): CreatedBoard {
        val body = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","dateRange":{"start":"2099-01-01","end":"2099-01-02"},"purpose":"테스트","hostNickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return CreatedBoard(json["board"]["boardId"].asText(), json["participant"]["participantToken"].asText(), json["invitation"]["inviteCode"].asText())
    }

    private fun join(inviteCode: String, nickname: String): String {
        val body = mockMvc.post("/api/v1/invitations/$inviteCode/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(body)["participantToken"].asText()
    }

    private fun createPlace(host: CreatedBoard, name: String, lon: Double, lat: Double): PlaceInfo {
        val body = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": $lon,
              "lat": $lat,
              "addressName": "서울시",
              "roadAddressName": "서울시 어딘가",
              "internalCategory": "RESTAURANT",
              "provider": null,
              "providerPlaceId": null,
              "providerPlaceUrl": null,
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return PlaceInfo(json["placeId"].asText())
    }

    private fun getDraftETag(host: CreatedBoard): String {
        val result = mockMvc.get("/api/v1/boards/${host.boardId}/course-draft") { bearer(host.token) }
            .andExpect { status { isOk() } }.andReturn()
        return result.response.getHeader("ETag") ?: error("ETag 헤더가 없습니다")
    }

    private fun putDraft(host: CreatedBoard, ifMatch: String, body: String) =
        mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(host.token)
            header("If-Match", ifMatch)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun confirmCourse(host: CreatedBoard, draftVersion: Int): String =
        mockMvc.post("/api/v1/boards/${host.boardId}/courses") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"draftVersion":$draftVersion}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString

    private fun stop(placeId: String, orderIndex: Int, role: String, scheduledAt: Instant) =
        """{"placeId":"$placeId","orderIndex":$orderIndex,"role":"$role","scheduledAt":"$scheduledAt"}"""

    private fun stopsRequest(vararg stops: String) = """{"stops":[${stops.joinToString(",")}]}"""

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)
    private data class PlaceInfo(val placeId: String)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
