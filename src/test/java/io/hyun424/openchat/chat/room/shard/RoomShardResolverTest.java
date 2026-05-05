package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.room.repository.RoomRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomShardResolverTest {

    @Test
    void resolveShardId_usesStoredShardId() {
        RoomRepository repository = mock(RoomRepository.class);
        when(repository.findShardIdById(10L)).thenReturn(Optional.of(5));
        RoomShardResolver resolver = new RoomShardResolver(properties(), repository);

        assertEquals(1, resolver.resolveShardId(10L));
    }

    @Test
    void resolveShardId_nullOrMissingFallbackZero() {
        RoomRepository repository = mock(RoomRepository.class);
        when(repository.findShardIdById(20L)).thenReturn(Optional.empty());
        RoomShardResolver resolver = new RoomShardResolver(properties(), repository);

        assertEquals(0, resolver.resolveShardId(null));
        assertEquals(0, resolver.resolveShardId(20L));
    }

    private RoomShardProperties properties() {
        return new RoomShardProperties(true, 4, Set.of(0), true, 10_000, 0.8, 500, 5_000, 30_000, 180_000);
    }
}
