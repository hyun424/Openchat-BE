package io.hyun424.openchat.chat.websocket.listener;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

@Component
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.REALTIME)
public class WebSocketEventListener {

    private final RoomMemberService roomMemberService;

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(event.getMessage());

        String roomId = accessor.getFirstNativeHeader("roomId");
        String userId = accessor.getFirstNativeHeader("userId");

        if (roomId == null || userId == null) return;

        roomMemberService.join(Long.valueOf(roomId), userId);
    }
}
