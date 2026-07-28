package com.siheungbootcamp.teamd.global.sse

import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.test.assertEquals

/**
 * `SseEmitter`는 실제 서블릿 비동기 요청으로 초기화되어야 onCompletion 콜백이 울린다.
 * 단위 테스트에는 그런 요청이 없으므로, 내부 completionCallback 델리게이트를 리플렉션으로
 * 직접 호출해 "연결 종료"를 재현한다. 연결 종료 시 정리 로직만 검증하면 되므로 이 정도로 충분하다.
 */
private fun simulateCompletion(emitter: SseEmitter) {
    val field = ResponseBodyEmitter::class.java.getDeclaredField("completionCallback")
    field.isAccessible = true
    (field.get(emitter) as Runnable).run()
}

class BoardEventPublisherTest {
    @Test
    fun `등록하면 해당 보드의 활성 연결 수가 늘어난다`() {
        val publisher = BoardEventPublisher()

        publisher.register("board-1")

        assertEquals(1, publisher.activeCount("board-1"))
        assertEquals(0, publisher.activeCount("board-2"))
    }

    @Test
    fun `발행은 다른 보드의 연결에 영향을 주지 않는다`() {
        val publisher = BoardEventPublisher()
        publisher.register("board-1")
        publisher.register("board-2")

        publisher.publish("board-1", "participants")

        assertEquals(1, publisher.activeCount("board-1"))
        assertEquals(1, publisher.activeCount("board-2"))
    }

    @Test
    fun `연결이 완료되면 목록에서 제거되고 마지막 연결이면 키도 사라진다`() {
        val publisher = BoardEventPublisher()
        val emitter = publisher.register("board-1")

        simulateCompletion(emitter)

        assertEquals(0, publisher.activeCount("board-1"))
    }
}
