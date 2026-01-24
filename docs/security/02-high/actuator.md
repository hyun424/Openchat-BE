# Actuator Endpoint Security

## Severity: High

## Vulnerability Description

Spring Boot Actuator endpoints expose sensitive information:
- `/actuator/env` - Environment variables, secrets
- `/actuator/heapdump` - Memory dump with credentials
- `/actuator/mappings` - API routes

## Remediation

### Production Configuration

```properties
# Disable all by default
management.endpoints.enabled-by-default=false

# Enable only health
management.endpoint.health.enabled=true
management.endpoints.web.exposure.include=health

# Hide details
management.endpoint.health.show-details=never
```

### Security Configuration

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/health").permitAll()
    .requestMatchers("/actuator/**").denyAll()
    // or require admin role
    // .requestMatchers("/actuator/**").hasRole("ADMIN")
)
```

## Verification

```bash
# All actuator endpoints except health should be blocked
curl http://localhost:8080/actuator
# 404 or 403

curl http://localhost:8080/actuator/env
# 404 or 403

curl http://localhost:8080/actuator/heapdump
# 404 or 403

curl http://localhost:8080/actuator/health
# 200 (only this should work)
```
