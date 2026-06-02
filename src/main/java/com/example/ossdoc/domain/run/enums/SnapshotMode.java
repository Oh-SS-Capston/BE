package com.example.ossdoc.domain.run.enums;

/**
 * Repository snapshot 수집 방식.
 *
 * ZIP: codeload zip 다운로드 + 압축 해제 (기본). git CLI 의존성 없음.
 * CLONE: shallow git fetch 후 commit SHA checkout. git 메타데이터 의존 build 통과율 향상.
 */
public enum SnapshotMode {
    ZIP,
    CLONE
}
