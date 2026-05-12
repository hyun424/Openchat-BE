package io.hyun424.openchat.chat.room.read;

import io.hyun424.openchat.auth.resolver.AuthUserResolver;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/read-position")
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.API)
public class RoomReadPositionController {

    private final RoomReadPositionService readPositionService;
    private final AuthUserResolver authUserResolver;

    @GetMapping
    public RoomReadPositionResponse get(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId
    ) {
        return readPositionService.get(roomId, authUserResolver.extractUserId(authorization));
    }

    @PutMapping
    public RoomReadPositionResponse update(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody RoomReadPositionUpdateRequest request
    ) {
        return readPositionService.update(roomId, authUserResolver.extractUserId(authorization), request.lastReadMessageId());
    }
}
