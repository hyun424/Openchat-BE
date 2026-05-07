package io.hyun424.openchat.chat.room.service;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.lifecycle.RoomLifecyclePublisher;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.chat.room.shard.RoomShardAssignmentService;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private RoomSessionRegistry roomSessionRegistry;
    @Mock private RoomLifecyclePublisher roomLifecyclePublisher;
    @Mock private RoomAfterCommitExecutor afterCommitExecutor;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;
    @Mock private RoomShardAssignmentService roomShardAssignmentService;
    @Mock private RoomPartitionStateService roomPartitionStateService;

    @Test
    void createRoom_initializesPartitionStateForSavedRoom() {
        RoomService service = new RoomService(
                roomRepository,
                roomSessionRegistry,
                roomLifecyclePublisher,
                afterCommitExecutor,
                chatPipelineMetrics,
                roomShardAssignmentService,
                roomPartitionStateService
        );
        RoomCreateRequest request = new RoomCreateRequest();
        ReflectionTestUtils.setField(request, "name", "Test Room");
        ReflectionTestUtils.setField(request, "maxMembers", 10);
        Room savedRoom = Room.builder()
                .id(10L)
                .name("Test Room")
                .ownerId("owner-1")
                .shardId(2)
                .maxMembers(10)
                .requiresApproval(false)
                .build();

        when(roomShardAssignmentService.assignShardForNewRoom()).thenReturn(2);
        when(roomRepository.save(any(Room.class))).thenReturn(savedRoom);

        Room room = service.createRoom("owner-1", request);

        assertEquals(10L, room.getId());
        verify(roomPartitionStateService).ensureInitialized(10L);
        ArgumentCaptor<Room> captor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getShardId());
    }
}
