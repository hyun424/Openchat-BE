package io.hyun424.openchat.hotchat;

import io.hyun424.openchat.global.response.ApiResponse;
import io.hyun424.openchat.hotchat.dto.HotChatResult;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/hotchat")
public class HotChatController {

    private final HotChatService hotChatService;
    private final RedisHealthState redisHealthState;

    @GetMapping
    public ApiResponse<List<HotChatResult>> getHotChats(
            @RequestParam(defaultValue = "5") int window,
            @RequestParam(defaultValue = "5") int limit
    ) {
        // 🔥 핵심: Redis 상태 가드
        if (!redisHealthState.isUp()) {
            return ApiResponse.ok(List.of());
        }

        try {
            return ApiResponse.ok(
                    hotChatService.getHotChats(window, limit)
            );
        } catch (Exception e) {
            // Redis는 켜져 있지만 데이터/버킷 문제 등
            // 핫챗만 degrade
            log.warn("[HOTCHAT FAIL] fallback empty", e);
            return ApiResponse.ok(List.of());
        }
    }
}
