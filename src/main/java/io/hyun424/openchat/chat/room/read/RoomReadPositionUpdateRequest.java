package io.hyun424.openchat.chat.room.read;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RoomReadPositionUpdateRequest(
        @NotNull @Positive Long lastReadMessageId
) {
}
