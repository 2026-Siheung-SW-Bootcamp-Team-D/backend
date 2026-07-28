package com.siheungbootcamp.teamd.global.sse

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 보드별 SSE 연결을 들고 있다가 REST 데이터가 바뀌었다는 신호만 밀어주는 컴포넌트다.
 *
 * 이벤트에는 실제로 바뀐 데이터를 담지 않는다("얇은 알림"). 프론트는 신호만 받고
 * 기존 REST API(`reload()`)로 다시 조회하므로, 여기서는 직렬화·권한 필터링·순서 보장을
 * 신경 쓸 필요가 없다. 이 원칙을 깨고 데이터를 실어 보내기 시작하면 그 모든 것이 따라붙는다.
 */
@Component
class BoardEventPublisher {
    private val log = LoggerFactory.getLogger(BoardEventPublisher::class.java)
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()

    /** 새 SSE 연결을 등록한다. 타임아웃을 두지 않아야 클라이언트가 계속 붙어 있을 수 있다. */
    fun register(boardId: String): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        val boardEmitters = emitters.computeIfAbsent(boardId) { CopyOnWriteArrayList() }
        boardEmitters.add(emitter)

        val remove = Runnable { removeEmitter(boardId, emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove.run() }
        return emitter
    }

    /** 리소스가 바뀌었다는 신호만 보드의 모든 연결에 보낸다. 데이터는 절대 싣지 않는다. */
    fun publish(boardId: String, resource: String) {
        val boardEmitters = emitters[boardId] ?: return
        for (emitter in boardEmitters) {
            try {
                emitter.send(SseEmitter.event().name("update").data(mapOf("resource" to resource)))
            } catch (e: IOException) {
                // 한 클라이언트의 연결 끊김이 다른 연결이나 호출한 트랜잭션에 전파되면 안 된다.
                removeEmitter(boardId, emitter)
            }
        }
    }

    /** 15초마다 comment ping을 보내 프록시의 idle timeout으로 연결이 끊기는 것을 막는다. */
    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        for ((boardId, boardEmitters) in emitters) {
            for (emitter in boardEmitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"))
                } catch (e: IOException) {
                    removeEmitter(boardId, emitter)
                }
            }
        }
    }

    internal fun activeCount(boardId: String): Int = emitters[boardId]?.size ?: 0

    private fun removeEmitter(boardId: String, emitter: SseEmitter) {
        val boardEmitters = emitters[boardId] ?: return
        boardEmitters.remove(emitter)
        if (boardEmitters.isEmpty()) {
            emitters.remove(boardId, boardEmitters)
        }
    }
}
