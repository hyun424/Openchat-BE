package io.hyun424.openchat.infra.websocket.session;

import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.util.Locale;

public class WebSocketSendFailureClassifier {

    public String classify(Exception e) {
        if (e instanceof SessionLimitExceededException) {
            String message = lowerMessage(e);
            if (message.contains("send time")) {
                return "send_time_limit";
            }
            if (message.contains("buffer size")) {
                return "buffer_limit";
            }
        }
        if (isClosedSessionFailure(e)) {
            return "closed_during_send";
        }
        if (e instanceof IOException) {
            return "io_exception";
        }
        if (e instanceof IllegalStateException) {
            return "illegal_state";
        }
        return "unknown_exception";
    }

    private boolean isClosedSessionFailure(Exception e) {
        String message = lowerMessage(e);
        return message.contains("closed")
                || message.contains("close")
                || message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("eof");
    }

    private String lowerMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
