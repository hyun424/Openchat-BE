package io.hyun424.openchat.chat.publish;

public class ChatPublishException extends RuntimeException {

    public ChatPublishException(String message) {
        super(message);
    }

    public ChatPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
