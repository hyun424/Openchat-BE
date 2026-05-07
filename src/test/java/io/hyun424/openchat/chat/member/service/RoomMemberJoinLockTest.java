package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RoomMemberJoinLockTest {

    private final RoomMemberRepository roomMemberRepository = mock(RoomMemberRepository.class);
    private final RoomMemberJoinLock joinLock = new RoomMemberJoinLock(roomMemberRepository);

    @Test
    void acquireOrThrow_whenLockAcquired_doesNotThrow() {
        when(roomMemberRepository.acquireJoinLock(RoomMemberJoinLock.joinLockName(1L, "user1"), 3)).thenReturn(1);

        assertDoesNotThrow(() -> joinLock.acquireOrThrow(1L, "user1"));
    }

    @Test
    void acquireOrThrow_whenLockTimeout_throwsInvalidRequest() {
        when(roomMemberRepository.acquireJoinLock(RoomMemberJoinLock.joinLockName(1L, "user1"), 3)).thenReturn(0);

        ApiException exception = assertThrows(ApiException.class, () -> joinLock.acquireOrThrow(1L, "user1"));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void release_swallowsRepositoryFailure() {
        doThrow(new RuntimeException("release failed"))
                .when(roomMemberRepository).releaseJoinLock(RoomMemberJoinLock.joinLockName(1L, "user1"));

        assertDoesNotThrow(() -> joinLock.release(1L, "user1"));
    }

    @Test
    void joinLockName_whenUserIdIsLong_staysWithinMysqlLimit() {
        String longUserId = "loadtest-user-mixed-mixed-room-workload-smoke-single-100vu-small-5-3";

        String lockName = RoomMemberJoinLock.joinLockName(9L, longUserId);

        assertTrue(lockName.length() <= 64);
    }
}
