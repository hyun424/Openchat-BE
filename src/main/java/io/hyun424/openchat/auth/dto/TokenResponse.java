package io.hyun424.openchat.auth.dto;

import lombok.Getter;

@Getter
public class TokenResponse {

    private final String token;
    private final String nickname;

    public TokenResponse(String token, String nickname) {
        this.token = token;
        this.nickname = nickname;
    }
}
