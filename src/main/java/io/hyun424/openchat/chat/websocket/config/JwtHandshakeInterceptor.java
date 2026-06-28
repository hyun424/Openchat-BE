package io.hyun424.openchat.chat.websocket.config;

import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.auth.resolver.AuthUserResolver;
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

    private final JwtProvider jwtProvider;
    private final AuthUserResolver authUserResolver;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        var queryParams = UriComponentsBuilder
                .fromUri(request.getURI())
                .build()
                .getQueryParams();
        String token = queryParams.getFirst("token");

        if (token == null || token.isBlank()) {
            return allowAnonymousHandshake(queryParams.getFirst("anonymousId"), queryParams.getFirst("nickname"), attributes);
        }

        try {
            Claims claims = jwtProvider.parseToken(token);

            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                log.warn("WS_HANDSHAKE_REJECT missing subject(userId)");
                return false;
            }

            String nickname = (String) claims.get("nickname");
            if (nickname == null || nickname.isBlank()) {
                log.warn("WS_HANDSHAKE_REJECT missing nickname claim");
                return false;
            }

            attributes.put("userId", userId);
            attributes.put("nickname", nickname);
            attributes.put("token", token);
            attributes.put("anonymous", false);

            return true;
        } catch (Exception e) {
            log.warn("WS_HANDSHAKE_REJECT invalid token", e);
            return false;
        }
    }

    private boolean allowAnonymousHandshake(String anonymousId, String nickname, Map<String, Object> attributes) {
        try {
            String userId = authUserResolver.anonymousUserId(anonymousId);
            attributes.put("userId", userId);
            attributes.put("nickname", authUserResolver.anonymousNickname(nickname, anonymousId));
            attributes.put("anonymous", true);
            return true;
        } catch (Exception e) {
            log.warn("WS_HANDSHAKE_REJECT missing anonymous identity");
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
