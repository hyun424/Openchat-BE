# X-Forwarded-For Spoofing Prevention

## Severity: High

## Vulnerability Description

The current implementation trusts `X-Forwarded-For` header from any source, allowing attackers to bypass IP-based rate limiting by spoofing their IP address.

## Current Code

```java
private String resolveKey(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isEmpty()) {
        ip = request.getRemoteAddr();
    } else {
        ip = ip.split(",")[0].trim();  // Trusts first IP - VULNERABLE
    }
    return "ip:" + ip;
}
```

## Attack Scenario

```bash
# Attacker spoofs different IPs to bypass rate limit
for i in {1..1000}; do
  curl -H "X-Forwarded-For: 192.168.1.$((i % 256))" \
    http://target/api/rooms
done
# All requests succeed - rate limit bypassed
```

## Remediation

### Trusted Proxy Validation

```java
private static final Set<String> TRUSTED_PROXY_CIDRS = Set.of(
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "127.0.0.1/32"
);

private String resolveClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    
    // Only trust X-Forwarded-For from known proxies
    if (!isTrustedProxy(remoteAddr)) {
        return remoteAddr;
    }
    
    String xff = request.getHeader("X-Forwarded-For");
    if (xff == null || xff.isEmpty()) {
        return remoteAddr;
    }
    
    // Use rightmost non-trusted IP
    String[] ips = xff.split(",");
    for (int i = ips.length - 1; i >= 0; i--) {
        String ip = ips[i].trim();
        if (!isTrustedProxy(ip)) {
            return ip;
        }
    }
    
    return remoteAddr;
}
```

## Verification

```bash
# From untrusted source, X-Forwarded-For should be ignored
for i in {1..110}; do
  curl -H "X-Forwarded-For: 192.168.1.$i" \
    -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:8080/api/rooms
done
# Expected: Returns 429 after limit - spoofing blocked
```
