package io.hyun424.openchat.auth.controller;

import io.hyun424.openchat.auth.dto.LoginRequest;
import io.hyun424.openchat.auth.dto.LoginResponse;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtProvider jwtProvider;

    public AuthController(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String token = jwtProvider.createToken(
                request.getUserId(),
                request.getNickname()
        );
        return new LoginResponse(token);
    }
}
