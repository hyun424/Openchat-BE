package io.hyun424.openchat.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PICTURE = "picture";

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_TEMP = "temp";

    private static final long TEMP_TOKEN_EXPIRATION_MS = 10 * 60 * 1000; // 10분

    private final String secretKey;
    private final long expirationMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secretKey,
            @Value("${jwt.expiration-ms:43200000}") long expirationMs  // default 12h
    ) {
        this.secretKey = secretKey;
        this.expirationMs = expirationMs;
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 정식 JWT 토큰 생성 (로그인 완료 유저)
     */
    public String createToken(String userId, String nickname) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .setSubject(userId)
                .claim(CLAIM_NICKNAME, nickname)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 임시 토큰 생성 (닉네임 설정 전, 신규 유저)
     */
    public String createTempToken(String googleId, String email, String name, String picture) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + TEMP_TOKEN_EXPIRATION_MS);

        return Jwts.builder()
                .setSubject(googleId)
                .claim(CLAIM_TYPE, TYPE_TEMP)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_NAME, name)
                .claim(CLAIM_PICTURE, picture)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 토큰이 정식 access token인지 확인
     */
    public boolean isAccessToken(String token) {
        Claims claims = parseToken(token);
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /**
     * 토큰이 임시 토큰인지 확인
     */
    public boolean isTempToken(String token) {
        Claims claims = parseToken(token);
        return TYPE_TEMP.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /**
     * 임시 토큰에서 이메일 추출
     */
    public String getEmailFromTempToken(String token) {
        return parseToken(token).get(CLAIM_EMAIL, String.class);
    }

    /**
     * 임시 토큰에서 이름 추출
     */
    public String getNameFromTempToken(String token) {
        return parseToken(token).get(CLAIM_NAME, String.class);
    }

    /**
     * 임시 토큰에서 프로필 이미지 추출
     */
    public String getPictureFromTempToken(String token) {
        return parseToken(token).get(CLAIM_PICTURE, String.class);
    }

    /** ✅ 토큰 파싱 */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** ✅ userId 추출 */
    public String getUserId(String token) {
        return parseToken(token).getSubject();
    }

    public String getNickname(String token) {
        return parseToken(token).get("nickname", String.class);
    }


    /** ✅ 토큰 유효성 검사 (MVP용) */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


}
