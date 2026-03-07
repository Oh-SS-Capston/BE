package com.example.ossdoc.domain.build.dto.json;

import lombok.*;

@Getter @Builder
@NoArgsConstructor @AllArgsConstructor
public class BuildFailure {
    private String code;
    private String message;
    private String moduleHint; // ":app" 같은 힌트
    private String logHint;    // 로그 앞부분 요약
}
