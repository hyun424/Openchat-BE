package io.hyun424.openchat.chat.room.workload.controller;

import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendationType;
import io.hyun424.openchat.chat.room.workload.service.RealtimeWorkloadClusterSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealtimeWorkloadInternalControllerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ConditionalTestConfig.class);

    private RealtimeWorkloadClusterSummaryService summaryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        summaryService = mock(RealtimeWorkloadClusterSummaryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RealtimeWorkloadInternalController(summaryService))
                .build();
    }

    @Test
    void controllerDisabledByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(RealtimeWorkloadInternalController.class));
    }

    @Test
    void controllerEnabledByProperty() {
        contextRunner
                .withPropertyValues("app.realtime-workload.summary-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RealtimeWorkloadInternalController.class));
    }

    @Test
    void summaryEndpointReturnsClusterSummary() throws Exception {
        when(summaryService.summary()).thenReturn(new RealtimeWorkloadClusterSummary(
                1_000,
                2,
                0,
                List.of(),
                100,
                40,
                60,
                0,
                500,
                300,
                500,
                0,
                3,
                4,
                List.of(),
                List.of(RealtimeWorkloadRecommendation.of(
                        RealtimeWorkloadRecommendationType.NO_ACTION,
                        "ok",
                        null,
                        null,
                        0,
                        10_000
                ))
        ));

        mockMvc.perform(get("/api/internal/realtime/workload/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeNodeCount").value(2))
                .andExpect(jsonPath("$.totalSessions").value(100))
                .andExpect(jsonPath("$.sendFailedDelta").value(3))
                .andExpect(jsonPath("$.reconnectSentDelta").value(4))
                .andExpect(jsonPath("$.recommendations[0].type").value("NO_ACTION"));
    }

    @Test
    void recommendationsEndpointReturnsRecommendationsOnly() throws Exception {
        when(summaryService.recommendations()).thenReturn(List.of(RealtimeWorkloadRecommendation.of(
                RealtimeWorkloadRecommendationType.WATCH,
                "watch threshold",
                1L,
                "node-1",
                7_000,
                7_000
        )));

        mockMvc.perform(get("/api/internal/realtime/workload/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WATCH"))
                .andExpect(jsonPath("$[0].roomId").value(1));
    }

    @Configuration
    @Import(RealtimeWorkloadInternalController.class)
    static class ConditionalTestConfig {
        @Bean
        RealtimeWorkloadClusterSummaryService summaryService() {
            return mock(RealtimeWorkloadClusterSummaryService.class);
        }
    }
}
