package io.hyun424.openchat.auth.service;

import io.hyun424.openchat.auth.entity.User;
import io.hyun424.openchat.auth.repository.UserRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    /**
     * Google ID로 사용자 조회
     */
    public Optional<User> findById(String googleId) {
        return userRepository.findById(googleId);
    }

    /**
     * 닉네임 중복 체크
     */
    public boolean isNicknameAvailable(String nickname) {
        return !userRepository.existsByNickname(nickname);
    }

    /**
     * 신규 유저 생성
     */
    @Transactional
    public User createUser(String googleId, String email, String nickname, String profileImage) {
        if (userRepository.existsByNickname(nickname)) {
            throw new ApiException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        User user = User.builder()
                .id(googleId)
                .email(email)
                .nickname(nickname)
                .profileImage(profileImage)
                .provider("google")
                .build();

        return userRepository.save(user);
    }

    /**
     * 로그인 시간 갱신
     */
    @Transactional
    public void updateLastLogin(String userId) {
        userRepository.findById(userId)
                .ifPresent(User::updateLastLogin);
    }

    /**
     * 닉네임 변경
     */
    @Transactional
    public void updateNickname(String userId, String newNickname) {
        if (userRepository.existsByNickname(newNickname)) {
            throw new ApiException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        user.updateNickname(newNickname);
    }

    /**
     * 사용자 조회 (필수)
     */
    public User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }
}
