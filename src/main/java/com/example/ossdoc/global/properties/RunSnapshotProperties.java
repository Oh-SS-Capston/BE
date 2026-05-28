package com.example.ossdoc.global.properties;

import com.example.ossdoc.domain.run.enums.SnapshotMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Snapshot 단계 동작 방식을 외부 설정으로 제어한다.
 *
 * 운영 중 문제가 생기면 ossdoc.snapshot.mode=ZIP 한 줄로 즉시 기존 방식으로 복귀 가능하다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.snapshot")
public class RunSnapshotProperties {

    /**
     * 스냅샷 수집 방식. 기본값은 ZIP(현 동작 유지).
     */
    private SnapshotMode mode = SnapshotMode.ZIP;

    /**
     * git 실행 파일 경로. 비워두면 PATH 상의 git을 사용한다.
     */
    private String gitCommand = "git";

    /**
     * git 명령 1회 실행 타임아웃(초).
     */
    private int gitTimeoutSeconds = 180;
}
