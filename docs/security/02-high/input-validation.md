# Input Validation Enhancement

## Severity: High

## Current Issues

1. XSS filter uses basic regex that can be bypassed
2. Missing validation annotations on DTOs
3. No protection against Mass Assignment

## Current XSS Filter

```java
content = content.replaceAll("<[^>]*>", "");  // Bypassable
```

Bypass examples:
- `<script/src=evil.js>` (malformed tag)
- `<img src=x onerror=alert(1)>` (event handlers)
- `\u003cscript\u003e` (Unicode encoding)

## Remediation

### 1. OWASP HTML Sanitizer

Add dependency:
```gradle
implementation 'com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1'
```

Implementation:
```java
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
    .allowElements("b", "i", "u", "em", "strong")  // Safe formatting only
    .toFactory();

public String sanitize(String input) {
    if (input == null) return null;
    return POLICY.sanitize(input);
}
```

### 2. DTO Validation

```java
public class RoomCreateRequest {
    @NotBlank(message = "Room name is required")
    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    @Pattern(regexp = "^[\\p{L}\\p{N}\\s-_]+$", message = "Invalid characters")
    private String name;
    
    @Size(max = 200, message = "Description too long")
    private String description;
}
```

### 3. Mass Assignment Protection

Use explicit DTOs instead of entity binding:
```java
@PostMapping
public RoomResponse create(@Valid @RequestBody RoomCreateRequest request,
                           @AuthenticationPrincipal String userId) {
    // ownerId comes from authentication, not request
    return roomService.create(request.getName(), userId);
}
```

## Verification

```bash
# XSS test
curl -X POST http://localhost:8080/api/rooms \
  -H "Content-Type: application/json" \
  -d '{"name":"<script>alert(1)</script>"}'
# Response should not contain <script>

# Validation test
curl -X POST http://localhost:8080/api/rooms \
  -H "Content-Type: application/json" \
  -d '{"name":"a"}'
# Expected: 400 Bad Request

# Mass assignment test
curl -X POST http://localhost:8080/api/rooms \
  -H "Content-Type: application/json" \
  -d '{"name":"test","ownerId":"hacker"}'
# ownerId should be ignored
```
