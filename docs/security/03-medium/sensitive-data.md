# Sensitive Data Exposure Prevention

## Severity: Medium

## Areas of Concern

### 1. User Location Data

If storing user location:
```java
// BAD: Exposes exact coordinates to all users
public record RoomResponse(
    Long id,
    String name,
    Double latitude,  // Exact location
    Double longitude
) {}

// GOOD: Approximate location or no location
public record RoomResponse(
    Long id,
    String name,
    String area  // "Seoul, Korea" - general area only
) {}
```

### 2. User Profile Information

```java
// BAD: Exposes email to everyone
public record UserResponse(
    String id,
    String nickname,
    String email,      // Privacy issue
    String googleId    // Internal ID
) {}

// GOOD: Only necessary public info
public record UserResponse(
    String id,
    String nickname,
    String profileImage
) {}
```

### 3. Internal IDs

Don't expose internal database IDs when not necessary:
```java
// Use UUIDs for external references
@Column(name = "public_id")
private String publicId = UUID.randomUUID().toString();
```

### 4. Error Messages

```java
// BAD: Leaks internal info
throw new RuntimeException("User not found in table users with id: " + userId);

// GOOD: Generic message
throw new ApiException(ErrorCode.USER_NOT_FOUND);
```

## Implementation Checklist

- [ ] Review all DTO/Response classes
- [ ] Remove internal IDs from responses
- [ ] Approximate location data
- [ ] Sanitize error messages
- [ ] Audit logging access control
