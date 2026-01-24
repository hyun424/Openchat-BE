# Development Endpoint in Production

## Severity: Critical

## Vulnerability Description

The `DevAuthController` allows arbitrary JWT token generation without OAuth authentication. It's enabled in the `default` profile, which means it's active in production if no profile is explicitly set.

## Current Code Location

`src/main/java/io/hyun424/openchat/auth/controller/DevAuthController.java:21`

```java
@Profile({"dev", "local", "default"})
public class DevAuthController {
```

## Attack Scenario

1. Attacker discovers `/api/auth/login` endpoint
2. Sends POST request with arbitrary userId and nickname
3. Receives valid JWT token
4. Uses token to impersonate any user or create fake accounts

```bash
curl -X POST http://production-server/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin","nickname":"Administrator"}'
```

## Remediation

Remove `default` from the profile list:

```java
@Profile({"dev", "local"})  // Removed "default"
public class DevAuthController {
```

And ensure production deployments explicitly set:
```properties
spring.profiles.active=prod
```

## Verification Test

```bash
# Production profile - should return 404
java -jar app.jar --spring.profiles.active=prod
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId":"test","nickname":"Test"}'
# Expected: 404 Not Found

# Dev profile - should work
java -jar app.jar --spring.profiles.active=dev
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId":"test","nickname":"Test"}'
# Expected: 200 with token
```

## References

- OWASP: Authentication Cheat Sheet
- Spring Security: Profile-based Configuration
