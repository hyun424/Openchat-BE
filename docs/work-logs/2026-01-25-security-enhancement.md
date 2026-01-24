# Backend Security Enhancement

**날짜**: 2026-01-25  
**작업자**: Claude  
**상태**: 완료

---

## 개요

OpenChat 백엔드 보안 강화 작업. Critical, High, Medium 우선순위로 분류된 16개 취약점 중 Critical/High 항목 구현 완료.

---

## 변경 파일

| 파일 | 변경 내용 |
|------|----------|
| `OAuth2SuccessHandler.java` | 토큰 전달 방식 URL → HttpOnly 쿠키 |
| `DevAuthController.java` | `@Profile`에서 `default` 제거 |
| `RateLimitFilter.java` | X-Forwarded-For 스푸핑 방지, 엔드포인트별 제한 |
| `SecurityConfig.java` | HSTS, Referrer-Policy, Actuator 차단 |
| `CorsConfig.java` | null Origin 차단, 도메인 검증 |
| `ChatWebSocketHandler.java` | 메시지 크기 제한, 토큰 재검증, 세션 타임아웃 |
| `RoomMember.java` | 유니크 제약조건 추가 |
| `RoomMemberService.java` | Race condition 예외 처리 |
| `LoginRequest.java` | 닉네임 패턴 검증 (영어, 숫자, 한글) |
| `NicknameRequest.java` | 닉네임 패턴 검증 (영어, 숫자, 한글) |
| `build.gradle` | OWASP HTML Sanitizer 의존성 추가 |
| `application.properties` | Rate limit, Cookie, Actuator 설정 |

---

## 구현 상세

### 1. Critical - OAuth 토큰 URL 노출 수정

**문제**: 토큰이 URL 쿼리 파라미터로 노출되어 브라우저 히스토리, 로그, Referrer 헤더에 유출 가능

**해결**:
```java
// Before
String redirectUrl = successRedirectUrl + "?token=" + token;

// After - HttpOnly 쿠키 사용
Cookie cookie = new Cookie("access_token", token);
cookie.setHttpOnly(true);
cookie.setSecure(secureCookie);
cookie.setPath("/");
// SameSite=Lax 헤더 추가
```

### 2. Critical - 개발 엔드포인트 분리

**문제**: `DevAuthController`가 `default` 프로파일에서 활성화되어 프로덕션 노출 위험

**해결**:
```java
// Before
@Profile({"dev", "local", "default"})

// After
@Profile({"dev", "local"})  // default 제거
```

### 3. High - X-Forwarded-For 스푸핑 방지

**문제**: 공격자가 X-Forwarded-For 헤더를 위조하여 Rate Limit 우회 가능

**해결**:
```java
// 신뢰할 수 있는 프록시 CIDR 정의
private static final Set<String> TRUSTED_PROXY_CIDRS = Set.of(
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8"
);

// 신뢰할 수 없는 소스에서는 remoteAddr 사용
if (!isTrustedProxy(remoteAddr)) {
    return remoteAddr;
}
```

### 4. High - Security Headers 강화

**추가된 헤더**:
- `Strict-Transport-Security`: HTTPS 강제 (31536000초)
- `Referrer-Policy`: strict-origin-when-cross-origin
- `Content-Security-Policy`: connect-src 'self' ws: wss: 추가
- Actuator 엔드포인트: `/actuator/health`만 허용, 나머지 차단

### 5. High - WebSocket 보안 강화

| 항목 | 설정값 |
|------|--------|
| 메시지 크기 제한 | 10KB |
| 토큰 재검증 주기 | 5분 |
| 세션 최대 유지 시간 | 4시간 |

```java
// 메시지 크기 체크
if (messageSize > MAX_MESSAGE_SIZE_BYTES) {
    sendError(session, "Message too large (max 10KB)");
    return;
}

// 토큰 주기적 재검증
if (!validateTokenPeriodically(session)) {
    session.close(new CloseStatus(4001, "Token expired"));
    return;
}
```

### 6. High - CORS 보안 강화

**차단 항목**:
- `null` Origin (file://, data: 스킴 공격)
- 와일드카드 `*` (credentials와 함께 사용 불가)
- 유효하지 않은 URL 형식

```java
private boolean isValidOrigin(String origin) {
    if (origin.equalsIgnoreCase("null")) return false;  // null 차단
    if (origin.equals("*")) return false;  // 와일드카드 차단
    if (!origin.startsWith("http://") && !origin.startsWith("https://")) return false;
    return true;
}
```

### 7. High - Race Condition 방지

**문제**: `join()` 메서드에서 TOCTOU 취약점으로 중복 멤버십 생성 가능

**해결**: DB 유니크 제약조건 + 예외 처리
```java
// Entity
@UniqueConstraint(name = "uk_room_user_active_member", 
                  columnNames = {"room_id", "user_id", "left_at"})

// Service
try {
    roomMemberRepository.save(RoomMember.join(roomId, userId, requiresApproval));
} catch (DataIntegrityViolationException e) {
    throw new ApiException(ErrorCode.ALREADY_JOINED);
}
```

### 8. High - Input Validation 강화

**닉네임 패턴**: 영어, 숫자, 한글만 허용
```java
@Pattern(regexp = "^[a-zA-Z0-9가-힣]+$", 
         message = "닉네임은 영어, 숫자, 한글만 사용할 수 있습니다.")
```

---

## 설정 추가 (application.properties)

```properties
# Rate Limiting - Auth 엔드포인트 별도 제한
ratelimit.api.auth.limit=10
ratelimit.api.auth.window-seconds=60

# Cookie Security
app.cookie.secure=${env.COOKIE_SECURE:false}
app.cookie.domain=${env.COOKIE_DOMAIN:}
app.cookie.max-age-seconds=43200

# Actuator Security
management.endpoints.enabled-by-default=false
management.endpoint.health.enabled=true
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

---

## 문서화

`docs/security/` 디렉토리에 취약점별 상세 문서 생성:

```
docs/security/
├── README.md
├── 01-critical/
│   ├── oauth-token-exposure.md
│   ├── dev-endpoint.md
│   ├── user-enumeration.md
│   └── websocket-token.md
├── 02-high/
│   ├── rate-limiting.md
│   ├── x-forwarded-for.md
│   ├── input-validation.md
│   ├── security-headers.md
│   ├── websocket-limits.md
│   ├── race-condition.md
│   ├── cors.md
│   └── actuator.md
└── 03-medium/
    ├── jwt-refresh-token.md
    ├── token-blacklist.md
    ├── sensitive-data.md
    └── audit-logging.md
```

---

## 테스트 결과

```bash
./gradlew test
# BUILD SUCCESSFUL in 8s
```

---

## 검증 방법

```bash
# Rate Limit 테스트
for i in {1..110}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/rooms
done | grep 429

# X-Forwarded-For 스푸핑 방지 확인
for i in {1..110}; do
  curl -H "X-Forwarded-For: 192.168.1.$i" -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/rooms
done
# 전부 429 반환해야 함 (스푸핑 차단)

# CORS null Origin 차단
curl -H "Origin: null" -I http://localhost:8080/api/rooms
# Access-Control-Allow-Origin 없어야 함

# Security Headers
curl -I http://localhost:8080/api/rooms | grep -E "X-Frame-Options|Strict-Transport-Security|Referrer-Policy"

# Actuator 차단
curl http://localhost:8080/actuator/env  # 403 또는 404
curl http://localhost:8080/actuator/health  # 200 (허용)
```

---

## 미구현 (Medium - 추후 작업)

| 항목 | 설명 |
|------|------|
| JWT Refresh Token | Access(15분) + Refresh(7일) 분리 |
| Token Blacklist | Redis SET으로 로그아웃/강제만료 |
| Sensitive Data | 응답 필드 필터링 |
| Audit Logging | 보안 이벤트 로깅 |

상세 구현 가이드는 `docs/security/03-medium/` 참조
