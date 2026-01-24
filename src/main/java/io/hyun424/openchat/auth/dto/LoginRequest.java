package io.hyun424.openchat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class LoginRequest {

    @NotBlank(message = "userId는 필수입니다.")
    @Size(max = 100, message = "userId는 100자 이하여야 합니다.")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "userId는 영문, 숫자, 밑줄, 하이픈만 사용할 수 있습니다.")
    private String userId;

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
    @Pattern(regexp = "^[a-zA-Z0-9가-힣]+$", message = "닉네임은 영어, 숫자, 한글만 사용할 수 있습니다.")
    private String nickname;
}
