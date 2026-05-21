package com.example.ossdoc.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NicknameUpdateRequest(

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 10, message = "닉네임은 2자 이상 10자 이하로 입력해주세요.")
        @Pattern(
                regexp = "^[가-힣a-zA-Z0-9_]+$",
                message = "닉네임은 한글, 영문, 숫자, 밑줄(_)만 사용할 수 있습니다."
        )
        String nickname
) {
}