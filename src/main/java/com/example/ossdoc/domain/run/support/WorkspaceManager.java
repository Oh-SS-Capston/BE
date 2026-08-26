package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.global.properties.WorkspaceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class WorkspaceManager {

    private final WorkspaceProperties workspaceProperties;

    /** 특정 Run의 최상위 작업 디렉토리 경로 반환 */
    public Path workspaceRoot(String runId) {
        return Path.of(workspaceProperties.getBaseDir(), runId)
                .toAbsolutePath()
                .normalize();
    }

    /** 분석 결과물이 저장될 폴더 경로 반환 */
    public Path artifactsDir(Path workspaceRoot) {
        return workspaceRoot.resolve("artifacts");
    }
}
