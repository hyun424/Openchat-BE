package io.hyun424.openchat.chat.room.partition.controller;

import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectOperations;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoomPartitionInternalControllerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ConditionalTestConfig.class);

    private MockMvc mockMvc;

    @Mock
    private RoomPartitionStateOperations stateOperations;

    @Mock
    private RoomPartitionReconnectOperations reconnectOperations;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RoomPartitionInternalController(stateOperations, reconnectOperations))
                .build();
    }

    @Test
    @DisplayName("internal partition admin controller는 기본 비활성화")
    void internalControllerDisabledByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(RoomPartitionInternalController.class));
    }

    @Test
    @DisplayName("property 활성화 시 internal partition admin controller 등록")
    void internalControllerEnabledByProperty() {
        contextRunner
                .withPropertyValues("app.room-partition.admin-api-enabled=true")
                .run(context ->
                        assertThat(context).hasSingleBean(RoomPartitionInternalController.class));
    }

    @Test
    @DisplayName("POST /scale-up - target partition count와 updatedBy 전달")
    void scaleUpCallsStateOperations() throws Exception {
        mockMvc.perform(post("/api/internal/rooms/10/partitions/scale-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPartitionCount":4,"updatedBy":"local-smoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(10))
                .andExpect(jsonPath("$.operation").value("scale-up"))
                .andExpect(jsonPath("$.accepted").value(true));

        verify(stateOperations).scaleUp(10L, 4, "local-smoke");
    }

    @Test
    @DisplayName("POST /drain - draining partition 목록을 정렬된 Set으로 전달")
    void startDrainCallsStateOperations() throws Exception {
        mockMvc.perform(post("/api/internal/rooms/10/partitions/drain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPartitionCount":2,"drainingPartitions":[3,2],"updatedBy":"local-smoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(10))
                .andExpect(jsonPath("$.operation").value("drain"))
                .andExpect(jsonPath("$.accepted").value(true));

        verify(stateOperations).startDrain(10L, 2, java.util.Set.of(2, 3), "local-smoke");
    }

    @Test
    @DisplayName("POST /drain/reconnect - Redis control command publish 요청")
    void reconnectDrainingCallsReconnectOperations() throws Exception {
        when(reconnectOperations.reconnectDraining(eq(10L), eq("scale_down"), eq(750L), eq(25)))
                .thenReturn(new RoomPartitionReconnectOperations.RoomPartitionReconnectResult(10L, "scale_down", true, 2));

        mockMvc.perform(post("/api/internal/rooms/10/partitions/drain/reconnect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"scale_down","retryAfterMs":750,"limit":25}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(10))
                .andExpect(jsonPath("$.operation").value("drain-reconnect"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.publishedCommands").value(2));

        verify(reconnectOperations).reconnectDraining(10L, "scale_down", 750L, 25);
    }

    @Test
    @DisplayName("POST /drain/complete - drain 완료 처리")
    void completeDrainCallsStateOperations() throws Exception {
        mockMvc.perform(post("/api/internal/rooms/10/partitions/drain/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updatedBy":"local-smoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(10))
                .andExpect(jsonPath("$.operation").value("drain-complete"))
                .andExpect(jsonPath("$.accepted").value(true));

        verify(stateOperations).completeDrain(10L, "local-smoke");
    }

    @Test
    @DisplayName("POST /drain/reconnect - optional body 값 기본값 적용")
    void reconnectDrainingUsesDefaults() throws Exception {
        when(reconnectOperations.reconnectDraining(eq(10L), eq("scale_down"), eq(500L), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new RoomPartitionReconnectOperations.RoomPartitionReconnectResult(10L, "scale_down", true, 1));

        mockMvc.perform(post("/api/internal/rooms/10/partitions/drain/reconnect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCommands").value(1));

        verify(reconnectOperations).reconnectDraining(10L, "scale_down", 500L, null);
    }

    @Configuration
    @Import(RoomPartitionInternalController.class)
    static class ConditionalTestConfig {

        @Bean
        RoomPartitionStateOperations stateOperations() {
            return mock(RoomPartitionStateOperations.class);
        }

        @Bean
        RoomPartitionReconnectOperations reconnectOperations() {
            return mock(RoomPartitionReconnectOperations.class);
        }
    }
}
