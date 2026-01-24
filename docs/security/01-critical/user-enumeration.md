# User Enumeration via Nickname Check

## Severity: Critical

## Vulnerability Description

The `/api/auth/check-nickname` endpoint returns different responses for existing vs non-existing nicknames, allowing attackers to enumerate valid users.

## Current Code Location

`src/main/java/io/hyun424/openchat/auth/controller/AuthController.java:26`

```java
@GetMapping("/check-nickname")
public boolean checkNickname(@RequestParam String nickname) {
    return userService.isNicknameAvailable(nickname);
}
```

## Attack Scenario

1. Attacker scripts requests to check-nickname endpoint
2. Iterates through common usernames/nicknames
3. Builds list of valid users for targeted attacks
4. Uses list for credential stuffing or social engineering

```bash
for nickname in admin user1 john alice; do
  result=$(curl -s "http://target/api/auth/check-nickname?nickname=$nickname")
  echo "$nickname: $result"
done
```

## Remediation

### 1. Rate Limiting (Primary)

```java
@GetMapping("/check-nickname")
@RateLimited(limit = 5, window = 60)  // 5 requests per minute
public boolean checkNickname(@RequestParam String nickname) {
    return userService.isNicknameAvailable(nickname);
}
```

### 2. Consistent Response Timing

```java
@GetMapping("/check-nickname")
public boolean checkNickname(@RequestParam String nickname) {
    long start = System.currentTimeMillis();
    boolean available = userService.isNicknameAvailable(nickname);
    
    // Add random delay to prevent timing attacks
    long elapsed = System.currentTimeMillis() - start;
    long minDelay = 100; // ms
    if (elapsed < minDelay) {
        Thread.sleep(minDelay - elapsed + (long)(Math.random() * 50));
    }
    
    return available;
}
```

### 3. CAPTCHA for Repeated Requests

Implement CAPTCHA after 3 consecutive requests.

## Verification Test

```bash
# Should be rate limited after 5 requests
for i in {1..10}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:8080/api/auth/check-nickname?nickname=test$i"
done
# Expected: First 5 return 200, rest return 429

# Timing attack check
time curl -s "http://localhost:8080/api/auth/check-nickname?nickname=existinguser"
time curl -s "http://localhost:8080/api/auth/check-nickname?nickname=nonexistent"
# Response times should be similar
```

## References

- OWASP: Authentication Cheat Sheet - Prevent User Enumeration
- CWE-204: Observable Response Discrepancy
