package io.hyun424.openchat.infra.websocket.handler;

import io.hyun424.openchat.auth.jwt.JwtProvider;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 세션의 시간 기반 보안 검사를 담당한다.
 * 연결은 오래 유지될 수 있으므로 최초 handshake만 믿지 않고 주기적으로 토큰과 세션 수명을 확인한다.
 */
class WebSocketSessionGuard {

    private static final long TOKEN_VALIDATION_INTERVAL_MS = 5 * 60 * 1000;
    private static final long MAX_SESSION_DURATION_MS = 4 * 60 * 60 * 1000;

    private final JwtProvider jwtProvider;

    WebSocketSessionGuard(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    void markConnected(WebSocketSession session) {
        long now = System.currentTimeMillis();
        session.getAttributes().put("connectedAt", now);
        session.getAttributes().put("lastTokenValidation", now);
    }

    boolean isSessionExpired(WebSocketSession session) {
        Long connectedAt = (Long) session.getAttributes().get("connectedAt");
        return connectedAt != null && System.currentTimeMillis() - connectedAt > MAX_SESSION_DURATION_MS;
    }

    boolean isTokenValid(WebSocketSession session) {
        Long lastValidation = (Long) session.getAttributes().get("lastTokenValidation");
        long now = System.currentTimeMillis();

        if (lastValidation != null && now - lastValidation < TOKEN_VALIDATION_INTERVAL_MS) {
            return true;
        }

        String token = (String) session.getAttributes().get("token");
        if (token == null || !jwtProvider.validateToken(token)) {
            return false;
        }

        session.getAttributes().put("lastTokenValidation", now);
        return true;
    }
}
