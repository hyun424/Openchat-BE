package io.hyun424.openchat.chat.room.service;

import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.lifecycle.RoomLifecyclePublisher;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoomScheduler {

    private final RoomRepository roomRepository;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomLifecyclePublisher roomLifecyclePublisher;
    private final RoomAfterCommitExecutor afterCommitExecutor;

    /**
     * 매 10분마다 만료된 방 자동 종료
     * - meetingDate가 지났거나
     * - meetingDate가 오늘이고 meetingTime이 지난 경우
     */
    @Scheduled(fixedRate = 600_000) // 10분
    @Transactional
    public void autoEndExpiredRooms() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        List<Room> expiredRooms = roomRepository.findExpiredRooms(today, now);

        if (expiredRooms.isEmpty()) {
            return;
        }

        log.info("[SCHEDULER] Found {} expired rooms to end", expiredRooms.size());

        for (Room room : expiredRooms) {
            room.end();
            log.info("[SCHEDULER] Auto-ended room: id={}, name={}, meetingDate={}, meetingTime={}",
                    room.getId(), room.getName(), room.getMeetingDate(), room.getMeetingTime());

            Long roomId = room.getId();
            afterCommitExecutor.execute(() -> {
                roomSessionRegistry.closeAllSessionsInRoom(roomId);
                roomLifecyclePublisher.publishRoomEnded(roomId, "SCHEDULED_EXPIRED");
            });
        }
    }
}
