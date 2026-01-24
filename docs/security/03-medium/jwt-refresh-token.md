# JWT Refresh Token Implementation

## Severity: Medium

## Current State

Single token with long expiration (12 hours default). Issues:
- Token theft gives long-term access
- No way to revoke access without waiting for expiration
- Users must re-authenticate frequently if short expiration

## Recommended Architecture

```
Access Token:  15 minutes, used for API calls
Refresh Token: 7 days, used only to get new access tokens
```

## Implementation Design

### Token Pair Response

```java
public record TokenPairResponse(
    String accessToken,
    String refreshToken,
    long accessExpiresIn,
    long refreshExpiresIn
) {}
```

### Refresh Endpoint

```java
@PostMapping("/refresh")
public TokenPairResponse refresh(@CookieValue("refresh_token") String refreshToken) {
    if (!jwtProvider.validateRefreshToken(refreshToken)) {
        throw new ApiException(ErrorCode.INVALID_TOKEN);
    }
    
    String userId = jwtProvider.getUserId(refreshToken);
    // Verify user still exists and is active
    User user = userService.findByIdOrThrow(userId);
    
    return jwtProvider.createTokenPair(user.getId(), user.getNickname());
}
```

### Storage

- Access token: Memory/localStorage (short-lived)
- Refresh token: HttpOnly cookie (secure)

## Benefits

1. Short access token limits damage from theft
2. Refresh token rotation enables revocation
3. Better UX - less re-authentication
