package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.AnalysisAccessType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * lock 갱신 가드 검증.
 *
 * <p>LLM 단계 하나가 claim lock(30분)보다 오래 걸려(실측 67분) 갱신이 필요해졌는데,
 * 갱신이 소유권·상태를 보지 않으면 이미 남에게 넘어간 job이나 캐시 대기로 빠진 job을
 * 되살려 오히려 중복 실행을 만든다.</p>
 */
class RunPipelineJobLockRenewTest {

    private static final String WORKER = "worker-A";

    @Test
    void renewsLockWhenSameWorkerStillRunsTheJob() {
        RunPipelineJob job = claimedJob();
        LocalDateTime extended = LocalDateTime.now().plusMinutes(30);

        boolean renewed = job.renewLock(WORKER, extended);

        assertThat(renewed).isTrue();
        assertThat(job.getLockedUntil()).isEqualTo(extended);
    }

    @Test
    void doesNotRenewLockHeldByAnotherWorker() {
        RunPipelineJob job = claimedJob();
        LocalDateTime lockedUntilBefore = job.getLockedUntil();

        boolean renewed = job.renewLock("worker-B", LocalDateTime.now().plusMinutes(30));

        assertThat(renewed).isFalse();
        assertThat(job.getLockedUntil()).isEqualTo(lockedUntilBefore);
    }

    @Test
    void doesNotRenewAfterJobFinished() {
        RunPipelineJob job = claimedJob();
        job.markSuccess();

        boolean renewed = job.renewLock(WORKER, LocalDateTime.now().plusMinutes(30));

        assertThat(renewed).isFalse();
    }

    @Test
    void doesNotRenewJobThatMovedToCacheWaiting() {
        // markCacheWaiting은 상태를 RUNNING으로 두고 lock만 비운다. 여기서 되살리면
        // claim 대상에서 빠져 있어야 할 job이 다시 lock을 갖게 된다.
        RunPipelineJob job = claimedJob();
        job.markCacheWaiting("동일 커밋 분석 결과를 기다립니다.");

        boolean renewed = job.renewLock(WORKER, LocalDateTime.now().plusMinutes(30));

        assertThat(renewed).isFalse();
        assertThat(job.getLockedUntil()).isNull();
    }

    private RunPipelineJob claimedJob() {
        RepoRun run = new RepoRun(
                "run_test_lock",
                null,
                "https://github.com/junit-team/junit-framework.git",
                "junit-team",
                "junit-framework",
                "main",
                "9cd9a3cfb6cd98aec355bd49fc8d801058762441",
                "C:/data/ossdoc/run_test_lock",
                AnalysisAccessType.TOKEN
        );

        RunPipelineJob job = RunPipelineJob.create(run, 1L);
        LocalDateTime now = LocalDateTime.now();
        job.claim(WORKER, now, now.plusMinutes(30));
        return job;
    }
}
