package io.hyun424.openchat.global.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * HTTP API Rate Limiting Filter
 * - 인증된 사용자: userId 기준
 * - 미인증 사용자: IP 기준
 * - X-Forwarded-For 스푸핑 방지: 신뢰할 수 있는 프록시만 허용
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;

    @Value("${ratelimit.api.limit:100}")
    private int apiLimit;

    @Value("${ratelimit.api.window-seconds:60}")
    private int apiWindowSeconds;

    @Value("${ratelimit.api.auth.limit:10}")
    private int authLimit;

    @Value("${ratelimit.api.auth.window-seconds:60}")
    private int authWindowSeconds;

    // 신뢰할 수 있는 프록시 CIDR 목록 (내부 네트워크)
    private static final Set<String> TRUSTED_PROXY_CIDRS = Set.of(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.0/8"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // WebSocket, 정적 리소스 제외
        String path = request.getRequestURI();
        if (shouldSkip(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        int limit = getLimitForPath(path);
        int windowSeconds = getWindowSecondsForPath(path);

        String normalizedPath = normalizePath(path);
        if (!rateLimiter.tryAcquire("api:" + key + ":" + normalizedPath, limit, windowSeconds)) {
            log.warn("[RATE_LIMIT] API limit exceeded: key={} path={}", key, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * path 변수 부분을 정규화하여 같은 엔드포인트를 하나의 rate limit 키로 통합
     * 예: /api/rooms/123 → /api/rooms/_
     */
    private static final java.util.regex.Pattern PATH_VAR_PATTERN =
            java.util.regex.Pattern.compile("/\\d+");

    private String normalizePath(String path) {
        return PATH_VAR_PATTERN.matcher(path).replaceAll("/_");
    }

    private boolean shouldSkip(String path) {
        return path.startsWith("/ws") ||
               path.startsWith("/actuator") ||
               path.contains(".");  // 정적 파일
    }

    /**
     * 엔드포인트별 Rate Limit 적용
     * - /api/auth/* : 10/분 (인증 관련 - 브루트포스 방지)
     * - 기타: 100/분
     */
    private int getLimitForPath(String path) {
        if (path.startsWith("/api/auth/")) {
            return authLimit;
        }
        return apiLimit;
    }

    private int getWindowSecondsForPath(String path) {
        if (path.startsWith("/api/auth/")) {
            return authWindowSeconds;
        }
        return apiWindowSeconds;
    }

    /**
     * Rate Limit 키 결정
     * - 인증 사용자: userId
     * - 미인증 사용자: 실제 클라이언트 IP (X-Forwarded-For 스푸핑 방지)
     */
    private String resolveKey(HttpServletRequest request) {
        // 인증된 사용자면 userId 사용
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }

        // 미인증이면 실제 클라이언트 IP 사용 (스푸핑 방지)
        return "ip:" + resolveClientIp(request);
    }

    /**
     * Security: X-Forwarded-For 스푸핑 방지
     * 신뢰할 수 있는 프록시에서 온 요청만 X-Forwarded-For 헤더 사용
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 신뢰할 수 없는 직접 연결이면 remoteAddr 사용
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // 신뢰할 수 있는 프록시에서 온 요청이면 X-Forwarded-For 파싱
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isEmpty()) {
            return remoteAddr;
        }

        // 가장 오른쪽의 신뢰할 수 없는 IP 사용 (rightmost non-trusted)
        String[] ips = xff.split(",");
        for (int i = ips.length - 1; i >= 0; i--) {
            String ip = ips[i].trim();
            if (!isTrustedProxy(ip)) {
                return ip;
            }
        }

        return remoteAddr;
    }

    /**
     * CIDR 기반 신뢰할 수 있는 프록시 체크
     */
    private boolean isTrustedProxy(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            byte[] addrBytes = addr.getAddress();

            for (String cidr : TRUSTED_PROXY_CIDRS) {
                if (isInCidr(addrBytes, cidr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            log.warn("[RATE_LIMIT] Invalid IP address: {}", ip);
        }
        return false;
    }

    /**
     * IP가 CIDR 범위에 포함되는지 확인
     */
    private boolean isInCidr(byte[] addr, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress cidrAddr = InetAddress.getByName(parts[0]);
            int prefixLen = Integer.parseInt(parts[1]);

            byte[] cidrBytes = cidrAddr.getAddress();
            if (addr.length != cidrBytes.length) {
                return false;
            }

            int fullBytes = prefixLen / 8;
            int remainingBits = prefixLen % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != cidrBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < addr.length) {
                int mask = 0xFF << (8 - remainingBits);
                return (addr[fullBytes] & mask) == (cidrBytes[fullBytes] & mask);
            }

            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
