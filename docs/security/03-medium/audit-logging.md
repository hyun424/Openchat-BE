# Security Audit Logging

## Severity: Medium

## Events to Log

### Authentication Events
- Login success/failure
- Token refresh
- Logout
- Password change
- OAuth provider errors

### Authorization Events
- Access denied
- Role changes
- Permission changes

### Data Events
- Room creation/deletion
- User ban/unban
- Sensitive data access

## Implementation

### Audit Log Entity

```java
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue
    private Long id;
    
    private Instant timestamp;
    private String eventType;
    private String userId;
    private String ipAddress;
    private String userAgent;
    private String details;
    private String outcome;  // SUCCESS, FAILURE
}
```

### Audit Service

```java
@Service
@RequiredArgsConstructor
public class AuditService {
    
    private final AuditLogRepository repository;
    
    public void logAuthEvent(String eventType, String userId, 
                             HttpServletRequest request, String outcome) {
        AuditLog log = AuditLog.builder()
            .timestamp(Instant.now())
            .eventType(eventType)
            .userId(userId)
            .ipAddress(resolveClientIp(request))
            .userAgent(request.getHeader("User-Agent"))
            .outcome(outcome)
            .build();
        
        repository.save(log);
    }
}
```

### Usage

```java
@PostMapping("/login")
public TokenResponse login(@RequestBody LoginRequest request,
                          HttpServletRequest httpRequest) {
    try {
        TokenResponse response = authService.login(request);
        auditService.logAuthEvent("LOGIN", request.getUserId(), 
                                  httpRequest, "SUCCESS");
        return response;
    } catch (Exception e) {
        auditService.logAuthEvent("LOGIN", request.getUserId(), 
                                  httpRequest, "FAILURE");
        throw e;
    }
}
```

## Retention Policy

- Keep auth logs for 90 days
- Keep access denied logs for 30 days
- Archive to cold storage after retention period
