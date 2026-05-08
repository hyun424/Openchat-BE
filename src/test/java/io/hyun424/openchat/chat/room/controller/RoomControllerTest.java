package io.hyun424.openchat.chat.room.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.dto.RoomListResponse;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionRoute;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRouteUnavailableException;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import io.hyun424.openchat.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private RoomService roomService;
    @Mock private RoomMemberService roomMemberService;
    @Mock private RoomPartitionRoutingService roomPartitionRoutingService;
    @InjectMocks private RoomController roomController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(roomController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/rooms - 인증된 유저가 방 생성")
    void createRoom_success() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();

        when(roomService.createRoom(eq("user1"), any())).thenReturn(room);

        mockMvc.perform(post("/api/rooms")
                        .principal(authUser("user1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Room\",\"maxMembers\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Test Room"));
    }

    @Test
    @DisplayName("POST /api/rooms - 이름 없이 생성 시 400")
    void createRoom_noName_badRequest() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .principal(authUser("user1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/rooms - 방 목록 페이지네이션")
    void getRooms_pagination() throws Exception {
        Page<RoomListResponse> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(roomService.getRoomsWithMemberCount(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/rooms").param("page", "0").param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/rooms - page 음수 보정")
    void getRooms_negativePageCorrected() throws Exception {
        Page<RoomListResponse> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(roomService.getRoomsWithMemberCount(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/rooms").param("page", "-5").param("size", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/rooms - size 초과 보정 (max 50)")
    void getRooms_sizeCapped() throws Exception {
        Page<RoomListResponse> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(roomService.getRoomsWithMemberCount(0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/rooms").param("page", "0").param("size", "100"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId}/ws-route - WebSocket partition route 조회")
    void getWebSocketRoute_success() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();
        when(roomService.getActiveRoomOrThrow(1L)).thenReturn(room);
        when(roomPartitionRoutingService.route(1L, "user1"))
                .thenReturn(new RoomPartitionRoute(1L, true, 1, 2, "/ws/chat?roomId=1&partitionId=1"));

        mockMvc.perform(get("/api/rooms/1/ws-route")
                        .principal(authUser("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(1))
                .andExpect(jsonPath("$.partitioned").value(true))
                .andExpect(jsonPath("$.partitionId").value(1))
                .andExpect(jsonPath("$.partitionCount").value(2));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId}/ws-route - partition owner 준비 전 503")
    void getWebSocketRoute_ownerNotReady() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();
        when(roomService.getActiveRoomOrThrow(1L)).thenReturn(room);
        when(roomPartitionRoutingService.route(1L, "user1"))
                .thenThrow(new RoomPartitionRouteUnavailableException("owner_not_ready"));

        mockMvc.perform(get("/api/rooms/1/ws-route")
                        .principal(authUser("user1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.reason").value("owner_not_ready"))
                .andExpect(jsonPath("$.retryAfterMs").value(500));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId}/ws-route - assignment unavailable 503")
    void getWebSocketRoute_assignmentUnavailable() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();
        when(roomService.getActiveRoomOrThrow(1L)).thenReturn(room);
        when(roomPartitionRoutingService.route(1L, "user1"))
                .thenThrow(new RoomPartitionRouteUnavailableException("assignment_unavailable"));

        mockMvc.perform(get("/api/rooms/1/ws-route")
                        .principal(authUser("user1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.reason").value("assignment_unavailable"))
                .andExpect(jsonPath("$.retryAfterMs").value(500));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId}/ws-route - assignment error 503")
    void getWebSocketRoute_assignmentError() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();
        when(roomService.getActiveRoomOrThrow(1L)).thenReturn(room);
        when(roomPartitionRoutingService.route(1L, "user1"))
                .thenThrow(new RoomPartitionRouteUnavailableException("assignment_error", new RuntimeException("redis")));

        mockMvc.perform(get("/api/rooms/1/ws-route")
                        .principal(authUser("user1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.reason").value("assignment_error"))
                .andExpect(jsonPath("$.retryAfterMs").value(500));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId} - 방 상세 조회")
    void getRoom_success() throws Exception {
        Room room = Room.builder()
                .id(1L).name("Test Room").ownerId("user1")
                .maxMembers(10).requiresApproval(false).build();

        when(roomService.getRoomOrThrow(1L)).thenReturn(room);
        when(roomMemberService.getApprovedMemberCount(1L)).thenReturn(5);

        mockMvc.perform(get("/api/rooms/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.currentMembers").value(5));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId} - 존재하지 않는 방 404")
    void getRoom_notFound() throws Exception {
        when(roomService.getRoomOrThrow(999L))
                .thenThrow(new ApiException(ErrorCode.ROOM_NOT_FOUND));

        mockMvc.perform(get("/api/rooms/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/rooms/{roomId} - 방장이 삭제")
    void deleteRoom_success() throws Exception {
        mockMvc.perform(delete("/api/rooms/1")
                        .principal(authUser("owner1")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/rooms/{roomId}/enter - 방 입장")
    void enterRoom_success() throws Exception {
        when(roomMemberService.joinIfNotExists(1L, "user1"))
                .thenReturn(new RoomMemberService.JoinResult(MemberStatus.APPROVED, false));

        mockMvc.perform(post("/api/rooms/1/enter")
                        .principal(authUser("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.requiresApproval").value(false));
    }

    private Authentication authUser(String username) {
        return new TestingAuthenticationToken(username, null, "ROLE_USER");
    }
}
