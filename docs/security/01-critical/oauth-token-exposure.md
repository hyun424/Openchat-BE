# OAuth Token URL Exposure

## Severity: Critical

## Vulnerability Description

OAuth tokens are exposed in URL query parameters during the redirect flow. This exposes tokens in:
- Browser history
- Server access logs
- Referrer headers
- Proxy logs

## Current Code Location

`src/main/java/io/hyun424/openchat/auth/oauth/OAuth2SuccessHandler.java:56`

```java
String redirectUrl = successRedirectUrl + "?token=" + token;
```

## Attack Scenario

1. User authenticates via Google OAuth
2. Token is appended to redirect URL: `https://app.com/callback?token=eyJ...`
3. Token leaks via:
   - Browser history (accessible to other users on shared computers)
   - Referrer header when clicking external links
   - Server logs if frontend makes requests with full URL

## Remediation

### Solution: HttpOnly Cookie

```java
@Override
public void onAuthenticationSuccess(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Authentication authentication) throws IOException {
    // ... existing code to get token ...
    
    // Set token as HttpOnly cookie
    Cookie cookie = new Cookie("access_token", token);
    cookie.setHttpOnly(true);
    cookie.setSecure(true);  // HTTPS only
    cookie.setPath("/");
    cookie.setMaxAge(3600);  // 1 hour
    cookie.setAttribute("SameSite", "Lax");
    response.addCookie(cookie);
    
    // Redirect without token in URL
    getRedirectStrategy().sendRedirect(request, response, successRedirectUrl);
}
```

## Verification Test

```bash
# After OAuth login, check redirect URL
# Should NOT contain ?token= parameter
# Should have Set-Cookie header with HttpOnly flag
curl -I "http://localhost:8080/oauth2/authorization/google" -c cookies.txt
```

## References

- OWASP: Session Management Cheat Sheet
- RFC 6749: OAuth 2.0 Security Considerations
