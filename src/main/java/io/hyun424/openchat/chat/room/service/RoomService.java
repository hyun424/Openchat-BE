package io.hyun424.openchat.chat.room.service;

import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;

    public Room createRoom(String userId, String name) {
        Room room = Room.builder()
                .name(name)
                .ownerId(userId)
                .build();

        return roomRepository.save(room);
    }



    public List<Room> getRooms() {
        return roomRepository.findAll();
    }

    public Room getRoomOrThrow(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
    }
}
