package io.hyun424.openchat.chat.websocket.config;

import io.hyun424.openchat.auth.jwt.JwtProvider; // ✅ 너 프로젝트의 실제 JwtProvider 클래스명/패키지에 맞춰 바꿔
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtProvider jwtProvider; // ✅ 실제 클래스에 맞추기

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = UriComponentsBuilder
                .fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");

        if (token == null || token.isBlank()) {
            log.warn("WS_HANDSHAKE_REJECT missing token");
            return false;
        }

        try {
            Claims claims = jwtProvider.parseToken(token); // ✅ 실제 메서드명에 맞추기 (예: validateAndGetClaims 등)

            // ✅ userId
            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                log.warn("WS_HANDSHAKE_REJECT missing subject(userId)");
                return false;
            }

            // ✅ nickname (토큰에 넣어둔 클레임명에 맞추기)
            // 예: "nickname" / "userName" / "name"
            String nickname = (String) claims.get("nickname");
            if (nickname == null || nickname.isBlank()) {
                // ✅ 최선: nickname도 JWT에서 반드시 제공되어야 한다.
                // 토큰에 없다면, 토큰 발급 시점부터 nickname claim을 넣도록 고쳐야 함.
                log.warn("WS_HANDSHAKE_REJECT missing nickname claim");
                return false;
            }

            attributes.put("userId", userId);
            attributes.put("nickname", nickname);
            attributes.put("token", token);

            return true;
        } catch (Exception e) {
            log.warn("WS_HANDSHAKE_REJECT invalid token", e);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }
}
