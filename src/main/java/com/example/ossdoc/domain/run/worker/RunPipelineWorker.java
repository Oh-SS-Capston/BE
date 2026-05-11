package com.example.ossdoc.domain.run.worker;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.service.RunPipelineExecutor;
import com.example.ossdoc.domain.run.service.RunPipelineQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

/*
 * DB 기반 파이프라인 worker입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPipelineWorker {

    private final RunPipelineQueueService queueService;
    private final RunPipelineExecutor executor;

    @Value("${ossdoc.pipeline.worker.max-jobs-per-tick:1}")
    private int maxJobsPerTick;

    private final String workerId = buildWorkerId();

    @Scheduled(fixedDelayString = "${ossdoc.pipeline.worker.fixed-delay-ms:2000}")
    public void poll() {
        for (int i = 0; i < maxJobsPerTick; i++) {
            Optional<RunPipelineJob> claimed = queueService.claimNextJob(workerId);

            if (claimed.isEmpty()) {
                return;
            }

            RunPipelineJob job = claimed.get();

            log.info(
                    "[PIPELINE_WORKER] Claimed job. workerId={}, jobId={}, runId={}",
                    workerId,
                    job.getJobId(),
                    job.getRun().getRunId()
            );

            executor.execute(job.getJobId());
        }
    }

    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}