package io.hyun424.openchat.global.config.security;

import io.hyun424.openchat.auth.jwt.JwtAuthenticationFilter;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.auth.oauth.OAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.util.StringUtils;


@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})

                // Security Headers (강화)
                .headers(headers -> headers
                        .contentTypeOptions(opt -> {})  // X-Content-Type-Options: nosniff
                        .frameOptions(frame -> frame.deny())  // X-Frame-Options: DENY (클릭재킹 방지)
                        .xssProtection(xss -> xss.disable())  // 최신 브라우저는 자체 XSS 방어 사용
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; " +
                                        "script-src 'self'; " +
                                        "style-src 'self' 'unsafe-inline'; " +
                                        "img-src 'self' data: https:; " +
                                        "connect-src 'self' ws: wss:; " +
                                        "frame-ancestors 'none'"))
                        // HSTS: HTTPS 강제 (프로덕션에서 활성화)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))
                        // Referrer-Policy: 토큰 유출 방지
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )

                .authorizeHttpRequests(auth -> auth

                        // CORS preflight 허용 (필수)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Actuator 보안: health, prometheus만 허용, 나머지 차단
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()

                        // 인증 없이 허용
                        .requestMatchers(
                                "/api/auth/**",   // 로그인, 닉네임 체크
                                "/api/hotchat/**",
                                "/ws/**",
                                "/oauth2/**",     // OAuth2 로그인
                                "/login/**"       // OAuth2 로그인 페이지
                        ).permitAll()
                        .requestMatchers("/api/rooms/my").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/map", "/api/rooms/*").permitAll()

                        // 인증 필요
                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().permitAll()
                );

        // OAuth2 로그인 설정 (설정된 경우에만)
        if (StringUtils.hasText(googleClientId)) {
            log.info("[SECURITY] OAuth2 login enabled");
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler(oAuth2SuccessHandler)
                    .failureHandler((request, response, exception) -> {
                        log.warn("[OAUTH2] Login failed: {}", exception.getMessage());
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter()
                                .write("{\"message\":\"OAuth2 login failed\"}");
                    })
            );
        } else {
            log.warn("[SECURITY] OAuth2 disabled - google client-id not configured");
        }

        http
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter()
                                    .write("{\"message\":\"unauthorized\"}");
                        })
                )

                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

}
