package io.hyun424.openchat.auth.dto;

import lombok.Getter;

@Getter
public class LoginRequest {
    private String userId;
    private String nickname;
}
