package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.entity.RoomMember;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PendingMemberMapperTest {

    private final PendingMemberMapper mapper = new PendingMemberMapper();

    @Test
    void toPendingMember_mapsNicknameAndJoinedAt() {
        Instant joinedAt = Instant.now();
        RoomMember member = RoomMember.builder()
                .roomId(1L)
                .userId("user1")
                .joinedAt(joinedAt)
                .status(MemberStatus.PENDING)
                .build();

        RoomMemberService.PendingMember pending = mapper.toPendingMember(new Object[]{member, "tester"});

        assertEquals("user1", pending.userId());
        assertEquals("tester", pending.nickname());
        assertEquals(joinedAt, pending.joinedAt());
    }

    @Test
    void toPendingMember_whenNicknameMissing_usesFallback() {
        RoomMember member = RoomMember.builder()
                .roomId(1L)
                .userId("user1")
                .joinedAt(Instant.now())
                .status(MemberStatus.PENDING)
                .build();

        RoomMemberService.PendingMember pending = mapper.toPendingMember(new Object[]{member, null});

        assertEquals("알 수 없음", pending.nickname());
    }
}
