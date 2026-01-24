# CORS Configuration Security

## Severity: High

## Current Issues

1. Potential null Origin acceptance
2. Missing origin validation
3. Wildcard with credentials risk

## Secure Configuration

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    
    // Explicit allowed origins - no wildcards with credentials
    List<String> origins = Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(o -> !o.equals("*") && !o.equalsIgnoreCase("null"))
        .toList();
    
    if (origins.isEmpty()) {
        throw new IllegalStateException("No valid CORS origins configured");
    }
    
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setExposedHeaders(List.of("Authorization"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);
    
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    
    return source;
}
```

## Key Rules

1. **Never allow null Origin** - Used by file:// and data: schemes
2. **No wildcard with credentials** - `Access-Control-Allow-Origin: *` with `Access-Control-Allow-Credentials: true` is blocked by browsers but indicates misconfiguration
3. **Validate Origin strictly** - Don't use substring matching

## Verification

```bash
# Blocked origin
curl -H "Origin: http://evil.com" -I http://localhost:8080/api/rooms
# Should NOT have Access-Control-Allow-Origin header

# null Origin (file://, data: attacks)
curl -H "Origin: null" -I http://localhost:8080/api/rooms
# Should NOT have Access-Control-Allow-Origin header

# Subdomain attack
curl -H "Origin: http://localhost.evil.com" -I http://localhost:8080/api/rooms
# Should NOT have Access-Control-Allow-Origin header

# Valid origin
curl -H "Origin: http://localhost:3000" -I http://localhost:8080/api/rooms
# Should have Access-Control-Allow-Origin: http://localhost:3000
```
