package io.hyun424.openchat.chat.room.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RoomLifecycleEvent {
    private String type;
    private Long roomId;
    private String reason;
    private long timestamp;
}
