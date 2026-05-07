package io.hyun424.openchat.chat.member.service;

import io.hyun424.openchat.chat.member.entity.RoomMember;

class PendingMemberMapper {

    RoomMemberService.PendingMember toPendingMember(Object[] row) {
        RoomMember member = (RoomMember) row[0];
        String nickname = row[1] != null ? (String) row[1] : "알 수 없음";
        return new RoomMemberService.PendingMember(member.getUserId(), nickname, member.getJoinedAt());
    }
}
