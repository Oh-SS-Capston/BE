package com.example.ossdoc.domain.run.support;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class WorkspaceManager {

    // TODO: application.yml로 빼는 걸 추천 (ex. ossdoc.workspace.base-dir=/data/ossdoc)
    private final String baseDir = "/data/ossdoc";

    /** 특정 Run의 최상위 작업 디렉토리 경로 반환 */
    public Path workspaceRoot(String runId) {
        return Path.of(baseDir, runId);
    }

    /** 분석 결과물이 저장될 폴더 경로 반환 */
    public Path artifactsDir(Path workspaceRoot) {
        return workspaceRoot.resolve("artifacts");
    }
}