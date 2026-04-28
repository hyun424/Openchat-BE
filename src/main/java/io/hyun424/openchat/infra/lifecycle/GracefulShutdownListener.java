package io.hyun424.openchat.infra.lifecycle;

import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Graceful shutdown: 앱 종료 시 WebSocket 세션을 안전하게 정리한다.
 *
 * 종료 순서:
 * 1. 새 WS 연결 거부
 * 2. 전체 WS 세션에 1001(Going Away) 전송
 * 3. in-flight 메시지 완료 대기 (최대 5초)
 *
 * Kafka/Redis 구독 정리는 Spring이 @PreDestroy를 통해 자동 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownListener implements ApplicationListener<ContextClosedEvent> {

    private final RoomSessionRegistry roomSessionRegistry;

    private static final long IN_FLIGHT_WAIT_MS = 5000;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("[SHUTDOWN] Graceful shutdown initiated");

        // 1. Stop accepting new connections
        roomSessionRegistry.stopAcceptingConnections();

        // 2. Wait for in-flight messages to complete
        try {
            log.info("[SHUTDOWN] Waiting {}ms for in-flight messages...", IN_FLIGHT_WAIT_MS);
            Thread.sleep(IN_FLIGHT_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SHUTDOWN] Interrupted while waiting for in-flight messages");
        }

        // 3. Close all WebSocket sessions with 1001 (Going Away)
        int sessionCount = roomSessionRegistry.getTotalSessionCount();
        log.info("[SHUTDOWN] Closing {} WebSocket sessions...", sessionCount);
        roomSessionRegistry.closeAllSessions();

        log.info("[SHUTDOWN] Graceful shutdown completed");
    }
}
