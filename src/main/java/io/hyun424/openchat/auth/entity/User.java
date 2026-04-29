package io.hyun424.openchat.auth.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "uk_email", columnList = "email", unique = true),
        @Index(name = "uk_nickname", columnList = "nickname", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private String id;  // Google sub (고유 ID)

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true, length = 20)
    private String nickname;

    private String profileImage;

    @Column(nullable = false)
    private String provider;  // "google"

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastLoginAt;

    @Builder
    public User(String id, String email, String nickname, String profileImage, String provider) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.profileImage = profileImage;
        this.provider = provider;
        this.createdAt = Instant.now();
        this.lastLoginAt = Instant.now();
    }

    public void updateLastLogin() {
        this.lastLoginAt = Instant.now();
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }
}
