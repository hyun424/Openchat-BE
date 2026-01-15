package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;


    public void fanout(ChatMessageDto message) {
        try {
            Long roomId = message.getRoomId();

            outboundSender.send(message.getRoomId(), message);


            log.info(
                    "[FANOUT] roomId={} messageId={}",
                    message.getRoomId(),
                    message.getMessageId()
            );

        } catch (Exception e) {
            log.error(
                    "[FANOUT FAIL] roomId={} messageId={}",
                    message.getRoomId(),
                    message.getMessageId(),
                    e
            );
        }
    }
}
