# Security Headers Configuration

## Severity: High

## Required Headers

| Header | Value | Purpose |
|--------|-------|---------|
| Content-Security-Policy | `default-src 'self'` | Prevent XSS |
| X-Frame-Options | `DENY` | Prevent clickjacking |
| X-Content-Type-Options | `nosniff` | Prevent MIME sniffing |
| Strict-Transport-Security | `max-age=31536000; includeSubDomains` | Force HTTPS |
| Referrer-Policy | `strict-origin-when-cross-origin` | Control referrer leakage |

## Implementation

```java
.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives("default-src 'self'; " +
                         "script-src 'self'; " +
                         "style-src 'self' 'unsafe-inline'; " +
                         "img-src 'self' data:; " +
                         "frame-ancestors 'none'"))
    .frameOptions(frame -> frame.deny())
    .httpStrictTransportSecurity(hsts -> hsts
        .maxAgeInSeconds(31536000)
        .includeSubDomains(true))
    .referrerPolicy(ref -> ref
        .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
    .contentTypeOptions(cto -> {})  // nosniff
)
```

## Verification

```bash
curl -I http://localhost:8080/api/rooms

# Check headers
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Content-Security-Policy: default-src 'self'; ...
Referrer-Policy: strict-origin-when-cross-origin

# Also verify on error pages
curl -I http://localhost:8080/nonexistent
# Should have same security headers
```
