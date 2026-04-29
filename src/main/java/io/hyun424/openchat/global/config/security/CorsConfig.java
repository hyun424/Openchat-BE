package io.hyun424.openchat.global.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CORS 보안 설정
 * Security:
 * - null Origin 차단 (file://, data: 스킴 공격 방지)
 * - 와일드카드(*) Origin 차단
 * - 명시적 도메인만 허용
 */
@Slf4j
@Configuration
public class CorsConfig {

    /**
     * 허용할 Origin 목록 (콤마로 구분)
     * 개발: http://localhost:3000
     * 운영: https://yourdomain.com
     */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Security: Origin 검증 및 필터링
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(this::isValidOrigin)
                .collect(Collectors.toList());

        if (origins.isEmpty()) {
            log.warn("[CORS] No valid origins configured, using localhost:3000 as fallback");
            origins = List.of("http://localhost:3000");
        }

        log.info("[CORS] Allowed origins: {}", origins);
        config.setAllowedOrigins(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // preflight 캐시 1시간

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);

        return source;
    }

    /**
     * Security: Origin 유효성 검증
     * - null Origin 차단
     * - 와일드카드 차단
     * - 유효한 URL 형식만 허용
     */
    private boolean isValidOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }

        // null Origin 차단 (file://, data: 공격 방지)
        if (origin.equalsIgnoreCase("null")) {
            log.warn("[CORS] Blocked null origin configuration");
            return false;
        }

        // 와일드카드 차단 (credentials와 함께 사용 불가)
        if (origin.equals("*")) {
            log.warn("[CORS] Blocked wildcard origin (incompatible with credentials)");
            return false;
        }

        // 유효한 URL 형식 확인
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            log.warn("[CORS] Invalid origin format: {}", origin);
            return false;
        }

        return true;
    }
}
