package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/summary")
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.API)
public class RoomSummaryController {

    private final RoomSummaryAvailabilityService availabilityService;
    private final RoomSummaryQueryService queryService;
    private final AuthUserResolver authUserResolver;

    @GetMapping("/availability")
    public RoomSummaryAvailabilityResponse availability(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId
    ) {
        return availabilityService.availability(roomId, authUserResolver.extractUserId(authorization));
    }

    @GetMapping
    public RoomSummaryResponse getSummary(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId
    ) {
        return queryService.getSummary(roomId, authUserResolver.extractUserId(authorization));
    }
}
