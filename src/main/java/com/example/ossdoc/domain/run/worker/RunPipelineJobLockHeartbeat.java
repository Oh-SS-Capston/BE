package com.example.ossdoc.domain.run.worker;

import com.example.ossdoc.domain.run.service.RunPipelineQueueService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/*
 * 실행 중인 파이프라인 job의 lock을 주기적으로 연장합니다.
 *
 * 왜 별도 스레드인가:
 * - worker는 @Scheduled 스케줄러 스레드에서 executor.execute()를 동기 호출하고,
 *   스케줄러 풀 크기는 기본 1입니다. 즉 job이 도는 동안 @Scheduled는 하나도 못 돕니다.
 *   같은 스케줄러에 heartbeat를 얹으면 정작 필요한 순간에 실행되지 않습니다.
 * - 그래서 데몬 스레드 하나를 따로 두고, 실행을 감싸는 동안에만 heartbeat를 켭니다.
 *
 * 왜 필요한가:
 * - claim이 잡는 lock은 30분인데 LLM 단계 하나가 그보다 오래 걸립니다(실측 67분).
 *   갱신하지 않으면 lock 만료 후 다른 인스턴스가 같은 job을 다시 집어가 중복 실행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPipelineJobLockHeartbeat {

    private final RunPipelineQueueService queueService;

    /*
     * lock(30분)보다 충분히 짧아야 합니다. 한 번 걸러도 다음 주기가 만료 전에 오도록 기본 10분으로 둡니다.
     */
    @Value("${ossdoc.pipeline.worker.lock-renew-interval-ms:600000}")
    private long lockRenewIntervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pipeline-lock-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * heartbeat를 켠 채로 job 실행을 수행합니다.
     *
     * 실행이 어떻게 끝나든(정상/예외) heartbeat는 finally에서 멈춥니다.
     * 실행이 끝난 job은 상태가 RUNNING이 아니거나 lock이 비워져 있어
     * 혹시 남은 주기가 한 번 더 돌더라도 renewLock이 false로 떨어집니다.
     */
    public void runWithHeartbeat(Long jobId, String workerId, Runnable execution) {
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(
                () -> renewQuietly(jobId, workerId),
                lockRenewIntervalMs,
                lockRenewIntervalMs,
                TimeUnit.MILLISECONDS
        );

        try {
            execution.run();
        } finally {
            heartbeat.cancel(false);
        }
    }

    /*
     * heartbeat 스레드에서 던지면 이후 주기가 통째로 중단되므로 여기서 모두 잡습니다.
     * lock을 잃은 경우(false)는 중복 실행이 이미 시작됐을 수 있다는 신호라 WARN으로 남깁니다.
     */
    private void renewQuietly(Long jobId, String workerId) {
        try {
            boolean renewed = queueService.renewLock(jobId, workerId);

            if (renewed) {
                log.debug("[PIPELINE_WORKER] lock renewed. jobId={}, workerId={}", jobId, workerId);
                return;
            }

            log.warn(
                    "[PIPELINE_WORKER] lock renew skipped. 이미 lock을 잃었거나 실행 중이 아닙니다."
                            + " jobId={}, workerId={}",
                    jobId,
                    workerId
            );
        } catch (Exception e) {
            log.warn("[PIPELINE_WORKER] lock renew failed. jobId={}, workerId={}", jobId, workerId, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
