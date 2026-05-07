package io.hyun424.openchat.infra.websocket.session;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketSendFailureClassifierTest {

    private final WebSocketSendFailureClassifier classifier = new WebSocketSendFailureClassifier();

    @Test
    void classifiesKnownSendFailures() {
        assertEquals("send_time_limit", classifier.classify(
                new SessionLimitExceededException("Send time 5001 (ms) exceeded", CloseStatus.SESSION_NOT_RELIABLE)));
        assertEquals("buffer_limit", classifier.classify(
                new SessionLimitExceededException("Buffer size 65537 bytes exceeds", CloseStatus.SESSION_NOT_RELIABLE)));
        assertEquals("closed_during_send", classifier.classify(new RuntimeException("Connection reset by peer")));
        assertEquals("io_exception", classifier.classify(new IOException("write failed")));
        assertEquals("illegal_state", classifier.classify(new IllegalStateException("bad lifecycle state")));
        assertEquals("unknown_exception", classifier.classify(new RuntimeException("unexpected")));
    }
}
