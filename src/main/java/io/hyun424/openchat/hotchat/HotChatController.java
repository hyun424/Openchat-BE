package io.hyun424.openchat.hotchat;

import io.hyun424.openchat.global.response.ApiResponse;
import io.hyun424.openchat.hotchat.dto.HotChatResult;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@Validated
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/hotchat")
public class HotChatController {

    private final HotChatService hotChatService;
    private final RedisHealthState redisHealthState;

    @GetMapping
    public ApiResponse<List<HotChatResult>> getHotChats(
            @RequestParam(defaultValue = "5") @Min(1) @Max(60) int window,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit
    ) {
        // HotChat은 부가 기능이므로 Redis 장애가 전체 채팅 경험을 막지 않게 빈 목록으로 degrade한다.
        if (!redisHealthState.isUp()) {
            return ApiResponse.ok(List.of());
        }

        try {
            return ApiResponse.ok(
                    hotChatService.getHotChats(window, limit)
            );
        } catch (Exception e) {
            log.warn("[HOTCHAT FAIL] fallback empty", e);
            return ApiResponse.ok(List.of());
        }
    }
}
