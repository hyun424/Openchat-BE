package io.hyun424.openchat.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),

    // Room / Member
    NOT_JOINED(HttpStatus.CONFLICT, "방에 입장하지 않았습니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 입장한 방입니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 방입니다."),
    ROOM_ENDED(HttpStatus.GONE, "종료된 모임입니다."),
    ROOM_FULL(HttpStatus.CONFLICT, "방 인원이 가득 찼습니다."),
    PENDING_APPROVAL(HttpStatus.CONFLICT, "승인 대기 중입니다."),
    NOT_ROOM_OWNER(HttpStatus.FORBIDDEN, "방장만 수행할 수 있습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 멤버를 찾을 수 없습니다."),

    // User / Auth
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
