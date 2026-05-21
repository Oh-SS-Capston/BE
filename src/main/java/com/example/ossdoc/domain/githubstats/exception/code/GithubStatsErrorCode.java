package com.example.ossdoc.domain.githubstats.exception.code;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GithubStatsErrorCode implements BaseCode {

    GITHUB_STATS_RUN_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "GITHUBSTATS404_1",
            "존재하지 않는 run 입니다."
    ),

    GITHUB_STATS_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "GITHUBSTATS403_1",
            "해당 run 접근 권한이 없습니다."
    ),

    GITHUB_STATS_INVALID_REPOSITORY(
            HttpStatus.BAD_REQUEST,
            "GITHUBSTATS400_1",
            "GitHub 저장소 정보가 올바르지 않습니다."
    ),

    GITHUB_STATS_REPOSITORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "GITHUBSTATS404_2",
            "GitHub 저장소를 찾을 수 없습니다."
    ),

    GITHUB_STATS_API_FAILED(
            HttpStatus.BAD_GATEWAY,
            "GITHUBSTATS502_1",
            "GitHub 통계 API 호출에 실패했습니다."
    ),

    GITHUB_STATS_RESPONSE_PARSE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "GITHUBSTATS500_1",
            "GitHub 통계 응답 처리에 실패했습니다."
    ),

    GITHUB_STATS_CACHE_READ_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "GITHUBSTATS500_2",
            "GitHub 통계 캐시 조회에 실패했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public ReasonDTO getReason() {
        return ReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .build();
    }

    @Override
    public ReasonDTO getReasonHttpStatus() {
        return ReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .httpStatus(httpStatus)
                .build();
    }
}