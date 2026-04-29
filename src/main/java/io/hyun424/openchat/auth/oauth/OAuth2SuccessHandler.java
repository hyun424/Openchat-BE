package io.hyun424.openchat.auth.oauth;

import io.hyun424.openchat.auth.entity.User;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.auth.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtProvider jwtProvider;

    @Value("${app.oauth.success-redirect-url:http://localhost:3000/oauth/callback}")
    private String successRedirectUrl;

    @Value("${app.oauth.nickname-setup-url:http://localhost:3000/nickname-setup}")
    private String nicknameSetupUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String googleId = oauth2User.getAttribute("sub");
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String picture = oauth2User.getAttribute("picture");

        log.info("[OAUTH SUCCESS] googleId={}, email={}", googleId, email);

        Optional<User> existingUser = userService.findById(googleId);

        if (existingUser.isPresent()) {
            // 기존 유저 → 바로 JWT 발급
            User user = existingUser.get();
            userService.updateLastLogin(user.getId());

            String token = jwtProvider.createToken(user.getId(), user.getNickname());
            log.info("[OAUTH] Existing user login: {}", user.getNickname());

            // Security: Fragment(#)로 토큰 전달 - 서버 로그/Referrer에 노출 안 됨
            String redirectUrl = successRedirectUrl + "#token=" + token;
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
        } else {
            // 신규 유저 → 닉네임 설정 페이지로
            String tempToken = jwtProvider.createTempToken(googleId, email, name, picture);
            log.info("[OAUTH] New user, redirect to nickname setup: {}", email);

            // Security: Fragment(#)로 임시 토큰 전달
            String redirectUrl = nicknameSetupUrl + "#token=" + tempToken;
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
        }
    }
}
