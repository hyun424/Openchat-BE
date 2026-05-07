package io.hyun424.openchat.chat.room.workload.controller;

import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadClusterSummary;
import io.hyun424.openchat.chat.room.workload.dto.RealtimeWorkloadRecommendation;
import io.hyun424.openchat.chat.room.workload.service.RealtimeWorkloadClusterSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/internal/realtime/workload")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.realtime-workload.summary-enabled", havingValue = "true")
public class RealtimeWorkloadInternalController {

    private final RealtimeWorkloadClusterSummaryService summaryService;

    @GetMapping("/summary")
    public ResponseEntity<RealtimeWorkloadClusterSummary> summary() {
        return ResponseEntity.ok(summaryService.summary());
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<RealtimeWorkloadRecommendation>> recommendations() {
        return ResponseEntity.ok(summaryService.recommendations());
    }
}
